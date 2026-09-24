// Package bridge is a drop-in reference for Tulipskun/ai: a mobile WebSocket
// transport exposing canonical Input/Output JSON to the AIxodia Android app.
//
// Wire into cmd/ai (daemon): start Serve() with the Harness entry + display,
// behind your auth. It mirrors transport/discord gateway liveness policy
// (hello/resume + replay + backoff on the client side).
//
// Protocol (JSON text frames). The D1 token travels in the HTTP Authorization
// header of the WebSocket handshake, never inside a frame:
//
//	headers:      Authorization: Bearer <D1 token>
//
//	C->S hello:   {"type":"hello","session_id":"...","content":[{"text":"resume:123"}]}
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
	mu    sync.Mutex
	subs  map[string]map[*websocket.Conn]struct{}
	seq   atomic.Int64
	state *StateClient // stateless runtime data in D1 (nil = local disk mode)
	gate  *Gate        // handshake check + progressive lockout
}

// NewHub builds the hub. state carries the D1 token verification; when nil the
// hub only serves the local mock store (development), and Gate is skipped.
func NewHub(state *StateClient) *Hub {
	h := &Hub{subs: map[string]map[*websocket.Conn]struct{}{}, state: state}
	if state != nil {
		h.gate = NewGate(GateConfig{Verify: state})
	}
	return h
}

// State exposes the D1-backed runtime store (nil when not configured).
func (h *Hub) State() *StateClient { return h.state }

// Gate exposes the handshake gate (nil in local-only mode).
func (h *Hub) Gate() *Gate { return h.gate }

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
	// Two-step check, before any socket exists:
	//   1) the caller already had to guess the unguessable quick-tunnel URL
	//   2) Authorization: Bearer <D1 token>, verified against the Worker
	// Missing header → 401 (not counted). Wrong token → 401 and counted, five
	// failures lock the client for 30s, then 60/120/240/300s. Verifier outage
	// → 503, never counted. A socket is only created when the gate allows it.
	var token string
	if h.gate != nil {
		d := h.gate.Check(r)
		if !d.Allowed {
			h.gate.Write(w, d)
			return
		}
		token = d.Token
		AdoptPhoneToken(token) // memory only: this is the daemon's D1 credential
	}

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
			// Auth already happened at the handshake; the token is in memory.
			ack, _ := json.Marshal(MobileOut{Kind: "ack", Role: "system", SessionID: session, Stage: "resumed"})
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
