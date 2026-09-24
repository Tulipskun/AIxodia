package mockdb

import (
	"encoding/json"
	"io"
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

func TestSessionRenameAndDelete(t *testing.T) {
	s := New("d1-token", filepath.Join(t.TempDir(), "db.json"))
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	do := func(method, path, body string) (int, string) {
		var reader io.Reader
		if body != "" {
			reader = strings.NewReader(body)
		}
		req, _ := http.NewRequest(method, srv.URL+path, reader)
		req.Header.Set("Authorization", "Bearer d1-token")
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(raw)
	}

	if code, _ := do(http.MethodPost, "/api/sessions", `{"id":"c1","title":"แชทแรก"}`); code != 200 {
		t.Fatalf("create status = %d", code)
	}
	if code, _ := do(http.MethodPost, "/api/sessions/c1/turns", `{"role":"user","text":"hi"}`); code != 200 {
		t.Fatalf("turn status = %d", code)
	}
	if code, _ := do(http.MethodPatch, "/api/sessions/c1", `{"title":"เปลี่ยนชื่อแล้ว"}`); code != 200 {
		t.Fatalf("rename status = %d", code)
	}
	if s.ListSessions()[0].Title != "เปลี่ยนชื่อแล้ว" {
		t.Fatalf("title = %q", s.ListSessions()[0].Title)
	}
	if code, _ := do(http.MethodPatch, "/api/sessions/missing", `{"title":"x"}`); code != 404 {
		t.Fatalf("rename missing status = %d, want 404", code)
	}
	if code, _ := do(http.MethodPatch, "/api/sessions/c1", `{"title":"  "}`); code != 400 {
		t.Fatalf("empty title status = %d, want 400", code)
	}
	if code, _ := do(http.MethodDelete, "/api/sessions/c1", ""); code != 200 {
		t.Fatalf("delete status = %d", code)
	}
	if len(s.ListSessions()) != 0 {
		t.Fatalf("session survived delete: %+v", s.ListSessions())
	}
	if turns := s.Turns("c1", 0, 10); len(turns) != 0 {
		t.Fatalf("turns survived delete: %+v", turns)
	}
	if code, _ := do(http.MethodDelete, "/api/sessions/c1", ""); code != 404 {
		t.Fatalf("second delete status = %d, want 404", code)
	}
}
