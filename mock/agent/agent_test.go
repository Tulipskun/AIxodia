package agent_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Tulipskun/AIxodia/mock/agent"
	"github.com/Tulipskun/AIxodia/mock/mockdb"
	"github.com/gorilla/websocket"
)

func newStack(t *testing.T, token string) (*mockdb.Store, *httptest.Server, *agent.Agent) {
	t.Helper()
	dir := t.TempDir()
	db := mockdb.New(token, filepath.Join(dir, "mockdb.json"))
	dbSrv := httptest.NewServer(db.Handler())
	t.Cleanup(dbSrv.Close)

	ag := agent.New(agent.Config{
		DB:        db,
		Token:     token,
		DBBase:    dbSrv.URL,
		Version:   "test",
		StepDelay: 10 * time.Millisecond,
	})
	agSrv := httptest.NewServer(ag.Handler())
	t.Cleanup(agSrv.Close)
	return db, agSrv, ag
}

type client struct {
	c   *websocket.Conn
	t   *testing.T
	sid string
}

func dial(t *testing.T, url, token, session string) *client {
	t.Helper()
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	c, _, err := websocket.DefaultDialer.Dial(url, h)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	cl := &client{c: c, t: t, sid: session}
	cl.send(map[string]any{
		"type": "hello", "session_id": session,
		"content": []map[string]string{{"type": "text", "text": "resume:0"}},
	})
	return cl
}

func (cl *client) send(v any) {
	cl.t.Helper()
	if err := cl.c.WriteJSON(v); err != nil {
		cl.t.Fatalf("write: %v", err)
	}
}

func (cl *client) next(timeout time.Duration) map[string]any {
	cl.t.Helper()
	_ = cl.c.SetReadDeadline(time.Now().Add(timeout))
	_, raw, err := cl.c.ReadMessage()
	if err != nil {
		cl.t.Fatalf("read: %v", err)
	}
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		cl.t.Fatalf("decode %s: %v", raw, err)
	}
	return out
}

func waitFor(t *testing.T, timeout time.Duration, cond func() bool) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return true
		}
		time.Sleep(15 * time.Millisecond)
	}
	return false
}

// AX-013 core requirement: the job must keep running and keep persisting after
// the app disconnects; on reopen the app gets everything back from the DB.
func TestJobContinuesAfterClientDisconnectAndIsReadableFromDB(t *testing.T) {
	token := "t0ken"
	db, agSrv, _ := newStack(t, token)
	wsURL := "ws" + strings.TrimPrefix(agSrv.URL, "http") + "/ws"

	cl := dial(t, wsURL, token, "s1")
	if got := cl.next(2 * time.Second); got["kind"] != "ack" {
		t.Fatalf("hello ack kind = %v, want ack", got["kind"])
	}
	cl.send(map[string]any{
		"type": "message", "session_id": "s1", "source": "mobile",
		"content":       []map[string]string{{"type": "text", "text": "สร้างฟีเจอร์ login"}},
		"client_msg_id": "s1:1",
	})
	if got := cl.next(2 * time.Second); got["kind"] != "ack" {
		t.Fatalf("message ack kind = %v, want ack", got["kind"])
	}

	// App closes mid-job: the socket goes away immediately.
	_ = cl.c.Close()

	// Agent keeps working with nobody listening.
	ok := waitFor(t, 5*time.Second, func() bool {
		turns := db.Turns("s1", 0, 200)
		for _, tr := range turns {
			if tr.Role == "model" && tr.Agent == "main" && strings.Contains(tr.Text, "mock mode") {
				return true
			}
		}
		return false
	})
	if !ok {
		t.Fatalf("job did not finish after disconnect; turns=%+v", db.Turns("s1", 0, 200))
	}

	turns := db.Turns("s1", 0, 200)
	var sawMain, sawSubTool, sawSubResult bool
	for _, tr := range turns {
		if tr.Agent == "main" && tr.Role == "model" {
			sawMain = true
		}
		if tr.Agent == "sub" && tr.Role == "tool_call" {
			sawSubTool = true
		}
		if tr.Agent == "sub" && tr.Role == "tool_result" {
			sawSubResult = true
		}
	}
	if !sawMain || !sawSubTool || !sawSubResult {
		t.Fatalf("missing agent attribution: main=%v sub_tool=%v sub_result=%v", sawMain, sawSubTool, sawSubResult)
	}
	if turns[0].Role != "user" {
		t.Fatalf("first turn role = %q, want user", turns[0].Role)
	}
	for i := 1; i < len(turns); i++ {
		if turns[i].Seq <= turns[i-1].Seq {
			t.Fatalf("seq not monotonic: %d after %d", turns[i].Seq, turns[i-1].Seq)
		}
	}
}

