package bridge

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Verifier answers one question: is this D1 token good? The daemon has no
// secret of its own, so the token a phone presents is checked against the
// Worker (see StateClient.VerifyToken), which is the single source of truth.
type Verifier interface {
	VerifyToken(ctx context.Context, token string) error
}

// GateConfig tunes the two-step connection check described in AX-070/071:
//
//	step 1 — the quick-tunnel hostname (unguessable, no code needed)
//	step 2 — Authorization: Bearer <D1 token> on the WebSocket handshake
//
// A missing header is rejected immediately without counting. A wrong token is
// counted; five failures lock the client out for 30s, and every further
// failure doubles the penalty up to a 5 minute cap. A successful connection
// resets the counter. Verifier/transport errors fail closed (503) and are
// never counted as failed logins.
type GateConfig struct {
	Verify       Verifier
	MaxFails     int
	BasePenalty  time.Duration
	MaxPenalty   time.Duration
	RequireHdr   string // default "Authorization"
	RequireValue string // default "Bearer"
}

func (c GateConfig) withDefaults() GateConfig {
	if c.Verify == nil {
		panic("bridge: GateConfig.Verify is required")
	}
	if c.MaxFails <= 0 {
		c.MaxFails = 5
	}
	if c.BasePenalty <= 0 {
		c.BasePenalty = 30 * time.Second
	}
	if c.MaxPenalty <= 0 {
		c.MaxPenalty = 5 * time.Minute
	}
	if c.RequireHdr == "" {
		c.RequireHdr = "Authorization"
	}
	if c.RequireValue == "" {
		c.RequireValue = "Bearer"
	}
	return c
}

// Gate enforces the handshake check and the progressive lockout. It holds no
// credentials: only counters and penalty deadlines, all in memory.
type Gate struct {
	cfg GateConfig

	mu            sync.Mutex
	fails         map[string]int
	penalties     map[string]time.Time
	globalFails   int
	globalPenalty time.Time
}

// NewGate builds a gate with the standard schedule (5 fails → 30s → 60 → 120
// → 240 → 300s cap).
func NewGate(cfg GateConfig) *Gate {
	return &Gate{
		cfg:       cfg.withDefaults(),
		fails:     map[string]int{},
		penalties: map[string]time.Time{},
	}
}

// Decision is the outcome of one handshake attempt.
type Decision struct {
	Allowed    bool
	Status     int    // 200 ok, 401 missing/bad token, 429 locked out, 503 verifier down
	Token      string // populated only when Allowed
	RetryAfter time.Duration
	Reason     string
	Fails      int
}

// Check inspects one HTTP request. Call it BEFORE upgrading to WebSocket so a
// rejection never becomes a socket.
func (g *Gate) Check(r *http.Request) Decision {
	// Step 2a: the header must be present and well formed. Not counted: a
	// scanner probing the URL is not a login attempt.
	hdr := r.Header.Get(g.cfg.RequireHdr)
	if hdr == "" {
		return Decision{Status: http.StatusUnauthorized, Reason: "missing " + g.cfg.RequireHdr + " header"}
	}
	parts := strings.SplitN(hdr, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], g.cfg.RequireValue) || strings.TrimSpace(parts[1]) == "" {
		return Decision{Status: http.StatusUnauthorized, Reason: "malformed " + g.cfg.RequireHdr + " header"}
	}
	token := strings.TrimSpace(parts[1])
	key := gateKey(r)

	if wait := g.lockedUntil(key); wait > 0 {
		return Decision{Status: http.StatusTooManyRequests, RetryAfter: wait,
			Reason: "too many failed token attempts"}
	}

	err := g.cfg.Verify.VerifyToken(r.Context(), token)
	switch {
	case err == nil:
		g.success(key)
		return Decision{Allowed: true, Status: http.StatusOK, Token: token, Reason: "ok"}
	case errors.Is(err, ErrTokenRejected):
		fails := g.failure(key)
		if fails >= g.cfg.MaxFails {
			wait := g.penaltyFor(fails)
			return Decision{Status: http.StatusTooManyRequests, RetryAfter: wait, Fails: fails,
				Reason: "token ผิดเกินลิมิต — ลองอีกครั้งหลังหมดเวลาล็อก"}
		}
		return Decision{Status: http.StatusUnauthorized, Fails: fails, Reason: "token ไม่ผ่าน"}
	default:
		// Fail closed: we cannot confirm the token, so we must not let anyone in,
		// but a Worker outage must not look like a wrong password.
		return Decision{Status: http.StatusServiceUnavailable, Reason: "ตรวจ token ไม่ได้ (ข้อมูลชั่วคราว): " + err.Error()}
	}
}

// Write renders a rejection. Kept separate so both transports answer the same
// way and tests can assert on it.
func (g *Gate) Write(w http.ResponseWriter, d Decision) {
	if d.RetryAfter > 0 {
		secs := int(d.RetryAfter.Seconds())
		if secs < 1 {
			secs = 1
		}
		w.Header().Set("Retry-After", itoa(secs))
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(d.Status)
	_, _ = w.Write([]byte(`{"error":` + jsonString(d.Reason) + `,"status":` + itoa(d.Status) + `}`))
}

func (g *Gate) lockedUntil(key string) time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now()
	var wait time.Duration
	if until, ok := g.penalties[key]; ok && until.After(now) {
		wait = time.Until(until)
	}
	if until := g.globalPenalty; until.After(now) && until.Sub(now) > wait {
		wait = time.Until(until)
	}
	return wait
}

func (g *Gate) failure(key string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.fails[key]++
	g.globalFails++
	n := g.fails[key]
	if n >= g.cfg.MaxFails {
		until := time.Now().Add(g.penaltyFor(n))
		g.penalties[key] = until
		// A flood from many keys still gets a floor: block everyone briefly.
		if g.globalFails >= g.cfg.MaxFails*3 {
			g.globalPenalty = time.Now().Add(g.cfg.BasePenalty)
			g.globalFails = 0
		}
	}
	return n
}

func (g *Gate) success(key string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.fails, key)
	delete(g.penalties, key)
	g.globalFails = 0
	g.globalPenalty = time.Time{}
}

// penaltyFor implements 30 → 60 → 120 → 240 → 300 (capped at MaxPenalty).
func (g *Gate) penaltyFor(fails int) time.Duration {
	steps := fails - g.cfg.MaxFails
	if steps < 0 {
		steps = 0
	}
	d := g.cfg.BasePenalty
	for i := 0; i < steps && d < g.cfg.MaxPenalty; i++ {
		d *= 2
	}
	if d > g.cfg.MaxPenalty {
		d = g.cfg.MaxPenalty
	}
	return d
}

// gateKey identifies the client by its real network address. Behind a quick
// tunnel RemoteAddr is always the local cloudflared process, so the
// Cloudflare-provided address is what makes per-client lockout work.
//
// The key deliberately excludes the token: counting per (address, token) would
// let an attacker dodge the lockout by varying the credential, which is
// exactly what the 5-failure rule is meant to stop.
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
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

func jsonString(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 {
				b.WriteString(" ")
				continue
			}
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}
