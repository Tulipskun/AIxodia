// Command mockai runs the full AIxodia mock stack on one machine:
//
//	mockai                      # DB :8787, agent WS :18789, LAN
//	mockai -tunnel              # + real Cloudflare quick tunnel (announces URL to DB)
//
// It is a stand-in for Tulipskun/ai until that daemon speaks the mobile
// transport: same WebSocket protocol, same REST contract, background jobs
// that keep running when the phone disconnects.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Tulipskun/AIxodia/mock/agent"
	"github.com/Tulipskun/AIxodia/mock/mockdb"
)

func main() {
	addr := flag.String("db", "127.0.0.1:8787", "mock DB/Worker HTTP listen address")
	wsAddr := flag.String("ws", "127.0.0.1:18789", "agent WebSocket listen address (0.0.0.0 for LAN)")
	token := flag.String("token", os.Getenv("AIXODIA_TOKEN"), "bearer token; empty = open dev mode")
	data := flag.String("data", "mockai-data.json", "JSON file that acts as the database")
	version := flag.String("version", "mock-1", "version reported by /api/node")
	tunnel := flag.Bool("tunnel", false, "open a real Cloudflare quick tunnel")
	cloudflared := flag.String("cloudflared", "cloudflared", "cloudflared binary (or pass /root/cloudflared.so)")
	step := flag.Duration("step", 350*time.Millisecond, "delay between mock agent steps")
	flag.Parse()

	db := mockdb.New(*token, *data)
	dbSrv := &http.Server{Addr: *addr, Handler: db.Handler()}
	ag := agent.New(agent.Config{
		DB:        db,
		Token:     *token,
		DBBase:    "http://" + *addr,
		Version:   *version,
		StepDelay: *step,
		TunnelBin: *cloudflared,
	})
	agSrv := &http.Server{Addr: *wsAddr, Handler: ag.Handler()}

	go func() {
		log.Printf("mockdb  listening on http://%s (data=%s token=%s)", *addr, *data, tokenLabel(*token))
		if err := dbSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("mockdb: %v", err)
		}
	}()
	go func() {
		log.Printf("agent   listening on ws://%s/ws", *wsAddr)
		if err := agSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("agent: %v", err)
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if *tunnel {
		go func() {
			port := 18789
			if i := lastColon(*wsAddr); i >= 0 {
				if _, err := fmt.Sscanf((*wsAddr)[i+1:], "%d", &port); err != nil {
					log.Printf("tunnel: cannot parse port from %s: %v", *wsAddr, err)
					return
				}
			}
			public, closer, err := ag.RunQuickTunnel(ctx, port)
			if err != nil {
				log.Printf("tunnel: %v (LAN mode still works)", err)
				return
			}
			log.Printf("tunnel  public URL: %s (announced to /api/node)", public)
			defer closer()
			<-ctx.Done()
		}()
	}

	<-ctx.Done()
	log.Println("shutting down")
	shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = dbSrv.Shutdown(shutdown)
	_ = agSrv.Shutdown(shutdown)
}

func tokenLabel(t string) string {
	if t == "" {
		return "open (dev)"
	}
	return "required"
}

func lastColon(s string) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}
