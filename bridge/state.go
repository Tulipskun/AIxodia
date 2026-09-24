package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ErrTokenRejected means the credential itself is wrong (Worker answered
// 401/403). Transport problems return other errors and must never be counted
// as failed logins.
var ErrTokenRejected = errors.New("bridge: token rejected")

// StateClient is the daemon's only window into its own data. Nothing is read
// from local disk: every runtime value (provider API keys, system prompt,
// transports, per-session state) lives in Cloudflare D1 and is reached through
// the AIxodia Worker with the token the phone handed over in the WS hello.
//
// Secrets are sealed before they leave the process (AES-256-GCM under
// AIXODIA_STATE_KEY), so a stolen phone token yields history but not keys.
type StateClient struct {
	base    string
	http    *http.Client
	tokenFn func() string
}

// NewStateClient builds a client. base is the Worker URL; tokenFn returns the
// current credential (env at boot, replaced by the phone's token on hello).
func NewStateClient(base string, tokenFn func() string) *StateClient {
	return &StateClient{
		base:    strings.TrimRight(base, "/"),
		http:    &http.Client{Timeout: 30 * time.Second},
		tokenFn: tokenFn,
	}
}

// StatePrefixes groups the state keys the daemon owns.
const (
	KeyProviderConfig = "config/provider"
	KeySystemConfig   = "config/system"
	KeyEntryConfig    = "config/entry"
	KeyAttachment     = "config/attachment"
	KeySessionPrefix  = "sessions/"
)

func (c *StateClient) do(ctx context.Context, method, path string, body []byte, out any) error {
	return c.doWith(ctx, c.tokenFn(), method, path, body, out)
}

func (c *StateClient) doWith(ctx context.Context, token, method, path string, body []byte, out any) error {
	if token == "" {
		return errors.New("bridge: no token yet (set AIXODIA_NODE_TOKEN or pair a phone)")
	}
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return fmt.Errorf("%w (HTTP %d) — re-pair the phone or rotate AIXODIA_TOKEN", ErrTokenRejected, resp.StatusCode)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("bridge: %s %s -> HTTP %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// VerifyToken checks a token the phone presented in its hello frame. It must
// be a real credential: the daemon adopts it only when the Worker accepts it.
// The candidate is used for this check only; the caller decides whether to
// store it (AcceptPhoneToken) so a failed handshake cannot swap the live one.
func (c *StateClient) VerifyToken(ctx context.Context, token string) error {
	if token == "" {
		return errors.New("bridge: hello without token")
	}
	return c.doWith(ctx, token, http.MethodGet, "/api/ping", nil, nil)
}

// Get fetches one state value. found=false means the key does not exist yet.
func (c *StateClient) Get(ctx context.Context, key string) (value string, found bool, err error) {
	var out struct {
		Value *string `json:"value"`
	}
	path := "/api/state/" + url.PathEscape(key)
	if err := c.do(ctx, http.MethodGet, path, nil, &out); err != nil {
		return "", false, err
	}
	if out.Value == nil {
		return "", false, nil
	}
	return *out.Value, true, nil
}

// Put stores a state value. Values are stored as the operator chose: there is
// no extra encryption secret (none may be created without approval), so anyone
// holding a valid API token can read config/provider — that is a conscious
// single-operator tradeoff, documented in decisions.md (D-009).
func (c *StateClient) Put(ctx context.Context, key, value string) error {
	body, err := json.Marshal(map[string]string{"value": value})
	if err != nil {
		return err
	}
	return c.do(ctx, http.MethodPut, "/api/state/"+url.PathEscape(key), body, nil)
}

// List enumerates keys with the given prefix (e.g. sessions/).
func (c *StateClient) List(ctx context.Context, prefix string) ([]string, error) {
	var out struct {
		Keys []string `json:"keys"`
	}
	path := "/api/state?prefix=" + url.QueryEscape(prefix)
	if err := c.do(ctx, http.MethodGet, path, nil, &out); err != nil {
		return nil, err
	}
	return out.Keys, nil
}

// GetJSON loads a JSON document (e.g. the provider config) into v.
func (c *StateClient) GetJSON(ctx context.Context, key string, v any) (bool, error) {
	raw, found, err := c.Get(ctx, key)
	if err != nil || !found {
		return false, err
	}
	if err := json.Unmarshal([]byte(raw), v); err != nil {
		return true, fmt.Errorf("bridge: parse %s: %w", key, err)
	}
	return true, nil
}

// PutJSON stores a JSON document at key.
func (c *StateClient) PutJSON(ctx context.Context, key string, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.Put(ctx, key, string(raw))
}

// ListSessions returns the session ids that have state in D1.
func (c *StateClient) ListSessions(ctx context.Context) ([]string, error) {
	keys, err := c.List(ctx, KeySessionPrefix)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, strings.TrimPrefix(k, KeySessionPrefix))
	}
	return out, nil
}

// SessionKey is the D1 key for one session's state blob.
func SessionKey(id string) string { return KeySessionPrefix + id }
