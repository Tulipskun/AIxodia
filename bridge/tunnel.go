// Package bridge — quick-tunnel + stateless-state reference for Tulipskun/ai.
//
// Architecture (AXCH-004): the ai daemon exposes its mobile WebSocket only on
// localhost, then publishes it through a Cloudflare *quick tunnel*
// (https://<random>.trycloudflare.com, no account, no port forwarding).
// Because the URL is random on every boot, ai heartbeats it into D1 via the
// AIxodia Worker (POST /api/node/heartbeat); the phone discovers it with
// GET /api/node instead of hardcoding an IP.
//
// Stateless operation: ai keeps config/session JSON as opaque blobs in D1
// (`/api/state/:key`) and holds only the scoped token in memory. The phone
// sends that scoped Worker token inside the WS hello frame — never a raw
// Cloudflare API token (those must never leave Cloudflare / the operator).
//
// Wiring (ai repo): copy tunnel.go + mobile_ws.go into transport/mobile/,
// then on daemon boot:
//
//	hub := bridge.NewHub()
//	tok := bridge.TokenFromEnvOrMemory() // AIXODIA_NODE_TOKEN, or set by phone hello
//	stop, url, err := bridge.RunQuickTunnel(ctx, 18789, workerBase, tok, "v1.x")
//	// serve hub on 127.0.0.1:18789/ws ; on shutdown call stop()
package bridge

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync/atomic"
	"time"
)

var tryURL = regexp.MustCompile(`https://[A-Za-z0-9.-]+\.trycloudflare\.com`)

// memToken is the in-memory scoped Worker token. Set at boot from
// AIXODIA_NODE_TOKEN, or replaced when a phone hello arrives carrying the
// device token (see MobileIn.Token). Never persisted to disk (stateless).
var memToken atomic.Value // string

func init() { memToken.Store(os.Getenv("AIXODIA_NODE_TOKEN")) }

// TokenFromEnvOrMemory reports the current scoped token ("" = not paired yet).
func TokenFromEnvOrMemory() string {
	if v, ok := memToken.Load().(string); ok {
		return v
	}
	return ""
}

// AcceptPhoneToken stores the scoped token a paired phone sent in its hello
// frame. Call it from the mobile WS handler after verifying the hello.
// This is the "phone gives ai its DB-access secret" step: the secret is a
// revocable Worker-scoped token, valid only in memory until restart.
func AcceptPhoneToken(tok string) {
	if strings.TrimSpace(tok) != "" {
		memToken.Store(tok)
	}
}

// RunQuickTunnel starts `cloudflared tunnel --url http://127.0.0.1:<port>`,
// waits for the public URL, announces it to the Worker, and refreshes the
// announcement every 30s. The caller must already serve the mobile WS hub on
// that port (localhost only — the tunnel is the only public ingress).
// Returns the public base URL and a stop func.
func RunQuickTunnel(ctx context.Context, port int, workerBase, version string) (string, func(), error) {
	bin, err := exec.LookPath("cloudflared")
	if err != nil {
		return "", nil, fmt.Errorf("bridge: cloudflared not found in PATH (install from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)")
	}
	child, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(child, bin, "tunnel", "--no-autoupdate", "--url", fmt.Sprintf("http://127.0.0.1:%d", port))
	stderr, err := cmd.StderrPipe()
	if err != nil {
		cancel()
		return "", nil, err
	}
	cmd.Stdout = io.Discard
	if err := cmd.Start(); err != nil {
		cancel()
		return "", nil, err
	}

	urlCh := make(chan string, 1)
	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 64*1024), 64*1024)
		for sc.Scan() {
			if m := tryURL.FindString(sc.Text()); m != "" {
				select {
				case urlCh <- m:
				default:
				}
			}
		}
	}()

	var public string
	select {
	case public = <-urlCh:
	case <-time.After(45 * time.Second):
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		cancel()
		return "", nil, fmt.Errorf("bridge: timed out waiting for trycloudflare URL")
	case <-ctx.Done():
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		cancel()
		return "", nil, ctx.Err()
	}

	stopHB, hbErr := startHeartbeat(ctx, workerBase, public, version)
	if hbErr != nil {
		// Tunnel works; heartbeat failure just means undiscoverable until fixed.
		fmt.Fprintf(os.Stderr, "bridge: heartbeat: %v\n", hbErr)
	}
	stop := func() {
		if stopHB != nil {
			stopHB()
		}
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		cancel()
	}
	return public, stop, nil
}

func startHeartbeat(ctx context.Context, workerBase, public, version string) (func(), error) {
	if err := postHeartbeat(workerBase, public, version); err != nil {
		return nil, err
	}
	done := make(chan struct{})
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-ctx.Done():
				return
			case <-t.C:
				if err := postHeartbeat(workerBase, public, version); err != nil {
					fmt.Fprintf(os.Stderr, "bridge: heartbeat: %v\n", err)
				}
			}
		}
	}()
	return func() { close(done) }, nil
}

func postHeartbeat(workerBase, public, version string) error {
	tok := TokenFromEnvOrMemory()
	if tok == "" {
		return fmt.Errorf("no token yet (pair a phone or set AIXODIA_NODE_TOKEN)")
	}
	body, _ := json.Marshal(map[string]string{"tunnel_url": public, "version": version})
	req, _ := http.NewRequest("POST", strings.TrimRight(workerBase, "/")+"/api/node/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("heartbeat HTTP %d", resp.StatusCode)
	}
	return nil
}

// LoadState fetches an opaque JSON blob previously saved with SaveState.
// Use it at boot for config/provider, sessions/<id>, etc. so the daemon can
// run stateless (nothing under ~/.local/share/ai is required).
func LoadState(ctx context.Context, workerBase, key string) (string, error) {
	tok := TokenFromEnvOrMemory()
	if tok == "" {
		return "", fmt.Errorf("bridge: no token")
	}
	req, _ := http.NewRequestWithContext(ctx, "GET", strings.TrimRight(workerBase, "/")+"/api/state/"+key, nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Value *string `json:"value"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out.Value == nil {
		return "", fmt.Errorf("bridge: no state for %q", key)
	}
	return *out.Value, nil
}

// SaveState stores an opaque JSON blob (≤500KB) in D1 via the Worker.
func SaveState(ctx context.Context, workerBase, key, value string) error {
	tok := TokenFromEnvOrMemory()
	if tok == "" {
		return fmt.Errorf("bridge: no token")
	}
	body, _ := json.Marshal(map[string]string{"value": value})
	req, _ := http.NewRequestWithContext(ctx, "PUT", strings.TrimRight(workerBase, "/")+"/api/state/"+key, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("bridge: save state HTTP %d", resp.StatusCode)
	}
	return nil
}
