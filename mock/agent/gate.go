package agent

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// gate implements the production handshake rule inside the mock agent so both
// behave identically in tests and local runs:
//
//	missing Authorization header → 401, not counted
//	wrong token                  → 401, counted
//	5 failures                   → 429 for 30s, then 60/120/240/300s (capped)
//	verifier unreachable         → 503, not counted (fail closed)
//	success                      → counters reset
type gate struct {
	verify func(context.Context, string) error

	mu        sync.Mutex
	fails     map[string]int
	penalties map[string]time.Time
	gFails    int
	gPenalty  time.Time
}

const (
	gateMaxFails    = 5
	gateBasePenalty = 30 * time.Second
	gateMaxPenalty  = 5 * time.Minute
)

func newGate() *gate { return &gate{fails: map[string]int{}, penalties: map[string]time.Time{}} }

type decision struct {
	Allowed    bool
	Status     int
	Token      string
	RetryAfter time.Duration
	Reason     string
}

func (g *gate) Check(r *http.Request) decision {
	hdr := r.Header.Get("Authorization")
	if hdr == "" {
		return decision{Status: http.StatusUnauthorized, Reason: "missing Authorization header"}
	}
	parts := strings.SplitN(hdr, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") || strings.TrimSpace(parts[1]) == "" {
		return decision{Status: http.StatusUnauthorized, Reason: "malformed Authorization header"}
	}
	token := strings.TrimSpace(parts[1])
	key := gateKey(r)
	if wait := g.lockedUntil(key); wait > 0 {
		return decision{Status: http.StatusTooManyRequests, RetryAfter: wait, Reason: "locked out"}
	}
	err := g.verify(r.Context(), token)
	switch {
	case err == nil:
		g.ok(key)
		return decision{Allowed: true, Status: http.StatusOK, Token: token, Reason: "ok"}
	case errors.Is(err, ErrTokenRejected):
		n := g.fail(key)
		if n >= gateMaxFails {
			return decision{Status: http.StatusTooManyRequests, RetryAfter: g.penalty(n), Reason: "too many failed attempts"}
		}
		return decision{Status: http.StatusUnauthorized, Reason: "token ไม่ผ่าน"}
	default:
		return decision{Status: http.StatusServiceUnavailable, Reason: "verify failed: " + err.Error()}
	}
}

func (g *gate) Write(w http.ResponseWriter, d decision) {
	if d.RetryAfter > 0 {
		secs := int(d.RetryAfter.Seconds())
		if secs < 1 {
			secs = 1
		}
		w.Header().Set("Retry-After", itoa(secs))
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(d.Status)
	_, _ = w.Write([]byte(`{"error":"` + d.Reason + `","status":` + itoa(d.Status) + `}`))
}

func (g *gate) lockedUntil(key string) time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now()
	var wait time.Duration
	if until, ok := g.penalties[key]; ok && until.After(now) {
		wait = time.Until(until)
	}
	if until := g.gPenalty; until.After(now) && time.Until(until) > wait {
		wait = time.Until(until)
	}
	return wait
}

func (g *gate) fail(key string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.fails[key]++
	g.gFails++
	n := g.fails[key]
	if n >= gateMaxFails {
		g.penalties[key] = time.Now().Add(g.penalty(n))
		if g.gFails >= gateMaxFails*3 {
			g.gPenalty = time.Now().Add(gateBasePenalty)
			g.gFails = 0
		}
	}
	return n
}

func (g *gate) ok(key string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.fails, key)
	delete(g.penalties, key)
	g.gFails = 0
	g.gPenalty = time.Time{}
}

func (g *gate) penalty(n int) time.Duration {
	steps := n - gateMaxFails
	if steps < 0 {
		steps = 0
	}
	d := gateBasePenalty
	for i := 0; i < steps && d < gateMaxPenalty; i++ {
		d *= 2
	}
	if d > gateMaxPenalty {
		d = gateMaxPenalty
	}
	return d
}

// gateKey is the client address only: a lockout keyed on the token too could be
// dodged by rotating credentials.
func gateKey(r *http.Request) string {
	ip := strings.TrimSpace(r.Header.Get("CF-Connecting-IP"))
	if ip == "" {
		ip = strings.TrimSpace(r.Header.Get("X-Forwarded-For"))
		if i := strings.IndexByte(ip, ','); i >= 0 {
			ip = strings.TrimSpace(ip[:i])
		}
	}
	if ip == "" {
		ip, _, _ = net.SplitHostPort(r.RemoteAddr)
	}
	return ip
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
