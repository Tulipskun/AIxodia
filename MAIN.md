GOAL:
[COMPLETED] Name the keyless fallback provider by what actually serves it:
            ErrorText's free-tier guidance says "Pick Pollinations" instead of
            an "AIxodia Free" that is not a provider — the engine's built-in
            talks to text.pollinations.ai, and its builtin was renamed to match
            (engine v1.158). The two ErrorText unit-test expectations moved with
            the string; :app:testDebugUnitTest BUILD SUCCESSFUL. Shipped:
            156ebe1, scripts/release.sh cut the rolling `latest` at
            04:48:36Z (versionName 1.0.202610280447, versionCode 1793162848,
            APK 20,785,856 bytes, signature CN=AIxodia, AIxodia-version.json
            commit == HEAD).
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
[COMPLETED] Terminal tabs: the session list moves to the top of the drawer, a
            tab is closed by holding it instead of by a separate Close-session
            button, and reopening the terminal lands on the shells that are
            really there — the same pid, never a shell that was closed, and one
            fresh tab only when nothing is left. Shipped: 37e99e4 + bb4afae,
            released to the rolling `latest` with the scroll fix below.
[COMPLETED] terminal: a drag scrolls the page in a plain shell, not only inside
            a TUI like vim. :app:testDebugUnitTest BUILD SUCCESSFUL (Paparazzi
            included), shipped in bb4afae and released via scripts/release.sh
            to the rolling `latest` at 08:05:40Z (versionName
            1.0.202610280805, versionCode 1793174740, APK 20,802,664 bytes,
            signature CN=AIxodia, AIxodia-version.json commit == HEAD).
            History can be read but not copied: selection still addresses the
            live screen's rows, painted at the pan offset.
[COMPLETED] terminal: scrolling STILL dead in the operator's hands after
            1793174740, so the first round was wrong on device even though the
            pan math was right. Cause: every drag decision was gated on
            `fullScreenDetected`, a guess set by any program that parks the
            cursor three times (AbsoluteCsiCount >= 3 in TerminalEmulator) and
            cleared only by an alternate-screen exit or a reset — so after one
            inline TUI (opencode) a plain prompt stayed frozen for the rest of
            the session, and the drag went out as SGR wheel bytes nobody read.
            Fix: panMode = !mouseReporting() && !alternateScreen; the guess no
            longer gates the page and survives only as the tap->click guess; the
            now-unreachable SGR wheel branch is gone; once a vertical drag locks
            the view calls parent.requestDisallowInterceptTouchEvent so no
            ancestor can steal it. New TerminalScrollTest moves a real finger
            (MotionEvent) over a laid-out view: 7 tests, 0 failures, covering
            drag up, drag back to the prompt, the top of the history, typing,
            a mouse program getting the wheel, the guess not freezing a prompt,
            and a tap still clicking for a guessed program. Shipped ecffe26,
            released to the rolling `latest` at 08:22:07Z (versionName
            1.0.202610280822, versionCode 1793175727, APK 20,802,668 bytes,
            signature CN=AIxodia). Still unverified on a real phone — there is
            no device or KVM here, so the evidence is MotionEvent-level only.
[COMPLETED] terminal: on the phone the page still ran the wrong way, the IME
            opened by itself mid-scroll, and copied text lost its spacing and
            breaks. The drag compared against the wrong sign —
            dy = lastTouchY - event.y, so `pan += dy` moved the page against the
            finger — and the fling had been negated to match; both now run with
            the finger (down pulls history in, up returns to the prompt, fling
            follows). The keyboard came from `if (quick) keepSoftKeyboard()` on
            any ACTION_UP under 200 ms, which a flick also satisfies; only a
            still tap may ask now, and a tap that dismissed a live selection
            leaves the IME alone. Copy: TerminalRow.wrapped was declared but
            never set by the emulator, so every screen row became its own
            clipboard line; markWrapped() now marks both soft-wrap paths and a
            full-row erase drops the flag. Wrapped rows keep the width the shell
            wrote (lastWritten, so a rotation cannot turn unwritten cells into
            trailing spaces) and join the next row with no break, so a long ls
            line and the space it broke at survive. The composer moved to
            TextFieldValue: Enter sends only with the caret at the very end,
            anywhere else it is a line break, and the old latch that fired a
            send for every newline in the change is gone.
            :app:testDebugUnitTest BUILD SUCCESSFUL (Paparazzi included);
            TerminalScrollTest gained onlyATapAsksForTheKeyboard and its drags
            were re-aimed for the corrected direction; TerminalSelectionTest
            gained aSoftWrapCopiesAsOneLine and clearingARowDropsItsWrap.
            Shipped f53939b, scripts/release.sh cut the rolling `latest` at
            08:48:44Z (versionName 1.0.202610280848, versionCode 1793177324,
            APK 20,802,668 bytes, md5 4f3c7df6b35205f852545b06678f14f9,
            signature CN=AIxodia, AIxodia-version.json commit == HEAD).
