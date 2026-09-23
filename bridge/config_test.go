package bridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadConfigDefaultsWhenMissing(t *testing.T) {
	cfg, err := LoadConfig(filepath.Join(t.TempDir(), "nope.json"))
	if err != nil {
		t.Fatalf("missing config should not error: %v", err)
	}
	if cfg.MobileWS.Listen != "127.0.0.1:18789" || cfg.NodeTokenEnv != "AIXODIA_NODE_TOKEN" {
		t.Fatalf("defaults not applied: %+v", cfg)
	}
}

func TestLoadConfigReadsRuntimeValues(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "aixodia.json")
	body := `{"worker_base":"https://example.workers.dev","node_token_env":"TOK_ENV","mobile_ws":{"listen":"0.0.0.0:19999","tunnel":false}}`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.WorkerBase != "https://example.workers.dev" || cfg.MobileWS.Listen != "0.0.0.0:19999" {
		t.Fatalf("config not read: %+v", cfg)
	}
	t.Setenv("TOK_ENV", "secret-from-env")
	if got := cfg.NodeToken(); got != "secret-from-env" {
		t.Fatalf("NodeToken() = %q", got)
	}
}
