package agent

import (
	"io"
	"net/http"
	"strings"
	"time"
)

// historyProxy mirrors the production transport (transport/mobile in the ai
// repo): when the daemon is reached through its quick tunnel, the same address
// also serves the small REST surface the app needs. Requests carry the D1
// token in the Authorization header and are forwarded to the Worker unchanged.
//
// The allowlist is deliberately small — /api/state (which holds provider API
// keys) is never reachable through the tunnel.
var allowedProxyPaths = map[string]bool{
	"/api/sessions": true,
	"/api/node":     true,
	"/api/ping":     true,
}

// allowedProxySessionOps matches /api/sessions/<id> for rename (PATCH) and
// delete (DELETE) — the two actions a chat list needs.
func allowedProxySessionOps(method, path string) bool {
	if method != http.MethodPatch && method != http.MethodDelete {
		return false
	}
	rest := strings.TrimPrefix(path, "/api/sessions/")
	if rest == path {
		return false
	}
	return rest != "" && !strings.Contains(rest, "/")
}

func allowedProxyTurns(path string) bool {
	rest := strings.TrimPrefix(path, "/api/sessions/")
	if rest == path {
		return false
	}
	parts := strings.Split(rest, "/")
	return len(parts) == 2 && parts[1] == "turns" && parts[0] != ""
}

func (a *Agent) historyProxy() http.Handler {
	base := strings.TrimRight(a.cfg.MirrorBase, "/")
	client := &http.Client{Timeout: 60 * time.Second}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if base == "" {
			http.Error(w, "history proxy is not configured", http.StatusNotImplemented)
			return
		}
		if r.Header.Get("Authorization") == "" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"missing Authorization header"}`))
			return
		}
		if !allowedProxyPaths[r.URL.Path] && !allowedProxyTurns(r.URL.Path) &&
			!allowedProxySessionOps(r.Method, r.URL.Path) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"not proxied"}`))
			return
		}
		forward, err := http.NewRequestWithContext(r.Context(), r.Method, base+r.URL.Path, r.Body)
		if err != nil {
			http.Error(w, "proxy: bad request", http.StatusBadGateway)
			return
		}
		for _, header := range []string{"Authorization", "Content-Type"} {
			if value := r.Header.Get(header); value != "" {
				forward.Header.Set(header, value)
			}
		}
		forward.Header.Set("User-Agent", "ai")
		if q := r.URL.RawQuery; q != "" {
			forward.URL.RawQuery = q
		}
		resp, err := client.Do(forward)
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadGateway)
			_, _ = w.Write([]byte(`{"error":"worker unreachable"}`))
			return
		}
		defer resp.Body.Close()
		for _, header := range []string{"Content-Type", "Retry-After"} {
			if value := resp.Header.Get(header); value != "" {
				w.Header().Set(header, value)
			}
		}
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
	})
}
