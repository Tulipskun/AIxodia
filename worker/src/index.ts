// Cloudflare Worker REST for AIxodia D1 history + optional WS proxy.
// Endpoints (Bearer token compared to AIXODIA_TOKEN secret):
//   GET  /api/sessions
//   GET  /api/sessions/:id/turns?before_seq=&limit=
//   POST /api/sessions/:id/turns   {role, text}  (daemon ingest mirror)
//   GET  /ws?session= (proxies to AI_DAEMON_WS when phone can't reach daemon)
export interface Env { DB: D1Database; AIXODIA_TOKEN: string; AI_DAEMON_WS?: string }

const json = (d: unknown, s = 200) =>
  new Response(JSON.stringify(d), { status: s, headers: { "content-type": "application/json" } });

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

    if (u.pathname === "/api/sessions" && req.method === "GET") {
      const r = await env.DB.prepare("SELECT id, model, updated_at FROM sessions ORDER BY updated_at DESC LIMIT 200").all();
      return json(r.results ?? []);
    }
    const m = u.pathname.match(/^\/api\/sessions\/([^/]+)\/turns$/);
    if (m) {
      const sid = decodeURIComponent(m[1]);
      if (req.method === "GET") {
        const before = Number(u.searchParams.get("before_seq") ?? "9007199254740991");
        const limit = Math.min(Number(u.searchParams.get("limit") ?? "50"), 200);
        const r = await env.DB.prepare(
          "SELECT seq, role, text, created_at FROM turns WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?"
        ).bind(sid, before, limit).all();
        return json({ turns: (r.results ?? []).reverse() });
      }
      if (req.method === "POST") {
        const b = await req.json<{ role?: string; text?: string }>().catch(() => ({}));
        await env.DB.prepare("INSERT OR IGNORE INTO sessions(id, updated_at) VALUES(?, unixepoch())").bind(sid).run();
        const mx = await env.DB.prepare("SELECT COALESCE(MAX(seq),0) AS m FROM turns WHERE session_id = ?").bind(sid).first<{ m: number }>();
        const seq = (mx?.m ?? 0) + 1;
        await env.DB.prepare("INSERT INTO turns(session_id, seq, role, text) VALUES(?,?,?,?)")
          .bind(sid, seq, b.role ?? "model", b.text ?? "").run();
        await env.DB.prepare("UPDATE sessions SET updated_at = unixepoch() WHERE id = ?").bind(sid).run();
        return json({ seq });
      }
    }
    return json({ error: "not_found" }, 404);
  },
};
