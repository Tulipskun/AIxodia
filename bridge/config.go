package bridge

import (
	"encoding/json"
	"fmt"
	"os"
)

// MobileWSConfig is the runtime configuration for the mobile transport. It is
// loaded from a JSON file (default config/aixodia.json, override with
// AIXODIA_CONFIG) so hosts, ports and state keys never live in code.
//
// Secrets are NOT stored in this file: the node token is read from the
// environment variable named by NodeTokenEnv (e.g. AIXODIA_NODE_TOKEN) or from
// the WS hello frame of a paired phone, and is kept in memory only.
type MobileWSConfig struct {
	WorkerBase   string `json:"worker_base"`
	NodeTokenEnv string `json:"node_token_env"`
	StateKeys    struct {
		ProviderConfig string `json:"provider_config"`
		SystemConfig   string `json:"system_config"`
		EntryConfig    string `json:"entry_config"`
		SessionPrefix  string `json:"session_prefix"`
	} `json:"state_keys"`
	MobileWS struct {
		Listen       string `json:"listen"`
		PublicListen string `json:"public_listen"`
		Tunnel       bool   `json:"tunnel"`
		Cloudflared  string `json:"cloudflared"`
	} `json:"mobile_ws"`
}

const DefaultConfigPath = "config/aixodia.json"

// LoadConfig reads the runtime config. Missing file → defaults, so a fresh
// checkout still runs with environment-only configuration.
func LoadConfig(path string) (MobileWSConfig, error) {
	if path == "" {
		path = os.Getenv("AIXODIA_CONFIG")
	}
	if path == "" {
		path = DefaultConfigPath
	}
	cfg := MobileWSConfig{NodeTokenEnv: "AIXODIA_NODE_TOKEN"}
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			cfg.applyDefaults()
			return cfg, nil
		}
		return cfg, err
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return cfg, fmt.Errorf("bridge: parse %s: %w", path, err)
	}
	cfg.applyDefaults()
	return cfg, nil
}

func (c *MobileWSConfig) applyDefaults() {
	if c.NodeTokenEnv == "" {
		c.NodeTokenEnv = "AIXODIA_NODE_TOKEN"
	}
	if c.MobileWS.Listen == "" {
		c.MobileWS.Listen = "127.0.0.1:18789"
	}
	if c.MobileWS.PublicListen == "" {
		c.MobileWS.PublicListen = "0.0.0.0:18789"
	}
	if c.MobileWS.Cloudflared == "" {
		c.MobileWS.Cloudflared = "cloudflared"
	}
	if c.StateKeys.ProviderConfig == "" {
		c.StateKeys.ProviderConfig = "config/provider"
	}
	if c.StateKeys.SystemConfig == "" {
		c.StateKeys.SystemConfig = "config/system"
	}
	if c.StateKeys.EntryConfig == "" {
		c.StateKeys.EntryConfig = "config/entry"
	}
	if c.StateKeys.SessionPrefix == "" {
		c.StateKeys.SessionPrefix = "sessions/"
	}
}

// NodeToken returns the token from the environment variable the config names.
// It is never written to disk by this package.
func (c MobileWSConfig) NodeToken() string {
	if c.NodeTokenEnv == "" {
		return ""
	}
	return os.Getenv(c.NodeTokenEnv)
}
