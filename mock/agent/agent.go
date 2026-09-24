// Package agent is a mock ai daemon: WebSocket server + background job runner
// that behaves like Tulipskun/ai for AIxodia testing. It deliberately keeps
// jobs running after the phone disconnects (that is the product requirement):
// every step is persisted through mockdb first, then broadcast to whoever is
// connected. A reconnecting app pulls the latest rows from the DB and gets the
// live tail again.
package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Tulipskun/AIxodia/mock/mockdb"
	"github.com/gorilla/websocket"
)

type Config struct {
	DB     *mockdb.Store
	Token  string
	DBBase string // used for heartbeat posts (e.g. http://127.0.0.1:8787)
	// MirrorBase, when set, is the real AIxodia Worker (Cloudflare D1). Every
	// turn is mirrored there as well, and tunnel heartbeats are announced
	// there instead of the local mock DB. That makes the mock agent usable
	// against production storage without changing the phone app.
	MirrorBase string
	// WorkerToken is the credential for MirrorBase (the AIXODIA_TOKEN Worker
	// secret). It is separate from Token, which is the local WebSocket auth, so
	// the daemon can accept phones with their own token without giving the
	// local network the cloud credential (and vice versa).
	WorkerToken string
	Version     string
	StepDelay   time.Duration
	TunnelBin   string        // override for tests; default "cloudflared"
	TunnelWait  time.Duration // how long to wait for the public URL (default 45s)
	OpenTunnel  bool
}

type Agent struct {
	cfg      Config
	gate     *gate
	upgrader websocket.Upgrader
	mu       sync.Mutex
	subs     map[string]map[*websocket.Conn]struct{}
	jobs     atomic.Int64
	// mirrorCh keeps cloud writes in the exact order the job produced them.
	// One worker goroutine drains it, so D1 assigns seq in job order and the
	// app can render the thread straight from seq. Firing one goroutine per
	// turn races and interleaves (found in the real-D1 test).
	mirrorCh chan mirrorItem
}

type mirrorItem struct {
	session string
	role    string
	agent   string
	jobID   string
	text    string
}

type ContentPart struct {
	Type string `json:"type"`
	Text string `json:"text,omitempty"`
}

type In struct {
	Type        string        `json:"type"`
	Source      string        `json:"source"`
	SessionID   string        `json:"session_id"`
	Role        string        `json:"role"`
	Content     []ContentPart `json:"content"`
	ClientMsgID string        `json:"client_msg_id"`
	Token       string        `json:"token"`
}

type ToolCall struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

type Out struct {
	Kind         string        `json:"kind"` // ack | message | trace | done | error
	SessionID    string        `json:"session_id"`
	Role         string        `json:"role"`
	Agent        string        `json:"agent"`
	JobID        string        `json:"job_id"`
	Seq          int64         `json:"seq"`
	Stage        string        `json:"stage"`
	Text         string        `json:"text"`
	Content      []ContentPart `json:"content,omitempty"`
	ToolCall     *ToolCall     `json:"tool_call,omitempty"`
	ClientMsgID  string        `json:"client_msg_id,omitempty"`
	InputTokens  int           `json:"input_tokens,omitempty"`
	OutputTokens int           `json:"output_tokens,omitempty"`
}

func New(cfg Config) *Agent {
	if cfg.StepDelay == 0 {
		cfg.StepDelay = 350 * time.Millisecond
	}
	if cfg.Version == "" {
		cfg.Version = "mock-1"
	}
	if cfg.TunnelBin == "" {
		cfg.TunnelBin = "cloudflared"
	}
	if cfg.TunnelWait == 0 {
		cfg.TunnelWait = 45 * time.Second
	}
	a := &Agent{
		cfg:      cfg,
		subs:     map[string]map[*websocket.Conn]struct{}{},
		mirrorCh: make(chan mirrorItem, 256),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
	a.gate = newGate()
	a.gate.verify = storeVerifier{a}.VerifyToken
	if cfg.MirrorBase != "" {
		go a.mirrorWorker()
	}
	return a
}

func (a *Agent) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", a.serveWS)
	if a.cfg.MirrorBase != "" {
		// One address for the phone: the same tunnel serves history (proxied to
		// the Worker with the token the phone sent) and the live socket.
		mux.Handle("/api/", a.historyProxy())
	}
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok":true,"agent":"mock"}`))
	})
	return mux
}

// storeVerifier adapts the mock DB (or the real Worker) to the same
// "is this D1 token good?" contract the bridge uses.
type storeVerifier struct{ a *Agent }