func TestMultipleSessionsAreIsolated(t *testing.T) {
	token := "t0ken"
	db, agSrv, _ := newStack(t, token)
	wsURL := "ws" + strings.TrimPrefix(agSrv.URL, "http") + "/ws"

	a := dial(t, wsURL, token, "alpha")
	b := dial(t, wsURL, token, "beta")
	a.next(2 * time.Second)
	b.next(2 * time.Second)

	a.send(map[string]any{"type": "message", "session_id": "alpha",
		"content": []map[string]string{{"text": "งานของ session alpha"}}})
	b.send(map[string]any{"type": "message", "session_id": "beta",
		"content": []map[string]string{{"text": "งานของ session beta"}}})

	alphaDone := waitFor(t, 5*time.Second, func() bool {
		for _, tr := range db.Turns("alpha", 0, 200) {
			if strings.Contains(tr.Text, "mock mode") {
				return true
			}
		}
		return false
	})
	betaDone := waitFor(t, 5*time.Second, func() bool {
		for _, tr := range db.Turns("beta", 0, 200) {
			if strings.Contains(tr.Text, "mock mode") {
				return true
			}
		}
		return false
	})
	if !alphaDone || !betaDone {
		t.Fatalf("jobs did not finish: alpha=%v beta=%v", alphaDone, betaDone)
	}

	for _, tr := range db.Turns("alpha", 0, 200) {
		if strings.Contains(tr.Text, "session beta") {
			t.Fatalf("alpha leaked beta text: %+v", tr)
		}
	}
	sessions := db.ListSessions()
	if len(sessions) != 2 {
		t.Fatalf("sessions = %d, want 2 (%+v)", len(sessions), sessions)
	}
	var alphaTitle string
	for _, s := range sessions {
		if s.ID == "alpha" {
			alphaTitle = s.Title
		}
	}
	if !strings.Contains(alphaTitle, "alpha") {
		t.Fatalf("alpha title = %q, want derived from first user message", alphaTitle)
	}
}

func TestUnauthorizedTokenIsRejected(t *testing.T) {
	db, agSrv, _ := newStack(t, "right")
	wsURL := "ws" + strings.TrimPrefix(agSrv.URL, "http") + "/ws"
	// Wrong token now fails at the handshake, not after the socket opens.
	h := http.Header{}
	h.Set("Authorization", "Bearer wrong")
	c, resp, err := websocket.DefaultDialer.Dial(wsURL, h)
	if err == nil {
		c.Close()
		t.Fatal("expected handshake rejection for a wrong token")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %v, want 401", resp)
	}
	if len(db.ListSessions()) != 0 {
		t.Fatalf("unauthorized hello created a session")
	}
}

