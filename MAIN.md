GOAL:
[COMPLETED] Name the keyless fallback provider by what actually serves it:
            ErrorText's free-tier guidance says "Pick Pollinations" instead of
            an "AIxodia Free" that is not a provider — the engine's built-in
            talks to text.pollinations.ai, and its builtin was renamed to match
            (engine v1.158). The two ErrorText unit-test expectations moved with
            the string; :app:testDebugUnitTest BUILD SUCCESSFUL.
[COMPLETED] Remove the empty-provider dead-ends: the chat model sheet, the
            Settings provider sheet and the Providers page always open as a
            picker (an empty list is fine) — no "No provider yet" nag. Paired
            with the engine defaulting to keyless Opencode. Shipped: 7329a73,
            compileDebugKotlin green, released via scripts/release.sh to the
            rolling `latest` (versionName 1.0.202610280344, signed CN=AIxodia).
[COMPLETED] chat AI works: message appears fast, AI answer arrives
[COMPLETED] app shows chats that live in D1 even without the tunnel
[COMPLETED] switching chat session does not reconnect the websocket
[COMPLETED] terminal: Shift+Tab usable from the key chips
[COMPLETED] terminal: the menu opens by swipe only — drop the menu key chip
[COMPLETED] terminal: arrows aligned (up above down, D-pad style) and holdable
[IN_PROCESS] non-login mode: the app must work with no API key at all, without
routing through the opencode CLI

PLAN:
[COMPLETED] D1Api: typed binds (numbers as numbers) + chatSessions/chatTurns reads
[COMPLETED] HistoryApi: sessions()/turns() fall back to D1 when the tunnel is down
[COMPLETED] unit tests: D1 row -> SessionRow/TurnRow mapping (D1ChatReadTest)
[COMPLETED] docs: README/db README say history lives in D1, phone reads it fallback
[COMPLETED] build, test (206 green), commit, push
[COMPLETED] release via scripts/release.sh: keystore from aixodia-keys, sign, verify, publish latest
[COMPLETED] bootstrap local toolchain: Android SDK 35 + JDK 17 for builds
[COMPLETED] TerminalKeys gets a Shift chip that latches like Ctrl/Alt; Tab
with Shift latched sends the backtab ESC[Z; TerminalView spends the Shift
latch on Tab (chips, hardware keys, typed text) the same one-shot way
[COMPLETED] build, test, commit, push, release via scripts/release.sh
  cdddcbf pushed; latest re-cut 2026-10-27 20:21 UTC (versionName
  1.0.202610272010, versionCode 1793131859, signature OK)
[COMPLETED] Remove the MenuKey chip and composable (and the Menu icon import),
drop TerminalKeys' onMenu param; the drawer still opens by a left-to-right
swipe in the grid, which was already wired via ModalNavigationDrawer
[COMPLETED] build, test, commit, push, release
  847f77b pushed; latest re-cut 2026-10-27 20:42 UTC (versionName
  1.0.202610272042, versionCode 1793133753, signature OK)
[COMPLETED] Arrows: move ↑ into the upper row's 5th slot so it sits directly
above ↓ (it used to float over ←); replace the four arrow KeyChips with a
HoldableKeyChip — pointerInput + awaitEachGesture, one send on tap, then
one send per KEY_REPEAT_MS (100ms) after a 400ms grace, deadline windows
on the wall clock so pointer noise cannot starve the repeat, ended by lift
[COMPLETED] build, test, commit, push, release
  57718bd pushed to main; latest re-cut 2026-10-27 20:57 UTC (versionName
  1.0.202610272057, versionCode 1793134669, signature OK)
[COMPLETED] ProvidersScreen: the built-in sheet tells AIxodia Free (keyless,
  throttled) apart from Opencode (free models served to the OpenCode app only);
  the add-provider key field stops claiming a key is mandatory for every adapter
[COMPLETED] ErrorText: name OpenCode's client-gated refusal and the keyless
  provider's own rate limit, instead of a bare "HTTP 402"
[COMPLETED] cut the app release via scripts/release.sh; `latest` now targets
3fdc242, which carries the provider wording and error mapping

NOTE:
- App release cut and published: `latest` → commit 3fdc242, APK signed
  CN=AIxodia, versionCode from the build. The in-app checker reads
  AIxodia-version.json, so phones on the old build are offered this one.
  (An earlier tail of the build log looked like a kspDebugKotlin death; it was
  only a mid-flight log — the build and the publish both succeeded.)
- App commit 3c34719 pushed; unit tests green (:app:testDebugUnitTest, 1m17s).
- Existing chats pinned to Opencode keep their pin, so after the engine deploys
  the sessions that name Opencode are repointed at AIxodia Free/openai-fast with
  PATCH /api/sessions/:id — a new default does not retro-fix an old chat.
- Engine fix 5d9415d pushed: AIxodia Free (keyless openai adapter) + Opencode,
  and the engine no longer refuses a provider for carrying no keys. CI + Release
  engine rebuild engine-latest; the app side is the label/error wording below.
- Unit tests: run with JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 and
  ANDROID_HOME=/opt/android-sdk, ./gradlew :app:testDebugUnitTest.
- AGENTS.md rule: every non-reading tool call must be accompanied by a MAIN.md update.
- Token hygiene: the PAT lives only in the gh keyring; never commit or echo it.
- Release is rolling: push to main rebuilds and clobbers the `latest` GitHub release.
- The script refuses a dirty tree: MAIN.md is committed before releasing.
- Keystore fetched from Tulipskun/aixodia-keys (md5-verified), gitignored here.
- Fresh environment: no Android SDK — bootstrapped to /opt/android-sdk again.
- JDK 17 (apt openjdk-17) is what AGP needs; 21 breaks JdkImageTransform.
- HoldableKeyChip: PressGestureScope has no isPressed here — repeater driven
  by awaitEachGesture + withTimeoutOrNull deadlines (tap = one send, hold =
  400ms grace then one send per 100ms until lift).
- Kotlin compiles green with holdable arrows and the D-pad alignment.
- Unit tests green after the arrow alignment + hold-to-repeat change.
- Unit tests green again (testDebugUnitTest) with the Shift chip.
- SDK on PATH for release.sh; release uses scripts/release.sh, then verify and push.
- Push needs the gh credential helper — configure git credential.helper before pushing.
- Commit cdddcbf pushed to Tulipskun/AIxodia main.
- release.sh refused a dirty tree on itself; MAIN.md notes committed before it runs.
- Release published: https://github.com/Tulipskun/AIxodia/releases/tag/latest (signature OK).
- Kotlin compiles clean with the Shift chip (compileDebugKotlin green).
- Menu chip removed; Kotlin compiles green (compileDebugKotlin).
- Unit tests green after the menu-chip removal.
- Commit 847f77b (menu opens by swipe only) pushed to main.
- 2026-10-28T03:05Z: fresh clone of Tulipskun/AIxodia to /root/AIxodia (public
  repo, no credential in the remote URL) and AGENTS.md read; its sections 1–2
  (find-based exploration, MAIN.md state tracking) are the protocol in force.
  Token hygiene: the PAT the operator pasted into chat was used only as a
  per-command GH_TOKEN env and never stored; it should be revoked and reissued.
