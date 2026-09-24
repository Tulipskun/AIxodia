// Package mockdb is a local stand-in for the Cloudflare Worker + D1 so the
// whole AIxodia stack can be tested end-to-end before any Cloudflare setup.
// It speaks the exact same REST contract as worker/src/index.ts and persists
// to a JSON file, so history survives restarts like a real database.
package mockdb

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Turn struct {
	Seq       int64  `json:"seq"`
	Role      string `json:"role"`
	Agent     string `json:"agent"`
	JobID     string `json:"job_id"`
	Text      string `json:"text"`
	CreatedAt int64  `json:"created_at"`
}

type Session struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Provider  string `json:"provider"`
	Model     string `json:"model"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

type Node struct {
	TunnelURL string `json:"tunnel_url"`
	Version   string `json:"version"`
	Heartbeat int64  `json:"heartbeat"`
}

type Device struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	CreatedAt int64  `json:"created_at"`
	Revoked   int    `json:"revoked"`
}

type snapshot struct {
	Sessions map[string]*Session `json:"sessions"`
	Turns    map[string][]Turn   `json:"turns"`
	State    map[string]string   `json:"state"`
	Node     Node                `json:"node"`
	Devices  map[string]*Device  `json:"devices"`
	seq      int64
}

type Store struct {
	mu    sync.Mutex
	token string
	path  string
	data  snapshot
}

func New(token, path string) *Store {
	s := &Store{token: token, path: path}
	s.data = snapshot{
		Sessions: map[string]*Session{},
		Turns:    map[string][]Turn{},
		State:    map[string]string{},
		Devices:  map[string]*Device{},
	}
	if path != "" {
		if raw, err := os.ReadFile(path); err == nil {
			_ = json.Unmarshal(raw, &s.data)
			if s.data.Sessions == nil {
				s.data.Sessions = map[string]*Session{}
			}
			if s.data.Turns == nil {
				s.data.Turns = map[string][]Turn{}
			}
			if s.data.State == nil {
				s.data.State = map[string]string{}
			}
			if s.data.Devices == nil {
				s.data.Devices = map[string]*Device{}
			}
		}
	}
	return s
}

func (s *Store) VerifyToken(tok string) bool {
	if s.token == "" {
		return true
	}
	return tok == s.token
}

func (s *Store) save() {
	if s.path == "" {
		return
	}
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(s.path), 0o755)
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err == nil {
		_ = os.Rename(tmp, s.path)
	}
}

func now() int64 { return time.Now().Unix() }

// ---- domain operations (used by the agent + HTTP layer) ----

func (s *Store) EnsureSession(id, title, model string) Session {
	s.mu.Lock()
	defer s.mu.Unlock()
	if id == "" {
		id = "s" + strconv.FormatInt(time.Now().UnixMilli(), 36)
	}
	if ses, ok := s.data.Sessions[id]; ok {
		return *ses
	}
	ses := &Session{ID: id, Title: title, Model: model, CreatedAt: now(), UpdatedAt: now()}
	if ses.Title == "" {
		ses.Title = id
	}
	s.data.Sessions[id] = ses
	s.save()
	return *ses
}

func (s *Store) SetTitle(id, title string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ses, ok := s.data.Sessions[id]; ok {
		ses.Title = title
		ses.UpdatedAt = now()
		s.save()
	}
}

func (s *Store) ListSessions() []Session {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Session, 0, len(s.data.Sessions))
	for _, ses := range s.data.Sessions {
		out = append(out, *ses)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out
}

func (s *Store) AppendTurn(sessionID string, t Turn) Turn {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.seq++
	t.Seq = s.data.seq
	if t.CreatedAt == 0 {
		t.CreatedAt = now()
	}
	s.data.Turns[sessionID] = append(s.data.Turns[sessionID], t)
	if ses, ok := s.data.Sessions[sessionID]; ok {
		ses.UpdatedAt = t.CreatedAt
	}
	s.save()
	return t
}

func (s *Store) Turns(sessionID string, before int64, limit int) []Turn {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.data.Turns[sessionID]
	out := make([]Turn, 0, limit)
	for i := len(all) - 1; i >= 0; i-- {
		if before > 0 && all[i].Seq >= before {
			continue
		}
		out = append(out, all[i])
		if len(out) >= limit {
			break
		}
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

func (s *Store) SetNode(tunnelURL, version string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Node = Node{TunnelURL: tunnelURL, Version: version, Heartbeat: now()}
	s.save()
}

func (s *Store) NodeInfo() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	age := int64(-1)
	if s.data.Node.Heartbeat > 0 {
		age = now() - s.data.Node.Heartbeat
	}
	return map[string]any{
		"tunnel_url":      s.data.Node.TunnelURL,
		"version":         s.data.Node.Version,
		"heartbeat_age_s": age,
		"online":          age >= 0 && age < 90,
	}
}

func (s *Store) PutState(key, value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.State[key] = value
	s.save()
}

func (s *Store) GetState(key string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.data.State[key]
	return v, ok
}

func (s *Store) RegisterDevice(id, label string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.data.Devices[id]; !ok {
		s.data.Devices[id] = &Device{ID: id, Label: label, CreatedAt: now()}
		s.save()
	}
}

func (s *Store) RevokeDevice(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d, ok := s.data.Devices[id]; ok {
		d.Revoked = 1
		s.save()
	}
}

func (s *Store) ListDevices() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, 0, len(s.data.Devices))
	for _, d := range s.data.Devices {
		out = append(out, *d)
	}
	return out
}

// ---- HTTP layer (parity with worker/src/index.ts) ----

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("content-type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func (s *Store) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "mock": true})
	})
	mux.HandleFunc("/api/", s.serveAPI)
	return mux
}

// prefixOK allows "/" so callers can list a namespace such as "sessions/".
func prefixOK(p string) bool {
	if len(p) > 128 {
		return false
	}
	for _, c := range p {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == ':' || c == '_' || c == '-' || c == '/' {
			continue
		}
		return false
	}
	return true
}

var keyOK = func(k string) bool {
	if len(k) == 0 || len(k) > 128 {
		return false
	}
	for _, c := range k {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == ':' || c == '_' || c == '-' {
			continue
		}
		return false
	}
	return true
}

func (s *Store) serveAPI(w http.ResponseWriter, r *http.Request) {
	auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !s.VerifyToken(auth) {
		writeJSON(w, 401, map[string]any{"error": "unauthorized"})
		return
	}
	u := r.URL
	p := u.Path

	if p == "/api/node" && r.Method == http.MethodGet {
		writeJSON(w, 200, s.NodeInfo())
		return
	}
	if p == "/api/node/heartbeat" && r.Method == http.MethodPost {
		var b struct {
			TunnelURL string `json:"tunnel_url"`
			Version   string `json:"version"`
		}
		if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
			writeJSON(w, 400, map[string]any{"error": "bad json"})
			return
		}
		if !validTunnelURL(b.TunnelURL) {
			writeJSON(w, 400, map[string]any{"error": "tunnel_url must be https://*.trycloudflare.com or localhost"})
			return
		}
		s.SetNode(b.TunnelURL, b.Version)
		writeJSON(w, 200, map[string]any{"ok": true})
		return
	}

	if p == "/api/ping" && r.Method == http.MethodGet {
		writeJSON(w, 200, map[string]any{"ok": true, "service": "aixodia", "mock": true})
		return
	}

	if p == "/api/state" && r.Method == http.MethodGet {
		prefix := u.Query().Get("prefix")
		if len(prefix) > 128 || !prefixOK(prefix) {
			writeJSON(w, 400, map[string]any{"error": "bad prefix"})
			return
		}
		s.mu.Lock()
		keys := make([]string, 0, len(s.data.State))
		for k := range s.data.State {
			if strings.HasPrefix(k, prefix) {
				keys = append(keys, k)
			}
		}
		s.mu.Unlock()
		sort.Strings(keys)
		if len(keys) > 200 {
			keys = keys[:200]
		}
		writeJSON(w, 200, map[string]any{"keys": keys})
		return
	}

	if strings.HasPrefix(p, "/api/state/") && (r.Method == http.MethodGet || r.Method == http.MethodPut) {
		key := strings.TrimPrefix(p, "/api/state/")
		if !keyOK(key) {
			writeJSON(w, 400, map[string]any{"error": "bad key"})
			return
		}
		if r.Method == http.MethodGet {
			v, ok := s.GetState(key)
			if !ok {
				writeJSON(w, 200, map[string]any{"key": key, "value": nil, "updated_at": 0})
				return
			}
			writeJSON(w, 200, map[string]any{"key": key, "value": v, "updated_at": now()})
			return
		}
		var b struct {
			Value json.RawMessage `json:"value"`
		}
		if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
			writeJSON(w, 400, map[string]any{"error": "bad json"})
			return
		}
		val := string(b.Value)
		if len(val) > 500_000 {
			writeJSON(w, 413, map[string]any{"error": "too large"})
			return
		}
		s.PutState(key, val)
		writeJSON(w, 200, map[string]any{"ok": true})
		return
	}

	if p == "/api/devices" && r.Method == http.MethodGet {
		writeJSON(w, 200, s.ListDevices())
		return
	}
	if p == "/api/devices" && r.Method == http.MethodPost {
		var b struct {
			ID    string `json:"id"`
			Label string `json:"label"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.ID == "" {
			writeJSON(w, 400, map[string]any{"error": "id required"})
			return
		}
		s.RegisterDevice(b.ID, b.Label)
		writeJSON(w, 200, map[string]any{"ok": true})
		return
	}
	if strings.HasSuffix(p, "/revoke") && r.Method == http.MethodPost {
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/api/devices/"), "/revoke")
		s.RevokeDevice(id)
		writeJSON(w, 200, map[string]any{"ok": true})
		return
	}

	if p == "/api/sessions" && r.Method == http.MethodGet {
		writeJSON(w, 200, s.ListSessions())
		return
	}
	if p == "/api/sessions" && r.Method == http.MethodPost {
		var b struct {
			ID    string `json:"id"`
			Title string `json:"title"`
			Model string `json:"model"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, s.EnsureSession(b.ID, b.Title, b.Model))
		return
	}

	rest := strings.TrimPrefix(p, "/api/sessions/")
	if rest != p {
		parts := strings.Split(rest, "/")
		if len(parts) == 2 && parts[1] == "turns" {
			sid, err := decodePath(parts[0])
			if err != nil {
				writeJSON(w, 400, map[string]any{"error": "bad session id"})
				return
			}
			if r.Method == http.MethodGet {
				before, _ := strconv.ParseInt(u.Query().Get("before_seq"), 10, 64)
				limit, _ := strconv.Atoi(u.Query().Get("limit"))
				if before == 0 {
					before = 1 << 62
				}
				if limit <= 0 || limit > 200 {
					limit = 50
				}
				turns := s.Turns(sid, before, limit)
				if turns == nil {
					turns = []Turn{}
				}
				writeJSON(w, 200, map[string]any{"turns": turns})
				return
			}
			if r.Method == http.MethodPost {
				var b struct {
					Role  string `json:"role"`
					Text  string `json:"text"`
					Agent string `json:"agent"`
					JobID string `json:"job_id"`
				}
				if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
					writeJSON(w, 400, map[string]any{"error": "bad json"})
					return
				}
				if b.Role == "" {
					b.Role = "model"
				}
				s.EnsureSession(sid, "", "")
				t := s.AppendTurn(sid, Turn{Role: b.Role, Text: b.Text, Agent: b.Agent, JobID: b.JobID})
				writeJSON(w, 200, map[string]any{"seq": t.Seq})
				return
			}
		}
	}
	writeJSON(w, 404, map[string]any{"error": "not_found"})
}

func decodePath(s string) (string, error) {
	return url.PathUnescape(s)
}

func validTunnelURL(u string) bool {
	if strings.HasPrefix(u, "https://") && strings.HasSuffix(u, ".trycloudflare.com") {
		return true
	}
	return strings.HasPrefix(u, "http://127.0.0.1") || strings.HasPrefix(u, "http://localhost")
}
