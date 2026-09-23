package agent_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
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
	c, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	cl := &client{c: c, t: t, sid: session}
	cl.send(map[string]any{
		"type": "hello", "session_id": session, "token": token,
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
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	_ = c.WriteJSON(map[string]any{
		"type": "hello", "session_id": "s", "token": "wrong",
		"content": []map[string]string{{"text": "resume:0"}},
	})
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("expected error frame, got read error: %v", err)
	}
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	if out["kind"] != "error" || out["text"] != "unauthorized" {
		t.Fatalf("frame = %v, want error/unauthorized", out)
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
	public, stop, err := ag.RunQuickTunnel(t.Context(), 1)
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
