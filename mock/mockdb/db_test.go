package mockdb

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestPingRequiresTokenAndAnswers(t *testing.T) {
	s := New("d1-token", filepath.Join(t.TempDir(), "db.json"))
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/api/ping", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("without token: status = %d, want 401", resp.StatusCode)
	}

	req, _ = http.NewRequest("GET", srv.URL+"/api/ping", nil)
	req.Header.Set("Authorization", "Bearer d1-token")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("with token: status = %d, want 200", resp.StatusCode)
	}
}

func TestStatePrefixListing(t *testing.T) {
	s := New("d1-token", filepath.Join(t.TempDir(), "db.json"))
	s.PutState("config/provider", `{"providers":[]}`)
	s.PutState("sessions/abc", `{"turns":3}`)
	s.PutState("sessions/xyz", `{"turns":1}`)
	s.PutState("jobs/j1", `{}`)

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/api/state?prefix=sessions%2F", nil)
	req.Header.Set("Authorization", "Bearer d1-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var out struct {
		Keys []string `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if len(out.Keys) != 2 || out.Keys[0] != "sessions/abc" || out.Keys[1] != "sessions/xyz" {
		t.Fatalf("keys = %v, want the two session keys in order", out.Keys)
	}

	req, _ = http.NewRequest("GET", srv.URL+"/api/state?prefix="+strings.Repeat("a", 200), nil)
	req.Header.Set("Authorization", "Bearer d1-token")
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusBadRequest {
		t.Fatalf("long prefix: status = %d, want 400", resp2.StatusCode)
	}
}