func TestReconnectSeesLiveTailAgain(t *testing.T) {
	token := "t0ken"
	db, agSrv, _ := newStack(t, token)
	wsURL := "ws" + strings.TrimPrefix(agSrv.URL, "http") + "/ws"

	first := dial(t, wsURL, token, "s2")
	first.next(2 * time.Second)
	first.send(map[string]any{"type": "message", "session_id": "s2",
		"content": []map[string]string{{"text": "hello"}}})
	first.next(2 * time.Second) // ack
	_ = first.c.Close()

	waitFor(t, 5*time.Second, func() bool {
		for _, tr := range db.Turns("s2", 0, 200) {
			if strings.Contains(tr.Text, "mock mode") {
				return true
			}
		}
		return false
	})

	// Reopen: pull newest from DB (what the app does) and get live tail again.
	turns := db.Turns("s2", 0, 50)
	if len(turns) < 4 {
		t.Fatalf("expected history after reopen, got %d turns", len(turns))
	}
	last := turns[len(turns)-1]

	second := dial(t, wsURL, token, "s2")
	if got := second.next(2 * time.Second); got["kind"] != "ack" {
		t.Fatalf("hello ack = %v", got)
	}
	second.send(map[string]any{"type": "message", "session_id": "s2",
		"content": []map[string]string{{"text": "งานที่สอง"}}})
	_ = second.next(2 * time.Second) // ack

	gotFinal := false
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		frame := second.next(2 * time.Second)
		if frame["kind"] == "done" {
			gotFinal = true
			break
		}
	}
	if !gotFinal {
		t.Fatalf("no done frame after reconnect")
	}
	fresh := db.Turns("s2", last.Seq+1, 200)
	if len(fresh) == 0 {
		t.Fatalf("new job did not persist after resume seq %d", last.Seq)
	}
}

func TestQuickTunnelAnnouncesURLToDB(t *testing.T) {
	dir := t.TempDir()
	fake := filepath.Join(dir, "fake-cloudflared")
	script := "#!/bin/sh\necho 'INF |  https://mock-test.trycloudflare.com  | tunnel url' >&2\nsleep 30\n"
	if err := os.WriteFile(fake, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	db := mockdb.New("", filepath.Join(dir, "db.json"))
	ag := agent.New(agent.Config{DB: db, Version: "t", TunnelBin: fake, TunnelWait: 5 * time.Second})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	public, stop, err := ag.RunQuickTunnel(ctx, 1)
	if err != nil {
		t.Fatalf("RunQuickTunnel: %v", err)
	}
	defer stop()
	if public != "https://mock-test.trycloudflare.com" {
		t.Fatalf("public = %q", public)
	}
	info := db.NodeInfo()
	if info["tunnel_url"] != "https://mock-test.trycloudflare.com" || info["online"] != true {
		t.Fatalf("node info = %v", info)
	}
}

func TestDBHTTPPParity(t *testing.T) {
	dir := t.TempDir()
	db := mockdb.New("t0ken", filepath.Join(dir, "db.json"))
	srv := httptest.NewServer(db.Handler())
	defer srv.Close()

	get := func(path, token string) (*http.Response, string) {
		req, _ := http.NewRequest("GET", srv.URL+path, nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 4096)
		n, _ := resp.Body.Read(buf)
		_ = resp.Body.Close()
		return resp, string(buf[:n])
	}

	if resp, _ := get("/api/sessions", "wrong"); resp.StatusCode != 401 {
		t.Fatalf("bad token status = %d, want 401", resp.StatusCode)
	}
	resp, body := get("/api/sessions", "t0ken")
	if resp.StatusCode != 200 || strings.TrimSpace(body) != "[]" {
		t.Fatalf("sessions = %d %q", resp.StatusCode, body)
	}
	resp, _ = get("/api/node", "t0ken")
	if resp.StatusCode != 200 {
		t.Fatalf("node status = %d", resp.StatusCode)
	}
}

// The mock agent must be able to write into the real Worker (production D1)
// while the phone talks to it over the local WebSocket.
func TestTurnsAreMirroredToWorker(t *testing.T) {
	// One token, like production: the phone sends the D1 token in the
	// handshake header and the daemon verifies it against the Worker.
	token := "d1-token"
	db := mockdb.New(token, filepath.Join(t.TempDir(), "db.json"))
	var mu sync.Mutex
	var got []map[string]any
	mirror := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if auth := r.Header.Get("Authorization"); auth != "Bearer "+token {
			w.WriteHeader(401)
			return
		}
		if r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/api/ping") {
			w.WriteHeader(200) // real Worker answers 200 for a valid token
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		got = append(got, body)
		mu.Unlock()
		w.WriteHeader(200)
	}))
	defer mirror.Close()

	ag := agent.New(agent.Config{
		DB: db, Token: token, MirrorBase: mirror.URL,
		Version: "t", StepDelay: 5 * time.Millisecond,
	})
	srv := httptest.NewServer(ag.Handler())
	defer srv.Close()

	cl := dial(t, "ws"+strings.TrimPrefix(srv.URL, "http")+"/ws", token, "mirror-1")
	cl.next(2 * time.Second)
	cl.send(map[string]any{"type": "message", "session_id": "mirror-1",
		"content": []map[string]string{{"text": "ส่งขึ้น Worker จริง"}}})

	if !waitFor(t, 5*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		for _, b := range got {
			if txt, ok := b["text"].(string); ok && b["agent"] == "main" && strings.Contains(txt, "mock mode") {
				return true
			}
		}
		return false
	}) {
		mu.Lock()
		t.Fatalf("nothing mirrored: %v", got)
	}
	mu.Lock()
	defer mu.Unlock()
	var sawSub, sawMainFinal bool
	for _, b := range got {
		if b["agent"] == "sub" {
			sawSub = true
		}
		if txt, ok := b["text"].(string); ok && b["agent"] == "main" && strings.Contains(txt, "mock mode") {
			sawMainFinal = true
		}
	}
	if !sawSub || !sawMainFinal {
		t.Fatalf("agent attribution lost in mirror: sub=%v main_final=%v", sawSub, sawMainFinal)
	}
	// Order matters: D1 assigns seq on arrival, so the mirror must be FIFO or
	// the phone renders the thread scrambled.
	if got[0]["role"] != "user" {
		t.Fatalf("first mirrored turn = %v, want the user turn", got[0])
	}
	last := got[len(got)-1]
	if txt, _ := last["text"].(string); last["agent"] != "main" || !strings.Contains(txt, "mock mode") {
		t.Fatalf("last mirrored turn = %v, want the main agent final answer", last)
	}
}

