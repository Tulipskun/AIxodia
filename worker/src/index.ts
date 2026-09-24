// Cloudflare Worker REST for AIxodia: D1 history + quick-tunnel discovery +
// stateless-ai state + device registry. All /api/* need Bearer AIXODIA_TOKEN.
// (v1 single shared token; per-device enforcement is the documented next step.)
//
//   History:  GET  /api/sessions
//             PATCH  /api/sessions/:id  {title}   (rename, like a chat title)
//             DELETE /api/sessions/:id            (delete chat + its turns)
//             GET  /api/sessions/:id/turns?before_seq=&limit=
//             POST /api/sessions/:id/turns   {role, text}
//   Node:     POST /api/node/heartbeat  {tunnel_url, version}  (ai daemon)
//             GET  /api/node  -> {tunnel_url, version, online, heartbeat_age_s}
//   Ping:     GET  /api/ping  -> {ok:true}   (daemon verifies a phone token)
//   State:    GET  /api/state?prefix=        -> {keys:[...]}
//             GET  /api/state/:key  /  PUT /api/state/:key  {value}
//             (stateless ai: config/provider, sessions/<id>, ...)
//   Devices:  GET /api/devices  /  POST /api/devices {id, label}
//             POST /api/devices/:id/revoke
//   Legacy:   GET  /ws?session= (proxies to AI_DAEMON_WS when set)
export interface Env { DB: D1Database; AIXODIA_TOKEN: string; AI_DAEMON_WS?: string }

const json = (d: unknown, s = 200) =>
  new Response(JSON.stringify(d), { status: s, headers: { "content-type": "application/json" } });