func (v storeVerifier) VerifyToken(ctx context.Context, token string) error {
	if v.a.cfg.MirrorBase != "" {
		// Real production path: the Worker is the single source of truth.
		req, err := http.NewRequestWithContext(ctx, http.MethodGet,
			strings.TrimRight(v.a.cfg.MirrorBase, "/")+"/api/ping", nil)
		if err != nil {
			return err
		}
		if wt := v.a.workerToken(); wt != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return ErrTokenRejected
		}
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("worker ping HTTP %d", resp.StatusCode)
		}
		return nil
	}
	if !v.a.cfg.DB.VerifyToken(token) {
		return ErrTokenRejected
	}
	return nil
}

// ErrTokenRejected marks a wrong credential (counted toward lockout), as
// opposed to a transport failure (fail closed, never counted).
var ErrTokenRejected = errors.New("mock: token rejected")

func (a *Agent) serveWS(w http.ResponseWriter, r *http.Request) {
	// Same two-step handshake rule as the production bridge: unguessable
	// quick-tunnel URL + Authorization: Bearer <D1 token>, checked before the
	// socket exists, with the 5-failure progressive lockout.
	if d := a.gate.Check(r); !d.Allowed {
		a.gate.Write(w, d)
		return
	}
	c, err := a.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()

	var session string
	authed := a.cfg.DB.VerifyToken("") // token=="" store means open dev mode
	defer func() {
		if session != "" {
			a.mu.Lock()
			if set := a.subs[session]; set != nil {
				delete(set, c)
				if len(set) == 0 {
					delete(a.subs, session)
				}
			}
			a.mu.Unlock()
		}
	}()

	c.SetReadLimit(1 << 20)
	for {
		_, raw, err := c.ReadMessage()
		if err != nil {
			return
		}
		var in In
		if err := json.Unmarshal(raw, &in); err != nil {
			a.send(c, Out{Kind: "error", Text: "bad json"})
			return
		}
		// Auth is connection-scoped: the hello (or any first frame) must carry a
		// valid token; later frames on the same socket inherit it, which keeps
		// every later frame from carrying the secret again.
		_ = authed // auth happened at the handshake
		if in.SessionID == "" {
			a.send(c, Out{Kind: "error", Text: "session_id required"})
			return
		}
		session = in.SessionID
		a.subscribe(session, c)
		a.cfg.DB.EnsureSession(session, "", "")

		if in.Type == "hello" {
			resume := ""
			if len(in.Content) > 0 {
				resume = in.Content[0].Text
			}
			a.send(c, Out{Kind: "ack", SessionID: session, Role: "system", Stage: "resumed", Text: resume})
			continue
		}

		text := ""
		for _, part := range in.Content {
			text += part.Text
		}
		if strings.TrimSpace(text) == "" {
			continue
		}
		// The job is detached from this connection on purpose: closing the app
		// must not stop the agent (AX-013 / user requirement).
		a.startJob(session, text, in.ClientMsgID, c)
	}
}

func (a *Agent) subscribe(session string, c *websocket.Conn) {
	a.mu.Lock()
	defer a.mu.Unlock()
	set := a.subs[session]
	if set == nil {
		set = map[*websocket.Conn]struct{}{}
		a.subs[session] = set
	}
	set[c] = struct{}{}
}

func (a *Agent) send(c *websocket.Conn, out Out) {
	raw, err := json.Marshal(out)
	if err != nil {
		return
	}
	_ = c.WriteMessage(websocket.TextMessage, raw)
}

func (a *Agent) broadcast(out Out) {
	a.mu.Lock()
	set := a.subs[out.SessionID]
	conns := make([]*websocket.Conn, 0, len(set))
	for c := range set {
		conns = append(conns, c)
	}
	a.mu.Unlock()
	raw, err := json.Marshal(out)
	if err != nil {
		return
	}
	for _, c := range conns {
		_ = c.WriteMessage(websocket.TextMessage, raw)
	}
}

// emit persists the event in the mock DB first, then broadcasts it. A
// disconnected phone therefore loses nothing: it gets these rows on reopen.
func (a *Agent) emit(session, role, agent, stage, text, jobID, clientMsgID string, tool *ToolCall) {
	row := a.cfg.DB.AppendTurn(session, mockdb.Turn{
		Role:  role,
		Agent: agent,
		JobID: jobID,
		Text:  text,
	})
	a.mirror(session, role, agent, jobID, text)
	a.broadcast(Out{
		Kind:         kindFor(role, stage),
		SessionID:    session,
		Role:         role,
		Agent:        agent,
		JobID:        jobID,
		Seq:          row.Seq,
		Stage:        stage,
		Text:         text,
		Content:      []ContentPart{{Type: "text", Text: text}},
		ToolCall:     tool,
		ClientMsgID:  clientMsgID,
		InputTokens:  120,
		OutputTokens: 40,
	})
}

