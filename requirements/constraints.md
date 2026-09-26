# Constraints (AXC-xxx)

- AXC-001 — Kotlin + Jetpack Compose (Material 3). No XML layouts except theme.
- AXC-002 — minSdk 26, targetSdk 35, AGP 8.8.x, Kotlin 2.1.x (same train as Droid-MCP).
- AXC-003 — Room is the only on-device store; D1 is reached only through the
  Cloudflare D1 REST API with the operator's Cloudflare API token (D-011). No
  Worker exists in this project.
- AXC-004 — WebSocket frames must stay JSON-compatible with `ai` sdk types;
  adding a field is additive-only, never rename `session_id`/`content`/`role`.
- AXC-005 — No API keys / tokens / D1 IDs committed. `local.properties` and
  `*.keystore` are gitignored; CI builds unsigned debug APK only.
- AXC-006 — Text-only v1: attachment refs render as `[file: name]` labels,
  never fetch bytes.
