package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fakeVerifier struct {
	calls   atomic.Int64
	down    atomic.Bool
	rejects atomic.Bool
}

func (f *fakeVerifier) VerifyToken(_ context.Context, token string) error {
	f.calls.Add(1)
	if f.down.Load() {
		return errors.New("worker unreachable")
	}
	if f.rejects.Load() || token != "good" {
		return ErrTokenRejected
	}
	return nil
}

func dialWS(t *testing.T, url, token string) (*http.Response, error) {
	t.Helper()
	h := http.Header{}
	if token != "" {
		h.Set("Authorization", "Bearer "+token)
	}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header = h
	// A raw GET with Upgrade headers lets us read the rejection status directly.
	req.Header.Set("Connection", "Upgrade")
	req.Header.Set("Upgrade", "websocket")
	req.Header.Set("Sec-WebSocket-Version", "13")
	req.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	return resp, nil
}

func rejectAll() *fakeVerifier {
	v := &fakeVerifier{}
	v.rejects.Store(true)
	return v
}

func newHubServer(t *testing.T, v Verifier) *httptest.Server {
	t.Helper()
	g := NewGate(GateConfig{Verify: v})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d := g.Check(r); !d.Allowed {
			g.Write(w, d)
			return
		}
		w.WriteHeader(http.StatusSwitchingProtocols)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestMissingHeaderIsRejectedWithoutCounting(t *testing.T) {
	v := &fakeVerifier{}
	srv := newHubServer(t, v)

	resp, err := dialWS(t, srv.URL, "")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", resp.StatusCode)
	}
	if v.calls.Load() != 0 {
		t.Fatalf("verifier called %d times; a missing header must not count as a login", v.calls.Load())
	}
}

func TestMalformedHeaderIsRejected(t *testing.T) {
	v := &fakeVerifier{}
	srv := newHubServer(t, v)
	req, _ := http.NewRequest(http.MethodGet, srv.URL, nil)
	req.Header.Set("Authorization", "Token abc")
	req.Header.Set("Connection", "Upgrade")
	req.Header.Set("Upgrade", "websocket")
	req.Header.Set("Sec-WebSocket-Version", "13")
	req.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", resp.StatusCode)
	}
}

func TestWrongTokenLocksOutAndEscalates(t *testing.T) {
	v := rejectAll()
	g := NewGate(GateConfig{Verify: v})
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d := g.Check(r); !d.Allowed {
			g.Write(w, d)
			return
		}
		w.WriteHeader(http.StatusSwitchingProtocols)
	})
	srv := httptest.NewServer(handler)
	defer srv.Close()

	// Failures 1..4 answer 401 (no lockout yet).
	for i := 1; i <= 4; i++ {
		d := g.Check(fakeReq("1.2.3.4", "bad"))
		if d.Status != http.StatusUnauthorized {
			t.Fatalf("attempt %d: status = %d, want 401", i, d.Status)
		}
		if d.RetryAfter != 0 {
			t.Fatalf("attempt %d: locked out too early (%s)", i, d.RetryAfter)
		}
	}

	// Fifth failure locks the client for 30s.
	d5 := g.Check(fakeReq("1.2.3.4", "bad"))
	if d5.Status != http.StatusTooManyRequests || d5.RetryAfter < 29*time.Second || d5.RetryAfter > 31*time.Second {
		t.Fatalf("5th failure: status=%d retry=%s, want 429 + ~30s", d5.Status, d5.RetryAfter)
	}

	// While locked, even the correct token is refused...
	if d := g.Check(fakeReq("1.2.3.4", "good")); d.Status != http.StatusTooManyRequests {
		t.Fatalf("during lockout: status = %d, want 429", d.Status)
	}
	// ...and so is a DIFFERENT token, otherwise the lockout could be dodged by
	// rotating credentials.
	if d := g.Check(fakeReq("1.2.3.4", "another-bad-token")); d.Status != http.StatusTooManyRequests {
		t.Fatalf("lockout dodged by changing token: status = %d", d.Status)
	}

	// Escalation: further failures double up to the 5 minute cap.
	want := []time.Duration{30 * time.Second, 60 * time.Second, 120 * time.Second, 240 * time.Second, 300 * time.Second, 300 * time.Second}
	for i, w := range want {
		got := g.penaltyFor(5 + i)
		if got != w {
			t.Fatalf("penalty after %d failures = %s, want %s", 6+i, got, w)
		}
	}

	// Another client is unaffected: lockout is per address+token.
	if d := g.Check(fakeReq("9.9.9.9", "bad")); d.Status != http.StatusUnauthorized {
		t.Fatalf("other client status = %d, want 401", d.Status)
	}

	// A success clears the counters.
	g.success(gateKey(fakeReq("1.2.3.4", "bad")))
	if d := g.Check(fakeReq("1.2.3.4", "bad")); d.Status != http.StatusUnauthorized {
		t.Fatalf("after success: status = %d, want 401 (counter reset)", d.Status)
	}
}

func TestCorrectTokenPassesAndCarriesNoSecret(t *testing.T) {
	v := &fakeVerifier{}
	g := NewGate(GateConfig{Verify: v})
	req := fakeReq("1.2.3.4", "good")
	d := g.Check(req)
	if !d.Allowed || d.Status != http.StatusOK || d.Token != "good" {
		t.Fatalf("decision = %+v, want allowed", d)
	}
}

func TestVerifierOutageFailsClosedAndIsNotCounted(t *testing.T) {
	v := &fakeVerifier{}
	v.down.Store(true)
	g := NewGate(GateConfig{Verify: v})
	for i := 0; i < 8; i++ {
		d := g.Check(fakeReq("1.2.3.4", "good"))
		if d.Status != http.StatusServiceUnavailable {
			t.Fatalf("attempt %d: status = %d, want 503", i, d.Status)
		}
	}
	v.down.Store(false)
	if d := g.Check(fakeReq("1.2.3.4", "good")); !d.Allowed {
		t.Fatalf("after recovery: %+v, want allowed (outages must not lock the client out)", d)
	}
}

func TestGateNeverEchoesTheToken(t *testing.T) {
	v := rejectAll()
	g := NewGate(GateConfig{Verify: v})
	rec := httptest.NewRecorder()
	g.Write(rec, g.Check(fakeReq("1.2.3.4", "super-secret-value")))
	body := rec.Body.String()
	if strings.Contains(body, "super-secret-value") {
		t.Fatalf("token leaked into response: %s", body)
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(body), &parsed); err != nil {
		t.Fatalf("body not json: %s", body)
	}
}

func fakeReq(ip, token string) *http.Request {
	r := httptest.NewRequest(http.MethodGet, "http://x/ws", nil)
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("CF-Connecting-IP", ip)
	return r
}