func kindFor(role, stage string) string {
	if role == "user" {
		return "message"
	}
	if strings.HasPrefix(stage, "tool_") {
		return "trace"
	}
	return "message"
}

func (a *Agent) sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func (a *Agent) startJob(session, text, clientMsgID string, c *websocket.Conn) {
	if c != nil {
		a.send(c, Out{Kind: "ack", SessionID: session, Role: "system", JobID: "", Text: clientMsgID, ClientMsgID: clientMsgID})
	}
	jobID := fmt.Sprintf("job-%d", a.jobs.Add(1))
	go func() {
		ctx := context.Background()
		// user turn (author of a title when the session has none)
		a.cfg.DB.AppendTurn(session, mockdb.Turn{Role: "user", Text: text, JobID: jobID})
		a.mirror(session, "user", "", jobID, text)
		sessions := a.cfg.DB.ListSessions()
		for _, ses := range sessions {
			if ses.ID == session && (ses.Title == "" || ses.Title == session) {
				a.cfg.DB.SetTitle(session, truncate(text, 42))
			}
		}
		a.broadcast(Out{
			Kind: "message", SessionID: session, Role: "user", JobID: jobID, Seq: lastSeq(a.cfg.DB, session),
			Text: text, Content: []ContentPart{{Type: "text", Text: text}}, ClientMsgID: clientMsgID,
		})

		// main agent plans
		a.emit(session, "model", "main", "request", "main: วางแผนงาน…", jobID, "", nil)
		if !a.sleep(ctx, a.cfg.StepDelay) {
			return
		}

		lower := strings.ToLower(text)
		plan := "ตรวจไฟล์ → สรุป → ตอบ"
		if strings.Contains(lower, "research") || strings.Contains(lower, "ค้น") {
			plan = "แตกงาน 2 ชิ้นให้ sub agent → รวมผล"
		}
		a.emit(session, "model", "main", "response", "main: "+plan, jobID, "", nil)
		if !a.sleep(ctx, a.cfg.StepDelay) {
			return
		}

		runSub := func(name, goal string, delay time.Duration) *string {
			callID := fmt.Sprintf("%s-%d", jobID, time.Now().UnixNano())
			a.emit(session, "tool_call", "sub", "tool_call",
				fmt.Sprintf("sub: %s → %s", name, goal), jobID, "",
				&ToolCall{ID: callID, Name: name, Arguments: `{"goal":"` + goal + `"}`})
			if !a.sleep(ctx, delay) {
				return nil
			}
			a.emit(session, "tool_call", "sub", "tool_running", name, jobID, "", nil)
			if !a.sleep(ctx, a.cfg.StepDelay) {
				return nil
			}
			res := fmt.Sprintf("%s เสร็จแล้ว: พบ 3 ไฟล์ที่เกี่ยวข้อง", name)
			a.emit(session, "tool_result", "sub", "tool_result", res, jobID, "", nil)
			return &res
		}

		if plan == "ตรวจไฟล์ → สรุป → ตอบ" {
			if runSub("read_files", "อ่าน index.md", a.cfg.StepDelay) == nil {
				return
			}
		} else {
			var wg sync.WaitGroup
			results := make([]*string, 2)
			names := []string{"web_fetch", "list_directory"}
			for i, name := range names {
				wg.Add(1)
				go func(i int, name string) {
					defer wg.Done()
					results[i] = runSub(name, "เก็บหลักฐานส่วน "+name, a.cfg.StepDelay)
				}(i, name)
			}
			wg.Wait()
			if results[0] == nil || results[1] == nil {
				return
			}
		}
		if !a.sleep(ctx, a.cfg.StepDelay) {
			return
		}

		answer := "สรุปจากการทำงานของ agent: งานเสร็จแล้ว ✅ (mock mode — ยังไม่ต่อ provider จริง)"
		if strings.Contains(lower, "hello") || strings.Contains(lower, "สวัสดี") {
			answer = "สวัสดี! ผมคือ main agent (mock) และ sub agent ทำงานให้อยู่เบื้องหลัง"
		}
		a.emit(session, "model", "main", "response_text", answer, jobID, "", nil)
		a.broadcast(Out{Kind: "done", SessionID: session, Role: "system", JobID: jobID, Seq: lastSeq(a.cfg.DB, session)})
	}()
}

