// Package bridge is a drop-in reference for Tulipskun/ai: a mobile WebSocket
// transport exposing canonical Input/Output JSON to the AIxodia Android app.
//
// Wire into cmd/ai (daemon): start Serve() with the Harness entry + display,
// behind your auth. It mirrors transport/discord gateway liveness policy
// (hello/resume + replay + backoff on the client side).
//
// Protocol (JSON text frames):
//
//	C->S hello:   {"type":"hello","session_id":"...","token":"...","content":[{"text":"resume:123"}]}
//	C->S message: {"type":"message","source":"mobile","session_id":"...","role":"user","content":[{"type":"text","text":"..."}]}
//	S->C message: {"kind":"message","session_id":"...","role":"model","seq":N,"text":"...","content":[...]}
//	S->C trace:   {"kind":"trace","stage":"tool_running","text":"..."} / {"kind":"done"}
package bridge

import (
	"encoding/json"
	"net/http"
	"sync"
	"sync/atomic"

	"github.com/gorilla/websocket"
)

type ContentPart struct {
	Type string `json:"type"`
	Text string `json:"text,omitempty"`
}

type MobileIn struct {
	Type      string        `json:"type"`
	Source    string        `json:"source"`
	SessionID string        `json:"session_id"`
	Role      string        `json:"role"`
	Content   []ContentPart `json:"content"`
	Token     string        `json:"token"`
}

type MobileOut struct {
	Kind      string        `json:"kind"`
	SessionID string        `json:"session_id"`
	Role      string        `json:"role"`
	Seq       int64         `json:"seq"`
	Stage     string        `json:"stage"`
	Text      string        `json:"text"`
	Content   []ContentPart `json:"content,omitempty"`
}

var upgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

type Hub struct {
	mu   sync.Mutex
	subs map[string]map[*websocket.Conn]struct{}
	seq  atomic.Int64
}

func NewHub() *Hub { return &Hub{subs: map[string]map[*websocket.Conn]struct{}{}} }

// Publish sends one canonical Output-equivalent to every subscriber of a session.
// Call it from your Harness display func; call IngestD1 (worker POST) alongside
// so Cloudflare D1 history and the live socket never diverge.
func (h *Hub) Publish(sessionID, role, text string, content []ContentPart) {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := MobileOut{Kind: "message", SessionID: sessionID, Role: role, Seq: h.seq.Add(1), Text: text, Content: content}
	raw, _ := json.Marshal(out)
	for c := range h.subs[sessionID] {
		_ = c.WriteMessage(websocket.TextMessage, raw)
	}
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// TODO: verify r (Bearer token) against your entry config before upgrade.
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	var session string
	h.mu.Lock()
	h.mu.Unlock()
	for {
		_, raw, err := c.ReadMessage()
		if err != nil {
			break
		}
		var in MobileIn
		if err := json.Unmarshal(raw, &in); err != nil {
			continue
		}
		if in.SessionID == "" {
			continue
		}
		session = in.SessionID
		h.mu.Lock()
		m := h.subs[session]
		if m == nil {
			m = map[*websocket.Conn]struct{}{}
			h.subs[session] = m
		}
		m[c] = struct{}{}
		h.mu.Unlock()
		if in.Type == "hello" {
			ack, _ := json.Marshal(MobileOut{Kind: "done", SessionID: session, Stage: "resumed"})
			_ = c.WriteMessage(websocket.TextMessage, ack)
			continue
		}
		// TODO: convert MobileIn -> sdk.Input and run HarnessLoop.Entry;
		// stream sdk TraceEvents as {"kind":"trace"} and final Output as {"kind":"message"} via Publish.
	}
	if session != "" {
		h.mu.Lock()
		delete(h.subs[session], c)
		h.mu.Unlock()
	}
}