const KEY_RE = /^[A-Za-z0-9:_-]{1,128}$/;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const u = new URL(req.url);
    if (u.pathname === "/ws" && env.AI_DAEMON_WS) {
      return fetch(env.AI_DAEMON_WS + "?session=" + encodeURIComponent(u.searchParams.get("session") ?? "default"), {
        headers: { Authorization: req.headers.get("Authorization") ?? "" },
      });
    }
    if (!u.pathname.startsWith("/api/")) return new Response("AIxodia Worker", { status: 200 });
    if ((req.headers.get("Authorization") ?? "") !== `Bearer ${env.AIXODIA_TOKEN}`)
      return json({ error: "unauthorized" }, 401);

    // ---- node discovery (ai quick-tunnel announcements) ----
    if (u.pathname === "/api/node" && req.method === "GET") {
      const n = await env.DB.prepare("SELECT tunnel_url, version, heartbeat FROM nodes WHERE id = 'ai'")
        .first<{ tunnel_url: string; version: string; heartbeat: number }>();
      const now = Math.floor(Date.now() / 1000);
      const age = n ? now - n.heartbeat : -1;
      return json({
        tunnel_url: n?.tunnel_url ?? null,
        version: n?.version ?? "",
        heartbeat_age_s: age,
        online: age >= 0 && age < 90,
      });
    }
    if (u.pathname === "/api/node/heartbeat" && req.method === "POST") {
      const b = await req.json<{ tunnel_url?: string; version?: string }>().catch(() => ({}));
      if (!b.tunnel_url || !/^https:\/\/[A-Za-z0-9.-]+\.trycloudflare\.com$/.test(b.tunnel_url))
        return json({ error: "tunnel_url must be https://*.trycloudflare.com" }, 400);
      await env.DB.prepare(
        `INSERT INTO nodes(id, tunnel_url, version, heartbeat)
         VALUES('ai', ?, ?, unixepoch())
         ON CONFLICT(id) DO UPDATE SET tunnel_url = excluded.tunnel_url,
           version = excluded.version, heartbeat = unixepoch()`
      ).bind(b.tunnel_url, b.version ?? "").run();
      return json({ ok: true });
    }

    // ---- ping: cheapest possible token check for the WS handshake ----
    if (u.pathname === "/api/ping" && req.method === "GET") {
      return json({ ok: true, service: "aixodia" });
    }

    // ---- stateless-ai JSON state ----
    if (u.pathname === "/api/state" && req.method === "GET") {
      const prefix = u.searchParams.get("prefix") ?? "";
      // Prefix may contain "/" (e.g. "sessions/"); the key charset itself is
      // still restricted by KEY_RE below.
      if (prefix.length > 128 || !/^[A-Za-z0-9:_/-]*$/.test(prefix))
        return json({ error: "bad prefix" }, 400);
      const r = await env.DB.prepare(
        "SELECT key FROM state WHERE key >= ? ORDER BY key LIMIT 200"
      ).bind(prefix).all();
      return json({ keys: (r.results ?? []).map((row) => String(row.key)) });
    }
    const sm = u.pathname.match(/^\/api\/state\/([^/]+)$/);
    if (sm) {
      const key = decodeURIComponent(sm[1]);
      if (!KEY_RE.test(key)) return json({ error: "bad key" }, 400);
      if (req.method === "GET") {
        const row = await env.DB.prepare("SELECT value, updated_at FROM state WHERE key = ?")
          .bind(key).first<{ value: string; updated_at: number }>();
        return json({ key, value: row?.value ?? null, updated_at: row?.updated_at ?? 0 });
      }
      if (req.method === "PUT") {
        const b = await req.json<{ value?: unknown }>().catch(() => ({}));
        const v = typeof b.value === "string" ? b.value : JSON.stringify(b.value ?? null);
        if (v.length > 500_000) return json({ error: "too large (500KB max)" }, 413);
        await env.DB.prepare(
          `INSERT INTO state(key, value, updated_at) VALUES(?, ?, unixepoch())
           ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = unixepoch()`
        ).bind(key, v).run();
        return json({ ok: true });
      }
    }

    // ---- device registry (prepare per-device tokens; v1 informational) ----
    if (u.pathname === "/api/devices" && req.method === "GET") {
      const r = await env.DB.prepare("SELECT id, label, created_at, revoked FROM devices ORDER BY created_at DESC LIMIT 100").all();
      return json(r.results ?? []);
    }
    if (u.pathname === "/api/devices" && req.method === "POST") {
      const b = await req.json<{ id?: string; label?: string }>().catch(() => ({}));
      if (!b.id || !KEY_RE.test(b.id)) return json({ error: "id required" }, 400);
      await env.DB.prepare("INSERT OR IGNORE INTO devices(id, label) VALUES(?, ?)")
        .bind(b.id, b.label ?? "").run();
      return json({ ok: true });
    }
    const dm = u.pathname.match(/^\/api\/devices\/([^/]+)\/revoke$/);
    if (dm && req.method === "POST") {
      await env.DB.prepare("UPDATE devices SET revoked = 1 WHERE id = ?").bind(decodeURIComponent(dm[1])).run();
      return json({ ok: true });
    }

    // ---- history (unchanged) ----
    if (u.pathname === "/api/sessions" && req.method === "GET") {
      const r = await env.DB.prepare(
        "SELECT id, title, provider, model, created_at, updated_at FROM sessions ORDER BY updated_at DESC LIMIT 200"
      ).all();
      return json(r.results ?? []);
    }
    if (u.pathname === "/api/sessions" && req.method === "POST") {
      const b = await req.json<{ id?: string; title?: string; model?: string }>().catch(() => ({}));
      const id = (b.id ?? "").trim() || `s${Date.now().toString(36)}`;
      if (!/^[A-Za-z0-9:_-]{1,128}$/.test(id)) return json({ error: "bad id" }, 400);
      await env.DB.prepare(
        `INSERT OR IGNORE INTO sessions(id, title, model, created_at, updated_at)
         VALUES(?, ?, ?, unixepoch(), unixepoch())`
      ).bind(id, b.title ?? id, b.model ?? "").run();
      const row = await env.DB.prepare("SELECT id, title, provider, model, created_at, updated_at FROM sessions WHERE id = ?")
        .bind(id).first();
      return json(row ?? { id });
    }
    // One session on its own: rename or delete, the way a chat app manages
    // conversations. DELETE removes the history rows (FK cascade) and the
    // daemon's state blob for that session.
    const sm1 = u.pathname.match(/^\/api\/sessions\/([^/]+)$/);
    if (sm1) {
      const sid = decodeURIComponent(sm1[1]);
      if (req.method === "PATCH") {
        const b = await req.json<{ title?: string }>().catch(() => ({}));
        const title = (b.title ?? "").trim().slice(0, 120);
        if (!title) return json({ error: "title required" }, 400);
        const info = await env.DB.prepare("UPDATE sessions SET title = ?, updated_at = unixepoch() WHERE id = ?")
          .bind(title, sid).run();
        if (!info.meta?.changes) return json({ error: "not_found" }, 404);
        return json({ ok: true, id: sid, title });
      }
      if (req.method === "DELETE") {
        await env.DB.prepare("DELETE FROM turns WHERE session_id = ?").bind(sid).run();
        await env.DB.prepare("DELETE FROM state WHERE key = ?").bind(`sessions/${sid}`).run();
        const info = await env.DB.prepare("DELETE FROM sessions WHERE id = ?").bind(sid).run();
        if (!info.meta?.changes) return json({ error: "not_found" }, 404);
        return json({ ok: true, id: sid });
      }
    }
    const m = u.pathname.match(/^\/api\/sessions\/([^/]+)\/turns$/);
    if (m) {
      const sid = decodeURIComponent(m[1]);
      if (req.method === "GET") {
        const before = Number(u.searchParams.get("before_seq") ?? "9007199254740991");
        const limit = Math.min(Number(u.searchParams.get("limit") ?? "50"), 200);
        const r = await env.DB.prepare(
          "SELECT seq, role, agent, job_id, text, created_at FROM turns WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?"
        ).bind(sid, before, limit).all();
        return json({ turns: (r.results ?? []).reverse() });
      }
      if (req.method === "POST") {
        const b = await req.json<{ role?: string; text?: string; agent?: string; job_id?: string }>().catch(() => ({}));
        const role = b.role ?? "model";
        await env.DB.prepare(
          "INSERT OR IGNORE INTO sessions(id, title, updated_at) VALUES(?, ?, unixepoch())"
        ).bind(sid, sid).run();
        // First user message names the session, like every messenger does.
        if (role === "user") {
          const titled = await env.DB.prepare("SELECT title FROM sessions WHERE id = ?").bind(sid)
            .first<{ title: string }>();
          if (titled && (!titled.title || titled.title === sid)) {
            const t = (b.text ?? "").slice(0, 42);
            await env.DB.prepare("UPDATE sessions SET title = ? WHERE id = ?")
              .bind(t.length ? t : sid, sid).run();
          }
        }
        const mx = await env.DB.prepare("SELECT COALESCE(MAX(seq),0) AS m FROM turns WHERE session_id = ?").bind(sid).first<{ m: number }>();
        const seq = (mx?.m ?? 0) + 1;
        await env.DB.prepare("INSERT INTO turns(session_id, seq, role, agent, job_id, text) VALUES(?,?,?,?,?,?)")
          .bind(sid, seq, role, b.agent ?? "", b.job_id ?? "", b.text ?? "").run();
        await env.DB.prepare("UPDATE sessions SET updated_at = unixepoch() WHERE id = ?").bind(sid).run();
        return json({ seq });
      }
    }
    return json({ error: "not_found" }, 404);
  },
};