func lastSeq(db *mockdb.Store, session string) int64 {
	turns := db.Turns(session, 0, 1)
	if len(turns) == 0 {
		return 0
	}
	return turns[len(turns)-1].Seq
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// ---- quick tunnel (same pattern as bridge/tunnel.go) ----

var tryURL = regexp.MustCompile(`https://[A-Za-z0-9.-]+\.trycloudflare\.com`)

func (a *Agent) RunQuickTunnel(ctx context.Context, port int) (string, func(), error) {
	// --metrics 127.0.0.1:0 keeps cloudflared off the "localhost" hostname, which
	// some containers cannot resolve (it then aborts before the tunnel connects).
	cmd := exec.CommandContext(ctx, a.cfg.TunnelBin, "tunnel", "--no-autoupdate",
		"--metrics", "127.0.0.1:0", "--url", fmt.Sprintf("http://127.0.0.1:%d", port))
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return "", nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return "", nil, err
	}
	if err := cmd.Start(); err != nil {
		return "", nil, err
	}
	urls := make(chan string, 1)
	// Real cloudflared prints the URL on stderr; scan both streams so wrappers
	// and test doubles that log to stdout work too.
	scan := func(r io.Reader) {
		buf := make([]byte, 4096)
		var acc string
		for {
			n, err := r.Read(buf)
			if n > 0 {
				acc += string(buf[:n])
				if m := tryURL.FindString(acc); m != "" {
					select {
					case urls <- m:
					default:
					}
				}
				if len(acc) > 1<<16 {
					acc = acc[len(acc)-4096:]
				}
			}
			if err != nil {
				return
			}
		}
	}
	go scan(stderr)
	go scan(stdout)
	var public string
	select {
	case public = <-urls:
	case <-time.After(a.cfg.TunnelWait):
		_ = cmd.Process.Kill()
		return "", nil, errors.New("timed out waiting for trycloudflare URL")
	case <-ctx.Done():
		_ = cmd.Process.Kill()
		return "", nil, ctx.Err()
	}

	done := make(chan struct{})
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				a.heartbeat(public)
			}
		}
	}()
	a.heartbeat(public)
	return public, func() {
		close(done)
		_ = cmd.Process.Kill()
	}, nil
}

// mirror queues one finished turn for the real Worker (D1). Writes are
// serialized in job order; the live display never waits for the cloud.
func (a *Agent) mirror(session, role, agent, jobID, text string) {
	if a.cfg.MirrorBase == "" {
		return
	}
	select {
	case a.mirrorCh <- mirrorItem{session: session, role: role, agent: agent, jobID: jobID, text: text}:
	default:
		log.Printf("mirror: queue full, dropping turn for %s", session)
	}
}

// mirrorWorker drains mirrorCh in FIFO order, retrying transient failures.
func (a *Agent) mirrorWorker() {
	client := &http.Client{Timeout: 20 * time.Second}
	for item := range a.mirrorCh {
		body, _ := json.Marshal(map[string]string{
			"role": item.role, "agent": item.agent, "job_id": item.jobID, "text": item.text,
		})
		url := strings.TrimRight(a.cfg.MirrorBase, "/") + "/api/sessions/" + url.PathEscape(item.session) + "/turns"
		for attempt := 0; attempt < 3; attempt++ {
			req, err := http.NewRequest("POST", url, bytes.NewReader(body))
			if err != nil {
				break
			}
			req.Header.Set("Content-Type", "application/json")
			if wt := a.workerToken(); wt != "" {
				req.Header.Set("Authorization", "Bearer "+wt)
			}
			resp, err := client.Do(req)
			if err == nil {
				code := resp.StatusCode
				_ = resp.Body.Close()
				if code >= 200 && code < 300 {
					break
				}
				if code < 500 {
					log.Printf("mirror: %s -> HTTP %d", url, code)
					break
				}
			}
			time.Sleep(time.Duration(attempt+1) * 500 * time.Millisecond)
		}
	}
}

func (a *Agent) workerToken() string {
	if a.cfg.WorkerToken != "" {
		return a.cfg.WorkerToken
	}
	return a.cfg.Token
}

func (a *Agent) heartbeat(public string) {
	if a.cfg.MirrorBase != "" {
		base := strings.TrimRight(a.cfg.MirrorBase, "/")
		body, _ := json.Marshal(map[string]string{"tunnel_url": public, "version": a.cfg.Version})
		req, _ := http.NewRequest("POST", base+"/api/node/heartbeat", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if a.cfg.Token != "" {
			req.Header.Set("Authorization", "Bearer "+a.cfg.Token)
		}
		if resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req); err == nil {
			_ = resp.Body.Close()
		}
		return
	}
	if a.cfg.DBBase == "" {
		a.cfg.DB.SetNode(public, a.cfg.Version)
		return
	}
	client := &http.Client{Timeout: 10 * time.Second}
	body, _ := json.Marshal(map[string]string{"tunnel_url": public, "version": a.cfg.Version})
	req, _ := http.NewRequest("POST", strings.TrimRight(a.cfg.DBBase, "/")+"/api/node/heartbeat", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	if a.cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+a.cfg.Token)
	}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("mock: heartbeat: %v", err)
		return
	}
	_ = resp.Body.Close()
}