// AX-071: the handshake must carry the D1 token in the Authorization header;
// no header means no socket at all, and a wrong token is counted so five
// failures lock the client out for 30s.
func TestHandshakeRequiresHeaderAndLocksOutAfterFiveFailures(t *testing.T) {
	token := "d1-token"
	_, agSrv, _ := newStack(t, token)
	wsURL := "ws" + strings.TrimPrefix(agSrv.URL, "http") + "/ws"

	try := func(auth string) *http.Response {
		h := http.Header{}
		if auth != "" {
			h.Set("Authorization", auth)
		}
		_, resp, err := websocket.DefaultDialer.Dial(wsURL, h)
		if err != nil && resp == nil {
			t.Fatalf("dial error without response: %v", err)
		}
		return resp
	}

	if resp := try(""); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no header: status = %d, want 401", resp.StatusCode)
	}
	if resp := try("Basic abc"); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong scheme: status = %d, want 401", resp.StatusCode)
	}

	var locked *http.Response
	for i := 1; i <= 6; i++ {
		resp := try("Bearer wrong-" + strconv.Itoa(i))
		if i <= 4 && resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("attempt %d: status = %d, want 401", i, resp.StatusCode)
		}
		if i == 5 {
			if resp.StatusCode != http.StatusTooManyRequests {
				t.Fatalf("5th failure: status = %d, want 429", resp.StatusCode)
			}
			if ra := resp.Header.Get("Retry-After"); ra == "" || ra == "0" {
				t.Fatalf("5th failure: Retry-After = %q, want a positive lockout", ra)
			}
			locked = resp
		}
	}
	if locked == nil {
		t.Fatal("never locked out")
	}
	// Even the right token is refused while the lockout is active.
	if resp := try("Bearer " + token); resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("valid token during lockout: status = %d, want 429", resp.StatusCode)
	}
}
