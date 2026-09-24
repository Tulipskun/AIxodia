// Command aiclient is a tiny terminal client for the mock/real agent, used to
// prove the protocol without a phone (CI and manual checks).
//
//	go run ./cmd/aiclient -url ws://127.0.0.1:18789/ws -session s1 -text "hello"
//	go run ./cmd/aiclient -url ws://127.0.0.1:18789/ws -session s1 -text "hello" -detach
//
// -detach closes the socket right after the ack: the job must still finish and
// land in the DB, exactly like closing the Android app mid-turn.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/gorilla/websocket"
)

type frame map[string]any

func main() {
	url := flag.String("url", "ws://127.0.0.1:18789/ws", "agent WebSocket URL")
	session := flag.String("session", "s1", "session id")
	text := flag.String("text", "hello", "message text")
	token := flag.String("token", os.Getenv("AIXODIA_TOKEN"), "bearer token")
	detach := flag.Bool("detach", false, "close right after ack (proves background jobs)")
	noAuth := flag.Bool("no-auth", false, "omit the Authorization header (must be rejected)")
	badToken := flag.Bool("bad-token", false, "send a wrong token (counts toward lockout)")
	wait := flag.Duration("wait", 30*time.Second, "how long to keep reading frames")
	flag.Parse()

	dialer := *websocket.DefaultDialer
	header := http.Header{}
	if !*noAuth {
		tok := *token
		if *badToken {
			tok = "wrong-" + tok
		}
		header.Set("Authorization", "Bearer "+tok)
	}
	c, resp, err := dialer.Dial(*url, header)
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		fmt.Fprintf(os.Stderr, "dial rejected: %v (HTTP %d)\n", err, status)
		os.Exit(1)
	}
	defer c.Close()

	write := func(v any) {
		if err := c.WriteJSON(v); err != nil {
			fmt.Fprintln(os.Stderr, "write:", err)
			os.Exit(1)
		}
	}
	write(map[string]any{
		"type": "hello", "session_id": *session,
		"content": []map[string]string{{"type": "text", "text": "resume:0"}},
	})

	clientMsgID := fmt.Sprintf("%s:%d", *session, time.Now().UnixNano())
	write(map[string]any{
		"type": "message", "source": "cli", "session_id": *session,
		"content":       []map[string]string{{"type": "text", "text": *text}},
		"client_msg_id": clientMsgID,
	})

	deadline := time.Now().Add(*wait)
	for time.Now().Before(deadline) {
		_ = c.SetReadDeadline(deadline)
		_, raw, err := c.ReadMessage()
		if err != nil {
			fmt.Fprintln(os.Stderr, "read:", err)
			return
		}
		var f frame
		if err := json.Unmarshal(raw, &f); err != nil {
			fmt.Println(string(raw))
			continue
		}
		raw2, _ := json.Marshal(f)
		fmt.Println(string(raw2))
		if f["kind"] == "error" {
			os.Exit(1)
		}
		if *detach && f["kind"] == "ack" && f["client_msg_id"] == clientMsgID {
			fmt.Println("detached: closing socket while the job keeps running")
			return
		}
		if f["kind"] == "done" {
			return
		}
	}
}