[COMPLETED] terminal: the paragraph the operator copied out of 08:48 still
            came back in three pieces. The engine streams the tmux client's
            screen, and tmux lets the terminal autowrap long lines — pty probes
            show 200 X's in one write followed by \r\n, and 130 X's written
            continuously on the repaint after SIGWINCH — so shell output is a
            real soft wrap and already joined. The sample was not shell output:
            a full-screen program had wrapped its own paragraph into hard
            rows with three cells of left padding, a break space kept at the
            first cut and none at the second (a word split mid-syllable). Those
            rows carry no wrap mark. Copy now reads the block's padding from
            the first selected row and joins a row that ends within
            max(8, pad) cells of the right edge to an indented row below,
            stripping the padding from the continuation; a soft wrap is left
            alone, and full-bleed output (pad 0) or a row followed by column-0
            text never joins. TerminalSelectionTest gained
            aProgramWrappedParagraphCopiesAsOneParagraph (the operator's Thai
            paragraph on a 57-column screen, back as one line),
            aShortRowKeepsItsBreak, aRowAtTheEdgeIsNotJoinedToAnUnindentedOne
            and aFullBleedBlockKeepsItsBreaks; its put() helper uses WcWidth
            now so Thai marks do not take a cell. Known cost: a padded line
            ending within eight columns of the edge, followed by an indented
            line, is taken for a wrap.
            :app:testDebugUnitTest BUILD SUCCESSFUL, 222 tests, Paparazzi
            included. Shipped 9b556a6, scripts/release.sh cut the rolling
            `latest` at 09:25:30Z (versionName 1.0.202610280925, versionCode
            1793179530, APK 20,802,664 bytes, md5
            ce4b1ec96db1a75f242cf567c315d061, signature CN=AIxodia,
            AIxodia-version.json commit == HEAD).
[COMPLETED] terminal: the copy reflow no longer guesses with a fixed
            eight-column allowance. The block's right margin is read from the
            rows themselves: the column where at least two of its lines end —
            one line ending there proves nothing, a heading is often the
            longest line of its block. It only counts when some line ends
            short of it (the ragged last line of a paragraph), so a block
            whose every line reaches the same column — a table, an aligned
            list — is not treated as prose. A row ending exactly at the
            learned margin joins an indented row below; a soft-wrapped row is
            untouched and a block with no left padding is never reflowed. The
            operator's Thai paragraph still copies as one line. The old
            edge-case test was re-aimed at the learned margin (two rows end at
            19, the first cut is followed by column-0 text and keeps its
            break, the second is followed by an indented line and joins), and
            aLongestLineIsNotAWrapColumn plus alignedRowsAreNotAWrapColumn
            pin the two new guards. :app:testDebugUnitTest BUILD SUCCESSFUL,
            224 tests, Paparazzi included. Shipped 5067f40, scripts/release.sh
            cut the rolling `latest` at 09:56:19Z (versionName
            1.0.202610280956, versionCode 1793181379, APK 20,802,668 bytes,
            md5 85e4cec6b2df7c49aa7cbe1b4368536b, signature CN=AIxodia,
            AIxodia-version.json commit == HEAD).
[COMPLETED] terminal: the copy still broke the operator's paragraph — a chat
            style program word-wraps English words and Thai runs at the spaces
            between them, so its lines end at different columns and the
            repeated-column margin never forms. The block now tells the two
            cuts apart itself. When at least two lines end at one column and
            another ends shorter, the cut is at the text edge and mid-word:
            those lines join as they are, keeping their cells. Otherwise the
            cut fell on a space the program dropped: consecutive prose lines
            join with one space put back, unless the program carried it to the
            next line (the line starts at pad+1). Rows with two spaces in a
            row (tables, aligned code) and rows opening a list, heading,
            quote, table or box drawing end the run, and a block whose lines
            all reach the same column is aligned content, never reflowed. The
            operator's four-line Thai/English paragraph comes back on one line
            with its spaces intact. TerminalSelectionTest gained
            aWordWrappedParagraphCopiesAsOneParagraph (the reported text on a
            52-column screen), aCarriedSpaceIsNotDoubled and
            aListItemStartsANewLine; the two cases pinning the old margin rule
            were replaced. :app:testDebugUnitTest BUILD SUCCESSFUL, 225 tests,
            Paparazzi included.

PLAN:
[COMPLETED] TerminalSocket.open() waits for the engine's /v1/terminals answer
            before dialing anything (an engine mid-restart holds nothing in
            memory, and attaching a saved id there starts a new shell under the
            old tab); HistoryApi.terminalShells() tells Listed / Unlisted /
            Unreachable apart so silence cannot be read as "no shells"
[COMPLETED] closeSession(id) takes the tab from the row that was held, drops
            that tab's emulator, and opens one fresh shell when nothing is left
[COMPLETED] TerminalView painted only the emulator's screen and pinned
            scrollTopPx to 0, so a vertical drag did nothing unless the program
            asked for the mouse; the page now pans over the normal buffer's
            scrollback (drag, fling, scroll-to-bottom button, selection
            following what is drawn)
[COMPLETED] build toolchain re-bootstrapped on this host: Android SDK 35 +
            build-tools 35 at /root/android-sdk. Gradle needs JDK 17
            (/usr/lib/jvm/java-17-openjdk-amd64): under JDK 21 the build dies
            in :app:compileDebugJavaWithJavac on JdkImageTransform/jmod
[COMPLETED] scripts/release.sh pushes before it builds, and git has no
            credential helper here: run it with
            GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=credential.helper
            GIT_CONFIG_VALUE_0='!gh auth git-credential' plus
            ANDROID_HOME=/root/android-sdk, or it stops at "pushing main"

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
