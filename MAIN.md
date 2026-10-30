GOAL:
[COMPLETED] The strip under the grid is the app's own (operator, on the
            1.0.202610302316 build: "ก็เป็นแถบว่างประมาณ 1 บรรทัดอยู่นี่ไง", with a
            bash-tab screenshot after `seq 1 43`). The tmux bar is gone and
            qodercli's own last pane line stays as the program's, but the app was
            still throwing away part of a row: `resizeToView` floored
            `rows = height / cellHeight`, so the leftover strip — up to a whole
            font cell — stayed empty below the last row, between the prompt and
            the keys. The row count is still the whole cells that fit; each row
            is now painted at `height / rows`, so the grid ends exactly at the
            footer. `lineHeight` keeps the font's natural line, so the count can
            never flap between N and N-1 as a side effect of the stretch (on the
            handset the share is under a pixel per row at the 41px pitch). The
            page is re-anchored in the same step: scrollTopPx is read as a row
            with the old pitch and written back with the new one, in both the
            resize path and the same-size path, so a reader in the history is not
            moved by the pitch change. One new TerminalScrollTest case: a 1919px
            view ends exactly on `rows * cellHeight`, a second settled pass keeps
            both, and the prompt is still the bottom row. 379 unit tests, 0
            failures, 0 skipped; assembleDebug green. Shipped: 791cbbd pushed on
            its own, release run 41144940267 green after one re-run (the first
            attempt built and signed fine and then died on a transient HTTP 502
            from the release-asset API), `latest` at versionName 1.0.202610302335
            / versionCode 1793403348, AIxodia-version.json commit == 791cbbd, APK
            20,890,454 bytes verified as CN=AIxodia. Not handset-verified: the
            band is the operator's to judge, and qodercli's own last pane line
            remains the program's.
[COMPLETED] Three terminal complaints in one message (operator: "เวลา focus ช่องพิมพ์
            command ใน terminal พอกดปุ่ม extra key แล้วแป้นพิมพ์ปิดเปิดเอง ทำให้การ
            กดหน้าจอ Terminal ไม่ต้องเปิดแป้นพิมพ์ขึ้นมา แล้วก็ aix ที่ย้ายไปแล้ว แต่
            ไม่คืนพื้นที่ด้านล่างของหน้าจอ Terminal").
            (1) The keyboard blinked shut and open on every extra key pressed
            into the command field. `keepSoftKeyboardIfShown()` — which every key
            chip and Ctrl/Alt/Shift calls — took focus back to the grid whenever
            the IME was visible, and the grid is not the field: losing focus is
            the system's cue to hide the field's IME, and the restore posted
            behind it asked for the IME again. It now returns at once unless the
            grid itself is the focused view, so the field keeps its own keyboard
            and a chip tapped while it is up changes nothing about focus.
            (2) A tap on the grid raised the keyboard. The ACTION_UP tap path
            called `keepSoftKeyboard()` itself; it now only reports the tap
            (`onUserInteraction`), which is what the click-on-the-page handling
            and the existing TerminalScrollTest case read. The field is the only
            thing on the screen that asks for the IME; the grid keeps its
            `requestFocus()` on press, which never shows the keyboard in touch
            mode.
            (3) "The aix row moved but the bottom of the Terminal screen was not
            given back" is not an aixodia reservation — it was proved live on
            this host instead of guessed at. The pane is the full client: both
            live sessions report status off, window = pane = client = 54x43, and
            every attach in the engine log since the status-off build is 54x43,
            so the grid has the reclaimed row (no code reserves one: `resizeToView`
            is `height / cellHeight`, history and chrome are untouched). The
            blank line that is left is drawn by the running program: qodercli
            paints to `rows - 1` by design. Live proof: resizing that pane to 41
            rows moved its content to 40, and back at 43 it returned to 42
            (capture-pane after each step), and vim in a scratch 43-row pane uses
            its own last line the same way — tmux hands a program every row; it
            is the program that leaves the last one empty. Nothing was changed
            for this item; if the band on the handset is ever more than that one
            program line, it is something else and needs its own look.
            378 unit tests, 0 failures, 0 skipped; assembleDebug green.
            Shipped: 530507f pushed on its own, release run 41143281544 green,
            `latest` at versionName 1.0.202610302316 / versionCode 1793402197,
            AIxodia-version.json commit == 530507f, APK 20,890,462 bytes verified
            as CN=AIxodia. Not handset-verified yet: the operator's phone is
            where the field's keyboard and the tap will show it.
[COMPLETED] Move the terminal's status line into the app drawer (operator: "ย้าย
             [aix-t-e61<der> ไปไว้ที่ App drawer", with a screenshot of the phone).
             The row at the bottom of the grid was not the app's chrome at all: it
             was tmux's own status bar — `[aix-t-e61…] 1:qodercli* "◆ …" 07:46
             30-Oct-26` — spending one row of the phone on a session name, a window
             name and a clock. The bar is switched off on the engine side (see the
             engine's MAIN.md: its `set-option` lost a race against the session it
             was meant to quiet), and what it carried is in the drawer now: every
             shell row reads `pid 1234 · aix-t-e615791f6…`, the same name `tmux ls`
             answers to, and the program's window title was already the drawer's
             WINDOW card. `shellHandle` mirrors the engine's `tmux_name` so the two
             spell a tab identically. 374 unit tests, 0 failures, 0 skipped (five
             new in ShellHandleTest), assembleDebug green. Shipped: 1e5b81d pushed
             on its own, release run 41024334946 green, `latest` at versionName
             1.0.202610300809 / versionCode 1793347761, version.json commit ==
             1e5b81d, APK 20,874,074 bytes verified as CN=AIxodia. The engine half
             went out as ffc2094 with CI green on build, test, fmt and clippy, and
             both live sessions were quieted by hand on the host.

[COMPLETED] The keyboard resize is still wrong (operator: "บัคหน้าจอเลื่อนตอน
             เปิด/ปิดแป้นพิมพ์ยังไม่หายนะ"), and asked where, they picked
             "เนื้อหายังถูก scroll กลับขึ้นไปข้างบนสุดเหมือนเดิม" — the page still
             ends up laid out from the top. Two causes, both real, both fixed:
             (a) `TerminalBuffer.resize` grew a screen by appending the new rows
             UNDERNEATH its content, so after the keyboard closed the shell's line
             sat part-way up the page with a band of nothing below it, and the
             cursor was left counting from the old top. A terminal is read from
             its bottom edge, so the rows a screen gains belong ABOVE the content
             — taken back out of the scrollback first, blank space only once the
             history is spent — and the cursor travels with them. The alternate
             screen keeps its old top anchor: a full-screen program repaints from
             its own top-left corner the moment the size lands.
             (b) [WITHDRAWN the same session — see the entry below] The grid did
             not follow the layout at all while the keyboard was moving:
             `onSizeChanged` only reposted a resize 140ms after the layout
             stopped, so every intermediate frame drew the old row count into the
             new canvas — text at the top of a taller area, or its last rows
             clipped by a shorter one — and then jumped. I made the grid move with
             every frame and left only the socket report throttled.
             (c) With growth taking rows back out of the history, the page anchor
             can no longer be a one-directional delta: `onContentChanged` only
             compensates for a history that grows, so `resizeToView` now captures
             the document row at the top of the view and re-derives `scrollTopPx`
             from it, which holds for both directions.
             369 unit tests, 0 failures, 0 skipped (four new in
             TerminalEmulatorTest, one in TerminalScrollTest), assembleDebug
             green. Shipped: 94df615 pushed on its own, release run 41012120643
             green, `latest` at versionName 1.0.202610300608 / versionCode
             1793340493, version.json commit == 94df615, APK 20,874,078 bytes
             verified as CN=AIxodia. The operator's screen is still the only
             handset this has been on.

[COMPLETED] That build was worse, not better (operator: "รอบนี้บัคการแสดงผลหนักมาก
             ให้แก้ bug ไม่ใช่เพิ่ม bug"). The (b) half above is what did it, and it
             is withdrawn: the grid is back to moving only with the settled size,
             the report and the grid together as before. Chasing rows through every
             frame of an IME animation hands them back and forth between the live
             screen and the history while the shell is still writing to the size it
             was last told, so output lands against a bottom edge that keeps
             moving — a stale-but-still page is a smaller sin than a page whose
             bottom edge crawls. What stays is the model fix, (a) and (c): a
             resized screen holds its bottom row, the rows it gains come from the
             history above, and the page anchor is derived in both directions.
             Verified against `f5139e5` with `git diff`: the geometry path is
             byte-identical apart from comments, so the only behaviour left in this
             build that was not in the one before the last is the anchoring.
             369 unit tests, 0 failures, 0 skipped, assembleDebug green. Shipped:
             a2167be pushed on its own, release run 41013400792 green, `latest` at
             versionName 1.0.202610300621 / versionCode 1793341266, version.json
             commit == a2167be, APK 20,874,070 bytes verified as CN=AIxodia.

[COMPLETED] The operator's two-part order (operator: "ปรับการเลื่อนเปิด app drawer
             เป็นเต็มหน้าจอเหมือนเดิม โดยค้นหา skill ux ui ที่มีผู้ใช้งานจริงมาประกอบ
             และแก้ bug เวลา resize terminal มันเลื่อนหน้าจอจากบนลงล่างใหม่ตลอดเวลา
             ที่ resize"): give the drawer swipe back the whole page, with real-world
             UX practice behind it, and stop a terminal resize re-scrolling the page
             from the top. (a) The left 15% band is gone. `EdgeDrawer.kt`
             (`drawerEdgeGuard`, `isDrawerEdgePress`, `isInDrawerEdgeBand`,
             `DRAWER_EDGE_SHARE`) is deleted and `DrawerFloor.kt` replaces it:
             `drawerFloorGuard` refuses the drawer a drag begun on the measured
             bottom bar — the chat composer and session strip, the terminal's key
             rows and command field — and only there, so every other horizontal
             swipe on the page opens the menu the way Material wrote it. That
             matches how a drawer works on a gesture-navigation phone: the true
             screen edge belongs to the system back swipe, `DrawerLayout` and
             `SeekBar` are documented as opting out of it on their own through
             `View.setSystemGestureExclusionRects`, and Compose's
             `ModalNavigationDrawer` has no such exclusion — a band hugging the
             border competes with the system, a drag begun a hand's width inside
             the screen does not. The no-flash rule moved with the refusal: a
             floor gesture is consumed at its first move, sooner than Material's
             own drag threshold, so either the menu opens or nothing moves.
             (b) `TerminalView.resizeToView()` no longer answers a resize with
             `resetScroll()`; the keyboard opening pushed a reader's history off
             the screen every single time. The buffer already hands a shrinking
             live screen's top rows to the history above it and the
             screen-change callback adds that height back to the page, so
             holding the position is now just a matter of not throwing it away.
             compileDebugKotlin, assembleDebug and the full unit suite green:
             364 tests, 0 failures, 0 skipped — three new resize regression tests
             in TerminalScrollTest and DrawerFloorTest in place of EdgeDrawerTest.
             (Read with the entry above: dropping `resetScroll()` was necessary
             and was not enough, and only one direction of the resize anchor was
             right. This is the batch the operator replied "ยังไม่หาย" to.)
             Shipped: 23bad55 + f5139e5 pushed together, release run 41008343071
             green, `latest` at versionName 1.0.202610300527 / versionCode
             1793338027, version.json commit == f5139e5, and the downloaded
             AIxodia.apk (20,874,078 bytes) verifies as CN=AIxodia. Still
             operator-screen questions: whether a full-width swipe now opens the
             menu from wherever the thumb lands without stealing a scroll, and
             whether a keyboard open leaves the history row being read in place.

[COMPLETED] The defect as the operator actually described it (operator: "การปัด
             หน้าจอส่วนที่ไม่ได้ตั้งไว้มันเปิด app drawer ขึ้นมาชั่วขณะ แทนที่จะไม่
             ทำงานเลย ตั้งค่าให้ถูกต้องไม่ได้หรือไง"): a sideways swipe begun off the
             reserved band slides the drawer out a few millimetres and snaps it
             back — a visible flash — instead of doing nothing at all. The band
             work below only stopped the drawer SETTLING open: Material begins
             following the finger at its own ~8dp drag threshold while the guard
             cut the claim at its own 24dp slop, and that gap is exactly the
             flash. Fixed by refusing the drawer the drag rather than its outcome:
             the guard now consumes the first move of any gesture begun outside
             the band, sooner than the drawer's threshold can elapse, so nothing
             moves; the 24dp slop and `isSidewaysDrag` went with it.
             compileDebugKotlin green, 364 unit tests, 0 failures. Ship as the
             `ui:` commit pushed on its own, docs record behind it. Done: 2c1b795
             pushed alone, release run 41005535448 green, `latest` at
             versionName 1.0.202610300456 / versionCode 1793336215, version.json
             commit == 2c1b795, APK re-downloaded and verified as CN=AIxodia.

[COMPLETED] Fix the terminal opening its drawer when the thumb swipes over the
             command field (operator: "แก้ bug app drawer เปิดเมื่อปัดที่ช่องพิมพ์
             ข้อความใน terminal"). The terminal window's drawerEdgeGuard passes
             no bottomReservedPx, so the left 15% band the drawer owns runs all
             the way down the page through the key rows and the OutlinedTextField
             — the very surface the chat composer already escapes. Reserve the
             footer for the footer: its measured height plus the safe-area strip
             beneath it (the IME once the keyboard is up), then compile, test and
             take it to the rolling `latest` release. Implemented — the terminal
             footer now reports its top to the guard and `drawerEdgeGuard` stops
             the band there; compileDebugKotlin green and the full unit suite is
             368 tests, 0 failures. Shipped: 5604a88 + 82ca7c5 on main, release
             run 41003618429 green, `latest` now versionName 1.0.202610300435 /
             versionCode 1793334932, AIxodia-version.json commit == 82ca7c5, and
             the downloaded AIxodia.apk (20,874,070 bytes) verifies as
             CN=AIxodia. NOT verified on a handset — no phone here, so the
             operator's screen is the first real render.

[COMPLETED] Fix the display hanging on "Sending request to provider…" (operator:
            "แก้ bug การแสดงผลค้างที่ sending request to provider"). Cause is in
            the transport, not the card: RunSocket read the run's SSE stream with
            readTimeout(0), so a stream an idle tunnel had dropped without a
            close never errored, never reconnected and never delivered the turn's
            `done` frame — the live Think(WAITING) block ticked forever, busy
            stayed set and every later send was refused. The stream client now
            times a read at three of the daemon's 15s heartbeats, which is an
            ordinary stream failure: state → OFFLINE, backoff redials from the
            last applied seq, and the existing reconnect path recovers the turn.
            Then commit, push and let the release workflow cut `latest`.

[COMPLETED] Session 2026-10-30 (drawer + goals placement): the side drawer may
             open only from a swipe that starts inside the left ~15% of the
             screen, and the goal/plan card belongs in the strip above the
             composer rather than at the very top of the chat. Implemented,
             verified on this host (360 tests, 0 failures) and shipped as the
             rolling `latest` at 24a5dd0.

[IN_PROCESS] Session 2026-10-29 (display/logic review): fix verified AIxodia
             display bugs and flawed UI state logic, with regression coverage.

[COMPLETED] Second batch: goals-card paging offset, offline/uploading send
            eligibility, draft-route re-pin, run/stop ownership, per-chat
            drafts and active-session header/drawer, plus on-device crash
            capture. Full suite: 348 tests, 332 pass, the same 16 ARM renderer
            initialization failures; debug APK assembles.

[IN_PROCESS] Diagnose the operator's new report that the shipped build closes
             right after pressing send ("แอพเด้งหลังจากกดส่งข้อความ"). Capture
             the next trace via Settings → About → Share last crash log; fix
             only the frame that trace names.

[COMPLETED] Fix the crash on pressing send (operator: "แอพ crash ตอนกดส่งข้อความ"),
            then commit, push and cut the rolling `latest` release.

[COMPLETED] Audit the Android UI (impeccable `audit`, native variant): a11y,
            performance, theming, platform conformance, adaptivity. Document
            findings only; no fixes in this pass. Score 12/20 (Acceptable),
            1 P0, 4 P1, 17 P2, 4 P3.

[IN_PROCESS] Fix every audit finding, P0 through P3, and take the result all
            the way to the rolling `latest` release.

[COMPLETED] Fix the terminal's two display bugs (operator: "แก้ bug การแสดงผลใน
            Terminal ไม่ถูกต้องในบางครั้ง หลังจากการ reconnect เห็นหน้าจอว่าง
            ต้องเปิด/ปิดแป้นพิมพ์ก่อน 1 ครั้งถึงจะเห็นหน้าจอ เมื่อ engine restart
            แท็บ ที่ควรปิดไปแล้วยังอยู่").
            (1) Blank screen after a reconnect: the engine's attach sends the
            reset and, under tmux, no replay (the ring is dropped), and the pane
            is repainted by tmux only on SIGWINCH. The engine kicked only an
            alternate-screen session on a same-size attach, so a plain shell came
            back to a cleared grid until the keyboard changed the size. Engine
            fix (aixodia-engine): always kick when the size did not change and
            tmux is on, and kick after the ready/replay writes so the repaint
            cannot be broadcast before the subscriber exists. A pty probe of
            tmux 3.4 proved a same-size SIGWINCH to the pty's foreground group
            repaints the whole pane, content included; the engine crate now
            carries that as a test through its own pty.
            (2) Tabs closed before an engine restart came back: a close was a
            single DELETE, kept as a process-local tombstone. An engine that is
            restarting never hears it, tmux keeps the shell, and the next list
            names it again — so the tab returned with the restarted engine (or
            with the next app launch). Closed ids are now persisted
            (`terminal_closed_ids`), stay out of the tab list and out of the
            list `onReady` republishes, and every list poll re-issues the DELETE
            for an id the engine still names until it stops; `endAllTabs` also
            closes listed tabs that never attached.

PLAN:
[COMPLETED] Engine: kick a reattached pty under tmux even when the size did not
            change, after ready/replay, and prove it with tmux 3.4 through a pty
            (engine crate: fmt/clippy clean, 9 tests incl. the pty repaint)
[COMPLETED] App: persist closed tab ids, retry the DELETE while the engine
            still lists them, prune them when it stops, and close listed-but-
            unattached ids on endAllTabs. 378 unit tests, 0 failures, 0 skipped
            (two new in TerminalSocketTest), assembleDebug green.
            Shipped: engine ce14ece pushed first (CI 41120799551 and Release
            41120799779 green; the daemon self-updated at 20:23:41Z), then the
            app 4cb4c7e on its own (Release APK 41120805630 green). `latest`
            at versionName 1.0.202610302009 / versionCode 1793390986,
            AIxodia-version.json commit == 4cb4c7e, APK 20,890,454 bytes
            verifies as CN=AIxodia. The engine half was proved live on this
            host, not just in a unit test: a fresh shell got `echo
            REKTEST-MARKER`, the WebSocket was dropped, and a same-size
            reattach (no resize, keyboard never involved) received 328 binary
            bytes carrying the marker, then DELETE answered 204 and took the
            tmux session with it. The app half is not handset-verified yet —
            the operator's phone is where a close-then-restart will show it.
[COMPLETED] Take the tmux status bar off the phone's grid and carry what it
            showed in the drawer: chain `set-option -t <name> status off` into the
            engine's `new-session`, quiet adopted sessions at boot, and print each
            shell's server name in its drawer row.
[COMPLETED] Ship the drawer row as one `ui:` commit pushed on its own, verify the
            published `latest`, then let the operator open a fresh tab: the bar
            only stays off for new shells once the engine build carrying the
            chained `set-option` is deployed. 1e5b81d → run 41024334946 → `latest`
            1.0.202610300809; engine ffc2094 → CI and Release engine both green.
[COMPLETED] Withdraw the per-frame grid resize: restore `pendingResize` to the
            settled-size path so the grid and the socket report move together.
[COMPLETED] Ship the withdrawal as one `ui:` commit pushed on its own, verify the
            published `latest`, then leave the anchoring judgement to the
            operator's screen. If the anchoring itself is what broke the display,
            the fallback is one revert of `TerminalBuffer.resize` back to
            appending below, which returns the old floating prompt and nothing
            else: a2167be → run 41013400792 → `latest` 1.0.202610300621.
[COMPLETED] Keyboard resize: anchor the normal buffer's growth to its bottom row
            (history back down, blanks above, cursor travelling with the rows) and
            keep the alternate screen top-anchored; move the grid on every
            `onSizeChanged` and throttle only the socket report; re-derive
            `scrollTopPx` from the document row at the top of the view.
[COMPLETED] Ship as one `ui:` commit pushed on its own, then the docs record,
            then verify the published `latest` against its commit and signer:
            94df615 → run 41012120643 → `latest` 1.0.202610300608.
[COMPLETED] Full-width drawer swipe: delete `EdgeDrawer.kt` and its band tests,
            add `DrawerFloor.kt` with `isOnDrawerFloor` + `drawerFloorGuard`,
            repoint ChatScreen and TerminalPanel at it, replace EdgeDrawerTest
            with DrawerFloorTest.
[COMPLETED] Terminal resize: drop the `resetScroll()` out of
            `resizeToView()`, re-anchor `lastHistoryRows` to the history the
            resize left behind and clamp, then prove it with three
            TerminalScrollTest cases — a page reading history keeps its row at
            the top of the screen when the bar appears and when it goes away,
            and a reader on the prompt stays there.
[COMPLETED] Ship as two `ui:` commits pushed together (no `[skip ci]` on the
            tip of that push), then the docs record behind them: 23bad55 +
            f5139e5 → run 41008343071 → `latest` 1.0.202610300527.
[COMPLETED] Refuse the drawer the drag: `drawerEdgeGuard` consumes every
            unconsumed move of a non-band gesture at the first event, so
            Material's own drag threshold is never reached. `DrawerEdgeSlop`,
            `isSidewaysDrag` and their five tests deleted; one footer-band test
            added in their place. 2c1b795 pushed alone → release run
            41005535448 published `latest`; docs record followed with
            `[skip ci]` and did not cancel the in-flight build.
[COMPLETED] Terminal drawer band: measure the footer in TerminalWindow, hand
            its distance from the bottom of the window to drawerEdgeGuard as
            bottomReservedPx, keep the grid's own edge swipe. compileDebugKotlin
            green; :app:testDebugUnitTest 368 tests, 0 failures.
[COMPLETED] Pushed and shipped: 5604a88 + 82ca7c5 on main, release run
            41003618429 published the rolling `latest` (versionName
            1.0.202610300435), APK signature re-verified from the asset.
[COMPLETED] Review chat rendering, paging, live-event handover and formatting;
            baseline: 247 tests, 231 passed, 16 renderer initialization failures.
[IN_PROCESS] Reproduce verified defects, apply focused fixes and add regression
             tests without changing the product's UI conventions.
[IN_PROCESS] Run the full unit/snapshot suite and assemble the debug APK.
[IN_PROCESS] Commit, push and publish the app's rolling `latest` after each
             validated fix batch, as explicitly requested by the operator.
[COMPLETED] Second-batch full run: 348 tests, 332 passed, 16 failed and all 16
            are the known ARM renderer initialization failures (8
            TerminalScrollTest, 3 TerminalViewSnapshots, 5 ScreenSnapshots).
            assembleDebug succeeded; the 12 real second-batch failures are gone.
[IN_PROCESS] Ship the batch, verify the published APK and version asset, then
             ask the operator for the recorded crash trace.

[COMPLETED] Send-crash fix (this pass): every liveBlocks write now runs under a
            Mutex in ChatViewModel. WS frames mutate the live timeline on
            Dispatchers.IO while send()/beginLiveTurn/clearLiveTurn tear it down
            on the main thread; the plain read-modify-write lost updates, so two
            items could share a LazyColumn key ("Key was already used" kills the
            app mid-turn — same class as 515e9ec) or an emptying clear raced a
            frame re-adding a block. beginRequest/settleOpenRequest/openRequest/
            settleRequest/onBlockFrame(REASONING+TEXT)/startLiveTool/
            completeLiveTool/onTrace(compact + tool_call + tool_result +
            reasoning) all go through mutateLiveBlocks; turn clears stay direct
            assignments so a torn-down turn never waits behind queued frames.
            compileDebugKotlin + :app:testDebugUnitTest (247 tests) +
            assembleDebug BUILD SUCCESSFUL on JDK17 + /opt/android-sdk.
            Shipped: 3ce0de1 pushed to main; scripts/release.sh cut the rolling
            `latest` at 06:12:49Z (versionName 1.0.202610290612, versionCode
            1793254369, signature CN=AIxodia OK, AIxodia-version.json commit ==
            HEAD). NOT verified on a phone — no handset and no KVM here, so the
            operator's screen is the first real render.

[COMPLETED] Session 2026-10-29 (impeccable finish pass): verified every
            P0–P3 fix already in the working tree against its finding, wrote
            DESIGN.md + .impeccable/design.json from the implemented tokens,
            synced README (token-at-rest + stale Thai status-word quote) and
            PRODUCT.md (credential model + voice split), fixed the one compile
            error the tree carried (AddProviderSheet used ctx without a
            LocalContext declaration).
[COMPLETED] Load PRODUCT.md + Android platform reference (context run).
[COMPLETED] Read the theme, entry point, chat/thread surface, settings and
            terminal panels; check strings, manifest, build config.
[COMPLETED] Score five dimensions, write the report with P0-P3 findings.
[COMPLETED] P0: provider management reachable again (drawer Key row opening
            ProvidersScreen), and the duplicate dead provider UI in Settings
            removed (its six dialog states and imports deleted).
[COMPLETED] P1: 48dp composer discs (DiscControl size floor + Role.Button
            semantics) and terminal keys with a semantic click path, Settings
            imePadding, bounded chat ViewModel retention (one viewModel keyed
            by nav graph, not per session id — twenty chats no longer mean
            twenty live ViewModels), token out of auto-backup
            (backup_rules/data_extraction_rules exclude the DataStore file)
            and sealed at rest with AES-GCM under an Android-keystore key
            (TokenCipher; plaintext legacy values pass through and re-seal on
            next save; tokenFlow reads on Dispatchers.IO).
[COMPLETED] P2/P3: legend/type sizes raised to a 12sp readout floor, semantics
            on expandable rows and selection, terminal TalkBack text
            (TerminalBuffer.spokenTail), system bars declared dark
            (enableEdgeToEdge SystemBarStyle.dark), non-role colours moved into
            AixodiaColors beside the scheme (no second palette in ui/ except
            the deliberately unthemed terminal ink), adaptive launcher icon
            added, lazy drawer (SessionOrganize LazyColumn grouped+keyed),
            ticker at one-per-second while busy, thinking-level picker
            (ChoiceRow gated on model.supportsThinking; engine capability
            reporting is the open follow-up below), StatusBanner severity set
            where the outcome is known, strings sweep into res/values
            (English default, values-en carries the shared technical subset),
            rememberSaveable overlays/drafts, scrollable setup sheet,
            Snackbar copy feedback, navigationBars∪ime inset union, connection
            word kept as Online/Connecting/Offline strings (README's "ai
            ออฟไลน์" quote was stale — removed there; PRODUCT.md's voice line
            corrected to what the resources actually ship: English default in
            res/values, values-en refines the notices and terminal states).
[COMPLETED] Toolchain: JDK 17 + /opt/android-sdk, compileDebugKotlin +
            testDebugUnitTest (247 tests, 0 failures) + assembleDebug green;
            Paparazzi goldens recorded (:app:recordPaparazziDebug) and
            verify-passed — 8 PNGs committed under app/src/test/snapshots,
            rendered-output evidence for the first time.
[COMPLETED] DESIGN.md written from the implemented tokens (scan mode) with the
            .impeccable/design.json sidecar; README auth section and PRODUCT.md
            credential line synced to TokenCipher + backup exclusion.
[IN_PROCESS] Commit, push to main (release).
[COMPLETED] Drawer + goals placement (2026-10-30): the side drawer opens only
             from a swipe begun in the left 15% of the page — ui/display/
             EdgeDrawer.kt holds the band, the slop and the passive watcher,
             wired onto ChatScreen's Scaffold and TerminalPanel's content
             surface, each ModalNavigationDrawer narrowed to
             gesturesEnabled = isOpen so the built-in full-width drag no longer
             claims a swipe begun over content. The goals/plans card moved from
             the head of the thread into the bar above the composer beside the
             session strip; threadHeaderItems dropped its goals offset (only
             the older marker is a lazy item now) and the expanded body is
             capped at 168dp and scrolls, because a bottom bar takes its height
             off the conversation. Tests: new EdgeDrawerTest on the predicate,
             ThreadPagingTest re-aimed at the single-argument geometry.
             Verified on this host: :app:compileDebugKotlin, :app:testDebugUnitTest
             (360 tests, 0 failures, 0 skipped — the renderer suites really ran,
             this host is amd64) and :app:assembleDebug (20.9 MB APK) all green
             with JDK 21 + /opt/android-sdk. NOT on a handset: the gesture feels
             right in theory and the predicate is tested, but only the operator's
             screen can say whether 15% is a comfortable band, and TerminalPanel
             has no menu button, so if the platform view swallows the edge drag
             the terminal drawer would lose its only opening gesture.
[COMPLETED] Commit, push and publish this placement batch as the operator
             explicitly asked; verify the workflow, the released APK and the
             version asset before calling it available. The one open question
             is the terminal window, which has no menu button at all: there the
             edge swipe is the only way into its drawer, so if the platform
             view swallows the drag the session controls become unreachable and
             that surface needs its own affordance.
[COMPLETED] Drawer feel follow-up (operator: "ไม่ใช่แค่แตะนิดเดียวแล้วเปิด app
             drawer สิ ทำให้เหมือนเดิมเลย"): the shipped drawerEdgeSwipe watched
             for a 24dp slop and then called open(), so a short flick snapped the
             menu wide instead of dragging it. Replaced by drawerEdgeGuard, which
             moves nothing. The drawer's own drag (material3 1.3.1 puts it on an
             ancestor of the content as anchoredDraggable → foundation draggable)
             follows the finger and settles on travel or a flick, and its slop
             loop reads the Main pass and aborts on a consumed change — so a
             guard on the content can veto that drag from underneath, which is
             exactly what the guard does for a drag begun outside the left 15%
             band. A drag begun inside the band is untouched and answers the way
             it did before this batch; gesturesEnabled is back to its default on
             the chat screen and to the selecting/verticalDrag form on the
             terminal, with the guard at each content surface. Verified here:
             compileDebugKotlin + testDebugUnitTest (362 tests, 0 failures, 0
             skipped) + assembleDebug.
[COMPLETED] Bottom-bar correction (operator: "การเลื่อนจากแถบด้านล่างเปิดหน้า app
             drawer ได้ไง? มันไม่ควรเกิดขึ้น"): the band was measured on x alone,
             and the composer, the session strip and the goals card all begin
             inside the left 15% — so a thumb hooking sideways over the bar
             still reached the drawer's drag. isDrawerEdgePress now takes the
             press's y, the surface height and the height of the bar pinned to
             the bottom, and the band is that left slice cut short above the
             bar; ChatScreen measures its bottomBar with onSizeChanged and hands
             the pixel height to the guard. A press over the bar is no press at
             all, so the guard sees the gesture through and the bar's own
             children (scrolling the strip, expanding the card, long-pressing
             the input) keep it. EdgeDrawerTest grows six vertical-limit tests.
             Verified here: compileDebugKotlin + testDebugUnitTest (368 tests, 0
             failures, 0 skipped) + assembleDebug.

NOTE:
- 2026-10-30T08:09Z: `latest` carries the drawer's shell names — versionName
  1.0.202610300809, versionCode 1793347761, commit 1e5b81d, APK 20,874,074 bytes,
  signer CN=AIxodia.
- 2026-10-30T08:07Z: a row of the terminal grid that the app does not draw is
  still a row of the grid. The line the operator wanted moved — `[aix-t-e61…
  "◆ …" 07:46 30-Oct-26` — was tmux's status bar, painted by the shell server
  inside the PTY, which is why no amount of `TerminalPanel` or `TerminalView`
  searching found it: the app never owned it. The tell was that its session name
  is the engine's tmux session name (`aix-` + tab id), readable on the host with
  `tmux -L aixodia list-sessions`. Anything like this belongs to the engine
  (`/root/aixodia-engine`) first and the app second.
- 2026-10-30T06:21Z: `latest` is the withdrawal — versionName 1.0.202610300621,
  versionCode 1793341266, commit a2167be, APK 20,874,070 bytes, signer
  CN=AIxodia. The `ui:` commit went out alone and the docs record follows it with
  `[skip ci]`, which does not cancel the in-flight build.
- 2026-10-30T06:18Z: the per-frame resize was an unforced error, and the shape of
  the mistake is worth keeping. A terminal has two owners of one number: the local
  grid and the PTY. The old code kept them equal at all times by only ever moving
  them together, one beat late. Splitting them — grid now, shell later — buys a
  smoother animation and spends the invariant, and every byte the shell writes in
  between is positioned against a bottom edge the shell does not have. When a fix
  trades an invariant for polish, the polish has to be worth the class of bug it
  opens; this one was not, because the operator's complaint was where the page
  *settled*, not how it travelled.
- 2026-10-30T06:18Z: how to tell which of the two resize halves is on the phone.
  The withdrawn (b) was about motion during the keyboard animation; the kept (a)
  and (c) are about the settled layout — the shell's line as the last row of the
  grid. If the build after this one still misbehaves *while standing still*, the
  next revert is `TerminalBuffer.resize`, back to appending new rows underneath.
- 2026-10-30T06:05Z: the first keyboard-resize fix removed one wrong call and
  left two wrong models standing. `TerminalBuffer.resize` had only ever been
  bottom-anchored on the way down (top rows to history) and top-anchored on the
  way up (blanks appended under the content), so a screen returning from the
  keyboard kept its *rows* but lost its *place*: the shell's line stayed where the
  old bottom had been, several rows above the new bottom, and the cursor counted
  from the old top too. That is the "content scrolled back to the top" the
  operator still sees, and no amount of scroll-offset bookkeeping fixes it,
  because the page is not scrolled — the content inside it moved.
- 2026-10-30T06:05Z: the other half was the burst, not the settled size.
  (Superseded by the 06:18Z entry above: this was written as the diagnosis that
  justified moving the grid per frame, and that remedy was withdrawn the same
  session.)
  `onSizeChanged` deliberately waited 140ms after the layout stopped before
  fitting the grid, to keep a repaint-per-frame SIGWINCH storm away from
  full-screen programs. What it also did was leave every frame of the keyboard
  animation drawing the old grid into the new canvas — the whole page at the top
  of a taller area, blank band under it, then a jump. The two are separable: the
  grid is cheap to move per frame (no row is reallocated unless the width
  changed), and only the report down the socket has to be coalesced. The class
  comment that justified the wait named the socket, not the grid.
- 2026-10-30T05:31Z: `latest` carries both — versionName 1.0.202610300527,
  versionCode 1793338027, commit f5139e5, APK 20,874,078 bytes, signer
  CN=AIxodia. The two `ui:` commits went out in ONE push with no `[skip ci]` on
  its tip, which is what the trap note at 04:30Z asked for, and the run built.
- 2026-10-30T05:31Z: the drawer entries above are history, not the current
  design. 24a5dd0's left-15% band and 2c1b795's non-band first-move refusal are
  both gone from the placement half of that idea; only the refusal at the first
  move survived, and it now applies to the bottom bar at any width. Anything
  MAIN.md says about an edge band should be read as superseded by the entry at
  the head of GOAL.
- 2026-10-30T05:26Z: the band was the wrong idea, not a wrong number. Android's
  own gesture-navigation guidance gives the left and right screen edges to the
  system back swipe, and says `DrawerLayout` and `SeekBar` opt out of that
  collision automatically via `View.setSystemGestureExclusionRects`; a Compose
  `ModalNavigationDrawer` has no such hook, so the 15% band was asking the app to
  argue with the system over the one strip where the system always wins. Real
  side-drawer apps answer the swipe where the thumb lands — a hand's width inside
  the screen — and that is also what `gesturesEnabled` was written for. The
  refusal therefore moved from *how far left* a drag started to *what it started
  on*: only the bottom bar keeps its own drags, everything above it is the
  drawer's at full width. `EdgeDrawer.kt` is deleted, not disabled.
- 2026-10-30T05:26Z: the terminal's re-scroll on resize was one call, not a
  layout problem. `resizeToView()` ended with `resetScroll()`, which zeroes the
  page and re-anchors the history count. The compensation for a shorter live
  screen was already there and already fired — `TerminalBuffer.resize` pushes
  its top rows into the history in document order, `em.resize` reports the screen
  change, and `onContentChanged` adds `(newRows - lastHistoryRows) * cellHeight`
  back to the page, which holds every row's pixel position exactly — and then
  `resetScroll()` ran and threw all of it away. Deleting the reset is the whole
  fix; the test pins the *document anchor* (`scrollback.size - pan/cellHeight`)
  rather than the pan, because the pan is supposed to grow by the rows the buffer
  moved.
- 2026-10-30T04:58Z: `latest` carries the guard fix — versionName
  1.0.202610300456, versionCode 1793336215, commit 2c1b795, APK 20,874,070
  bytes, signer CN=AIxodia.
- 2026-10-30T05:02Z: the drawer's flash on an off-band swipe was a threshold
  mismatch, not a placement one. Material's `Modifier.draggable` starts moving
  the drawer once the drag passes ITS slop (~8dp platform touch slop), while the
  guard only consumed past ITS own 24dp `DrawerEdgeSlop` — so every refusal began
  as ~16dp of visible drawer. Consuming from the first Move starves the ancestor
  before it can start; a descendant was asked for that same event earlier, so
  scrolls, selections and sideways code blocks are untouched.
- 2026-10-30T04:37Z: `latest` re-downloaded from the release and checked —
  signer CN=AIxodia, 20,874,070 bytes, builtAt 2026-10-30T04:35:32Z.
- 2026-10-30T04:30Z: `[skip ci]` is read from the HEAD commit of a push, so
  pushing the `ui:` fix and the `docs: … [skip ci]` record together in ONE push
  cancelled the release build for both. The earlier batches pushed the two
  commits separately, which is why they built. For this one the workflow was
  started by hand (`gh workflow run release.yml --ref main`, run 41003618429)
  on main, which carries 5604a88 + 82ca7c5. Push `ui:` alone first, then the
  docs commit, or drop `[skip ci]` from the tip.
- 2026-10-30T04:18Z: the bottom-bar correction is committed as 9d16608 and
  published. Release run 41001954525 green (unit + renderer checks, then
  build/sign/publish); `latest` is now versionName 1.0.202610300417,
  versionCode 1793333875, AIxodia-version.json commit ==
  9d1660861af1c9ddfe14abffcb1e936e9b6387fe, and the downloaded AIxodia.apk
  verifies as CN=AIxodia (SHA-256 652f0b4ae06b92cfbed47df335d1a7216b9e54640ae
  41c0aa26ae66f0be849a6). Locally: 368 unit tests, 0 failures, 0 skipped. Still
  operator-screen questions: whether the band now stops where the composer
  begins on the real handset, whether 15% is a comfortable width, and whether
  the terminal drawer's edge drag survives its AndroidView.
- 2026-10-30T03:51Z: the drawer-feel correction is committed as c13948b and
  published. Release run 40999489691 green (unit + renderer checks, then
  build/sign/publish); `latest` is now versionName 1.0.202610300351,
  versionCode 1793332279, AIxodia-version.json commit ==
  c13948b4ed62c367d84708505041cdb00ba827e3, and the downloaded AIxodia.apk
  verifies as CN=AIxodia (SHA-256 652f0b4ae06b92cfbed47df335d1a7216b9e54640ae
  41c0aa26ae66f0be849a6). The eager-open build (24a5dd0) is superseded, so the
  only drawer build the operator should install is this one.
- 2026-10-30T03:25Z: the drawer-edge + goals-strip batch is committed as
  24a5dd0, pushed to main, and published. Release run 40997112296 is green
  (unit + renderer gate BUILD SUCCESSFUL, then build/sign/publish); the rolling
  `latest` now carries versionName 1.0.202610300324, versionCode 1793330693,
  AIxodia-version.json commit == 24a5dd027fd817a7895fed37fd163e9a094ce061, and
  the downloaded AIxodia.apk verifies as the stable signer CN=AIxodia
  (SHA-256 652f0b4ae06b92cfbed47df335d1a7216b9e54640ae41c0aa26ae66f0be849a6),
  package com.tulipskun.aixodia, minSdk 26. Not verified on a handset: whether
  15% feels like the right band, and whether the terminal window's edge drag
  survives its AndroidView, are both operator-screen questions.
- 2026-10-30 (this host): the container changed under the work — it is x86_64
  now and had no Android SDK. Installed one at /opt/android-sdk (cmdline-tools
  19.0, platform 35, build-tools 35.0.0, platform-tools) and the suite runs here
  for real on JDK 21. Two things that look like project failures and are not:
  (a) AGP's JdkImageTransform dies because this sandbox's JVM prints a cgroup
  warning on **stdout**, which AGP feeds into `jmod --module-version` — run
  gradle with `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport"` and it disappears;
  (b) the old "16 ARM renderer initialization failures" no longer apply on this
  host, which is why the count below is the whole suite passing. Toolchain line:
  `ANDROID_HOME=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  JAVA_TOOL_OPTIONS=-XX:-UseContainerSupport ./gradlew --no-daemon ...`
- 2026-10-30T01:59Z: the silent-stream fix is committed as c4b2646, pushed to
  main, and the release workflow cut the rolling `latest` at it — run
  40988922701 green, retained x86 reports hold 353 tests, 0 failures, 0 skipped
  (RunSocketStreamTest's three cases included), versionName 1.0.202610300159,
  versionCode 1793325576, APK 20,874,070 bytes, AIxodia-version.json names the
  pushed SHA. Not reproduced on a phone: no handset and no KVM here, so the
  operator's next hung turn is the first real test of the recovery path.
- Deliberately left alone while fixing this: ChatViewModel's silence watcher is
  disarmed for the rest of a turn by its very first frame (`turnHeard`), so it
  only ever unsticks a never-acked turn. That is the documented intent — a slow
  provider is legitimately frame-silent while it thinks, and the transport fix
  above now catches the case that actually hung. Tightening that watcher needs a
  separate "still connected, but stalled" ceiling, not a shorter timeout.
- 2026-10-29 (display/logic review): baseline Paparazzi failures are a host
  architecture mismatch: layoutlib-runtime-14.0.11-linux ships an AMD64 native
  library that cannot load on this AARCH64 JVM. Do not hide or re-record these
  failures. Add deterministic Markdown, accounting and coroutine regressions;
  a test-owned repository scope permits exercising real reads without leaks.
- Regression validation in progress: compile and run the new chat-state,
  Markdown and token-accounting tests before applying behavioral fixes.
  Application compilation passed; the first run exceeded its two-minute
  command limit before test compilation. Retrying without that short limit.
- Added real-repository regressions for cancelled cache/server reads,
  cancelled session selection and sync, and failed older-page reads. These
  exercise ChatRepository with mocked DAOs/transport and a test-owned scope.
- Focused baseline confirmed: 53 tests ran, with 22 meaningful failures
  (11 chat-state, 7 Markdown, 3 token-accounting, 1 percentage overflow).
  Running the 13 repository regressions against unchanged read behavior next.
- Applying the independently reproduced Markdown/accounting fixes: preserve
  fence lengths, blank code lines and escaped table pipes; size the table's
  scrollable content including all gaps/padding; use the explicit cache-input
  convention and Long arithmetic for cache percentages.
- Repository baseline: 12 cancellation cases reproduce swallowed cancellation
  (some then reach unintended transport/pruning work); the ordinary-I/O case
  had an invalid checked-exception Mockito stub, corrected to throw from its
  suspendable answer. Critical cache/recovery reads will now propagate failure,
  and best-effort work will catch ordinary errors without swallowing cancellation.
- Validating the applied Markdown/accounting and repository/history patches
  together (six focused suites), before changing chat ownership or draft logic.
- Focused result: 65 tests, 63 passed. All Markdown, geometry, accounting and
  repository cancellation tests pass. Two real-history tests received the
  expected exception type/message but rejected coroutine stacktrace-recovery
  copies by identity; assert the original cause remains in the chain instead.
- Adding recovery/read-failure and same-chat stale-result regressions before
  applying the remaining view-model fixes.
- Running the expanded chat-state baseline plus the corrected real-history
  assertions. ChatViewModel behavior is still unchanged at this point.
- The expanded baseline has compiled and is executing the old behavior; it
  already reproduces stale paging/route results, cross-chat retry, startup
  override, attachment cleanup and cached-answer handover failures. Applying
  chat/turn ownership guards while that compiled baseline finishes.
- Live-block scheduling now captures the turn before launching; every mutation
  checks that identity under the existing mutex, so teardown invalidates queued
  work without undoing the earlier duplicate-key protections.
- Startup, recovery and cached-answer handover are being changed to require
  successful reads and matching ownership; unknown history is never treated as
  a finished answer or as permission to retry a different chat.
- Send/retry/model operations now capture their originating chat before the
  first suspension. Success, failure and attachment cleanup will update only
  that visit/turn; draft text is acknowledged only after repository acceptance.
- Navigation has one latest-request owner, including startup and New chat.
  Draft creation/session discovery will no longer select the transport as a
  side effect; only an accepted navigation may select and persist its chat.
- Older-page requests will own both their result and loading cleanup, including
  failure and cancellation, so a departed request cannot exhaust a later visit.
- Operator now explicitly requests commit + push + release for each completed
  batch so progress can be checked on a phone. Publish only build-validated app
  changes; keep the ARM Paparazzi blocker visible. No engine deploy requested.
- Correcting fixture restubs that invoked their own suspended old answer; use
  doReturn for replacements so the stale-result regressions can finish rather
  than waiting on the very response the test has yet to complete.
- Running the ownership patches through chat-state, real-history and repository
  regressions on JDK17/ARM64. Inspecting the release workflow and working tree
  before publishing the operator-requested progress build.
- The current app has `.github/workflows/release.yml`: a push to main already
  builds, signs and replaces `latest`. Use that single release writer, verify
  its final APK/version metadata, and avoid racing it with a local publisher.
- Ownership/history validation passed: 41 tests (24 chat-state, 4 real-history,
  13 repository), BUILD SUCCESSFUL in 2m48s. The corrected suspended fixtures
  complete normally. Running the complete suite and assembleDebug with
  --continue so the known native renderer failures stay visible while the APK
  is still built. Publish this verified batch before the remaining screen work.
- Add full unit/renderer checks and retained reports to the existing x86
  release workflow, before publishing. This supplies compatible renderer
  evidence without skipping tests or re-recording snapshots on the ARM host.
- Next batch: wire per-chat drafts/acceptance into ChatScreen, count the goals
  card in paging geometry and release failed anchors for retries. Also add a
  real RunSocket reducer regression: handle(run, ...) ignores the run argument
  and tags an event with mutable frameSession(), allowing a departed SSE reader
  to label old events with the newly selected chat. Fix only after reproduction.
- Full local suite finished its tests: 307 ran, 291 passed; the same 16
  renderer-initialization failures remain, with no logic/test regressions.
  APK assembly is continuing. Prepare the next RunSocket regression separately
  and leave it out of the first release until it has been executed and fixed.
- First batch local validation complete: assembleDebug produced the APK.
  The combined command correctly exits nonzero for the 16 known ARM renderer
  failures; all other 291 of 307 tests pass. Commit the Markdown/accounting,
  repository/history and 24-test view-model ownership fixes plus the x86 CI
  test gate. The unexecuted RunSocketOwnershipTest belongs to the next batch
  and is deliberately excluded from this commit/release.
- First batch committed as da240f5. Push it to main to run the x86 test gate
  and signed rolling release; verify the workflow and uploaded version asset
  before saying it is available. Reproduce RunSocket ownership in parallel,
  against the still-unchanged transport, for the next focused batch.
- da240f5 pushed successfully to main. The locally built APK verifies as the
  stable CN=AIxodia signer (SHA-256 652f0b4ae06b92cfbed47df335d1a7216b9e54640
  ae41c0aa26ae66f0be849a6). Discover the matching release run and watch it once,
  rather than assuming a successful push means a published APK.
- The RunSocket baseline first hit fixture compilation issues (a runTest
  wrapper incorrectly marked suspend, and an overloaded Android API matcher).
  Correct those fixtures without changing the transport, and add draft,
  send-eligibility and paging regressions before the second behavior patch.
- Extract the existing composer/header calculations unchanged for a focused
  baseline. Offline text and uploading eligibility, and goals-card offsets,
  must fail before their fixes rather than being asserted only after editing.
- First release workflow 40976296563 completed successfully for the full
  da240f5b5a1a6f09bfbf49b86e2a96b93e21eac9 commit. Download its retained test
  reports and actual release assets to verify counts, provenance and signature.
- Running the second focused baseline: unchanged RunSocket reducer, 16 draft
  regressions, composer eligibility, paging geometry and the 24 existing
  chat-state checks. Only the calculations were extracted; behavior is not
  fixed yet. No Room schema/database migration is planned for these fixes.
- Release assets downloaded successfully. The report download hit gh's invalid
  inherited /data/local/tmp location; retry with TMPDIR=/tmp/opencode rather
  than changing any tests or host directories. Verify the published APK itself.
- Published first progress build verified: versionName 1.0.202610292357,
  versionCode 1793318243; its APK manifest and version asset both name the
  da240f5 build, with the expected CN=AIxodia stable signer. The x86 artifact
  includes passing renderer suites and eight rendered PNGs; aggregate the
  actual XML counts rather than assume they match locally cached test outputs.
- Second-batch edits in place: goals card counted in paging headers, send
  eligibility allows offline text but never offline files/uploads/busy, a
  surviving draft route is re-pinned after a successful promotion, and
  RunSocket events/stop are gated by the run and chat that own them.
- Published d90020d build verified: versionName 1.0.202610300025,
  versionCode 1793319912, version asset commit d90020d5ab8ae81f4b4ced10
  1128903c97d76949, signer CN=AIxodia SHA-256 652f0b4ae06b92cfbed47df335d1a
  7216b9e54640ae41c0aa26ae66f0be849a6. The x86 runner's retained reports
  hold 348 tests, 0 failures, 0 skipped, so the goals-card, eligibility,
  draft-route, ownership and draft wiring all render and pass there too.
- The crash itself is still unexplained: this build does not claim to fix it.
  CrashLog only exists from d90020d onward, so the operator must install this
  APK and then reproduce once; the NEXT occurrence will leave
  filesDir/last-crash.txt, readable from Settings -> About -> Share last crash
  log, and that stack is what the follow-up fix follows.
- ChatScreen reads the per-chat draft from `vm.drafts`, sends through
  `sendDraft()` (clear after acceptance only), consumes the persisted selection
  via `openSavedChat()` instead of re-opening its own echo, and draws the
  header title and drawer highlight from `activeSession`. The focused batch
  passes: RunSocketOwnershipTest, ChatViewModelDraftTest, ThreadPagingTest,
  ComposerSendEligibilityTest and ChatViewModelStateTest all green on JDK17
  (3m13s, BUILD SUCCESSFUL).
- Crash trace received (vivo I2405, Android 16): IndexOutOfBounds at
  ChatScreen.kt:961, `ArrayList.get(0)` on length 0 inside a LazyColumn item.
  Root cause: the lazy interval content re-reads `liveBlocks` (a delegated
  State read) whenever the snapshot changes, but `liveKeys` was computed by
  `remember(liveBlocks)` during composition. A block that lands between the
  two — exactly what send does — makes the interval non-empty while the
  remembered keys are still empty, and `liveKeys[index]` kills the process.
  This is why serializing writes (3ce0de1) never helped: the crash is the
  tear, not a lost update. Fix: one `liveRows(liveBlocks)` read inside the
  content lambda returns key+block pairs, so no parallel list can go stale;
  `messages` is captured once per rebuild as `rows` for the same reason.
  Focused tests (LiveKeys, Draft, State) green after the change. Full local
  run: 350 tests, 334 pass, the same 16 ARM renderer initialization failures.
- Add a regression for `liveRows`: one row per block, stable distinct keys,
  and no positional list to index into. LiveKeysTest covers the pairing.
- The operator's x86 box runs the full suite for real: 350 tests, 0 failures,
  0 skipped, 8 Paparazzi PNGs produced. JDK 21 + SDK 35 installed there; the
  repo is cloned at b10d3e3 and builds with `JAVA_HOME=...java-21-openjdk-amd64
  ANDROID_HOME=$HOME/android-sdk ./gradlew --no-daemon :app:testDebugUnitTest`
  in 1m41s. Use it for renderer verification only: the release signing key
  stays on this host and is never copied there.
  commit c33f312d53a719d1c2616af955755b03d93cab14, signer CN=AIxodia
  (SHA-256 unchanged). Workflow 40982082647 green; its retained x86 reports
  hold 350 tests, 0 failures, 0 skipped. The fix is proven against the exact
  frame the trace named, but not yet on the operator's phone — that install is
  the remaining verification.
  ("แอพเด้งหลังจากกดส่งข้อความ"), with no stack trace supplied. Treat this as a
  new reproduction requirement: keep the verified second-batch fixes, wire the
  per-chat draft/acceptance path, and ship durable crash capture (file + share
  from Settings) so the next occurrence names the exact frame. Do not claim the
  crash is fixed without evidence.
- Baseline for the second batch reproduced 12 real failures: 5 RunSocket
  ownership (old reader labels frames with the newly selected chat), the failed
  model-pin retry, 3 goals-card paging offsets, and 3 composer eligibility
  rules. Fix those behind the existing tests, then re-run.
- Deliberate deviation, recorded rather than silently dropped: "off-main
  terminal parse" became chunked-with-yield parsing ON the main thread
  (TerminalPanel WRITE_CHUNK slices + yield()). emulator.write feeds the view
  directly, so moving it off-main would need a render-buffer handoff; the
  stutter fix (frames land while a build log scrolls) is achieved without it.
- The thinking picker offers the four levels all current providers accept
  (GenerationSettings.thinkingLevels); ModelView.thinkingLevels exists for the
  daemon to override per model, but the Rust engine reports only
  supports_streaming/supports_tools today (api.rs models view). Engine-side
  capability reporting is a separate deploy, deliberately not bundled into
  this app release.
- Evidence is source-level: no device/emulator here, and app/src/test/snapshots
  holds no committed PNGs, so no rendered capture was re-verified.
- Build and tests are the only runtime proof available; every fix below is
  compile- and unit-test-verified, never screenshot-verified.

[COMPLETED] Chat switching is store-first, like Discord/Telegram (operator:
            "ไม่เห็นจะดีขึ้นเลย ข้อมูลทุกอย่างเหมือนรอจาก ws อย่างเดียว ไม่มีการ
            เก็บไว้ใช้ซ้ำ การสลับแชทไปมาก็แสดงผลไม่ถูกต้อง" — the switch still
            showed the wrong thread because the app kept a single chat-shaped
            pile of live state and tore the window down before reading the new
            one. Now the id + floor are ONE value (ChatWindow) adopted in a
            single emission, live turn cleared on every switch:
            - Atomic window: activeId and windowFloor were two flows. The
              sessionId setter nulled the floor before flipping the id, so
              messages emitted an empty list until the local read finished —
              and if the switch job was cancelled mid-read the floor stayed
              null and the thread never filled again. ChatViewModel now holds
              ChatWindow(sid, floor); storedRows flatMaps it and switchChat
              (suspend read, non-suspend adopt) swaps both at once. A cancelled
              switch leaves the previous chat whole.
            - Stale live turn removed: busy, liveText, liveBlocks, reasoning,
              usage, errorCard, turnStats, turnBaseSeq (-1) and pending
              attachments are cleared by adoptWindow. Before, chat A's stream
              drew in chat B and threadRows filtered B's rows by A's base seq.
            - Cross-chat rows cannot paint: the flatMapLatest flow holds the old
              chat's rows for the few ms of the new Room read, so threadRows now
              takes the on-screen sid and drops anything from another chat
              (tested). hasOlder/olderExhausted/loadingOlder reset per window.
            - Paths converted: openChat, newChat, deleteChat, dropDeadChat, cold
              start (cache page, then ensureSession only if the chat changed).
              refresh() no longer re-anchors — Room writes reach the live
              window, so pages scrolled in are kept. Attachments picked in one
              chat no longer land in another (attach() pins its sid).
            WS stays a send/update channel: frames are persisted to Room by
            sessionId regardless of what is on screen, and the thread is drawn
            from Room (observeFrom) only. assembleDebug +
            :app:testDebugUnitTest BUILD SUCCESSFUL (JDK17 + /opt/android-sdk;
            ThreadRowsTest +3 switch/floor tests).
            NOT verified on a phone — no device and no KVM on this host.
[COMPLETED] Chat thread made correct, smooth and alive (operator report:
            "การแสดงผลแชทไม่ถูกต้องอย่างรุนแรง ค้างกระตุก บางครั้งเกิดการ crash"
            — the display badly wrong, freezing/stuttering, sometimes crashing.
            Four defects, one per symptom, none of them the delta coalescing
            fixed last time:
            - Drawn twice / reshuffled: the engine stores a turn's narration and
              every tool step as it happens, so those rows reached Room while the
              live timeline was still showing them — three tool rounds read as
              six cards. ChatViewModel turns turnBaseSeq into a flow and the
              thread hides the rows the in-flight turn is writing
              (threadRows: seq > base, the question itself excepted); the
              hand-over watcher now reads storedRows, not the drawn list, or the
              withheld rows would have kept the turn busy forever. Block.TEXT
              upserts by block id instead of appending.
            - Crash 1 (nested scroll): ThoughtPill's opened reasoning body was a
              second vertical scroller — heightIn(320) + verticalScroll — inside
              the thread's own LazyColumn. Compose refuses same-axis nesting and
              throws on the frame the pill opens. The cap and the inner scroll
              are gone; the thread scrolls and the header closes it back up.
            - Crash 2 (duplicate key): a lazy list dies on a repeated key. Live
              items were keyed by the millisecond they arrived (two blocks in one
              ms = one key twice) and stored rows by the engine's messageId,
              which reconcile hands to two rows when it moves a local row off a
              seq D1 owns. uniqueLiveKeys and distinctRowIds now name every item
              once (pure, tested: LiveKeysTest, RowKeysTest).
            - Stutter: every streamed token rebuilt the whole visible page,
              because itemsIndexed passed a fresh onCopy lambda per row — so
              MessageBlock could not be skipped and re-ran Block.parse, and
              MarkdownText re-ran its inline regex over every block on screen,
              twice a second, on finished rows too. One remembered copyMessage is
              handed to every row, MessageBlock takes (ChatMessage) -> Unit, and
              inline styling is cached through rememberInline on styles that are
              themselves remembered. The tail is now pinned by pixels
              (dispatchRawDelta on the last item's overflow) instead of restarting
              animateScrollToItem per flush — that chase was the shudder.
            assembleDebug + :app:testDebugUnitTest BUILD SUCCESSFUL on JDK17 +
            /opt/android-sdk (ThreadRowsTest, LiveKeysTest, RowKeysTest new).
            NOT verified on a phone: this host has no device and no KVM, so the
            operator's screen is the first real render of these changes.
[COMPLETED] Goals on screen, compaction made visible, and the live thread
            corrected (paired with engine v1.160):
            - GoalsCard: the chat shows this session's goals and plans in three
              sections — in progress ○, completed ✓, dropped ✕ (struck through).
              HistoryApi.objectives reads the engine's new
              GET /v1/sessions/:id/objectives; the ViewModel refreshes it on
              open/switch, at turn end, and 400ms after any goal_/plan_ tool
              result so the D1 write has landed. The collapsed card is one line
              with the open count.
            - CompactMarker: when the engine trims the prompt it now sends a
              tool_event stage "compact" (kept_turns, dropped_turns, one digest
              line per dropped turn). RunFrames maps it to a trace frame and the
              live thread draws "Context trimmed — kept N turns, dropped M",
              expandable to the list of what fell out.
            - Stream lag: answer deltas no longer restate the bubble per token;
              they coalesce on a 120ms timer (flushed before any tool card or
              final message takes the text, discarded when the turn clears).
            - Replay order: live items key by block/step id (atMs for prose)
              instead of position, so a settling thread stops reshuffling;
              mergeToolPairs now pairs call↔result by the call id the stored
              block carries (positional fallback for pre-block rows), so two
              parallel runs of one tool no longer merge crosswise.
            - Tool cards: arguments render through humanToolArgs (the command,
              not the raw JSON); failures read block.is_error instead of
              sniffing the text for `{"error"`; result text is no longer cut at
              160 chars.
            assembleDebug + :app:testDebugUnitTest BUILD SUCCESSFUL on JDK17
            (new ToolMergeTest cases for id pairing).
[COMPLETED] Move the terminal's window title into the drawer, off the shell:
            the engine (v1.159) reports #{pane_title} per session in
            /v1/terminals; TerminalSocket.syncSessions folds it into
            sessionTitles and TerminalSidebar shows a "Window title" card
            under the header. The operator's "[host]:bash … clock" bar was
            tmux.conf status-left/right — already gone because tmux.rs sets
            status off per session. assembleDebug + :app:testDebugUnitTest
            BUILD SUCCESSFUL on JDK17 + /opt/android-sdk (installed this
            host). Shipped: baf005a pushed, scripts/release.sh published the
            rolling `latest` (signed CN=AIxodia); engine v1.159 self-updated
            in place (pid 1354).
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
            Paparazzi included. Shipped 258e2a5, scripts/release.sh cut the
            rolling `latest` at 10:32:10Z (versionName 1.0.202610281032,
            versionCode 1793183530, APK 20,802,664 bytes,
            md5 39b15ef997949372993291f7f88b22ee, signature CN=AIxodia,
            AIxodia-version.json commit == HEAD).
[COMPLETED] terminal: copy now survives full-screen boxes. When every
            selected row belongs to a box — a vertical border after the
            layout indent, a rule of box drawing, or a blank row — the copy
            is the card's content: borders and the centering padding are
            chrome and are dropped, content rows keep their own breaks, and
            blank rows stay blank. A URL split by the box joins as one token:
            the block reflow no longer puts a space between a line with no
            spaces that carries a scheme and the next unspaced line (or a
            query string continued with key=value), and the card join uses
            the same test. The reported Cline sign-in box now yields
            https://authkit.cline.bot/device?user_code=XNXG-RCNZ while the
            "Waiting for sign-in..." row keeps its own line. A paragraph the
            program wrapped inside a card is not reflowed (each card row is
            padded on its own, so there is no cut column to trust), and a
            selection that mixes a card with text outside it copies as laid
            out. TerminalSelectionTest gained aLinkBrokenAcrossLinesIsNotSpaced
            and aCardCopiesAsItsContent (a full ╭─╮ box from a helper).
            :app:testDebugUnitTest BUILD SUCCESSFUL, 227 tests, Paparazzi
            included. Shipped f99ba8f, scripts/release.sh cut the rolling
            `latest` at 17:53:53Z (versionName 1.0.202610281753, versionCode
            1793210033, APK 20,802,668 bytes, md5
            09307558116fa92ef702db61832696f8, signature CN=AIxodia,
            AIxodia-version.json commit == HEAD).

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
[COMPLETED] TerminalKeys: the arrow cluster comes off the right edge. left,
down and right take the lower row's 3rd/4th/5th slots and the up arrow the upper
row's 4th, directly above down — a D-pad centred under the thumb rather than
jammed against the screen's edge — and Shift moves to the lower row's far right
end, since it only ever pairs with Tab and is wanted far less than Ctrl or Alt.
(operator: "ปุ่มลูกศรอยู่ชิดขวาเกินไป ให้อยู่ตรงกลางเยื้องขวา")
Six keys per row and all four arrow payloads (ESC[A / ESC[D / ESC[B / ESC[C])
verified unchanged after the move. NOT compiled here: this host carries only
JDK 21 and no Android SDK 35, and `MAIN.md` records that the build dies under
JDK 21 in :app:compileDebugJavaWithJavac — so `:app:testDebugUnitTest` and
assembleDebug still have to run where the SDK lives.
[COMPLETED] TerminalKeys grows to two rows of seven (operator: "esc tab cmd
page-up up page-down del-all / shift ctrl alt left down right exec"). Page keys
are new: PgUp sends ESC[5~ and PgDn ESC[6~, both holdable and repeating the way
the arrows are, flanking the up arrow so the navigation cluster reads
page-up / up / page-down over left / down / right with up still directly above
down. Modifier order becomes
Shift, Ctrl, Alt on the left of the lower row; the whole-line erase and the
fresh-shell key take the right edge, one per row. The 7th column costs touch
size: 7 equal weights with no side padding and no gap run the full width, the way
Termux's extra-keys row does: 51.4dp per key at 360dp (56.1 at 393, 58.7 at 411,
68.6 at 480). Every dp goes to the keys — the chips meet edge to edge, so a
near-miss fires the key beside it rather than landing in a gap, only the rounded
corners mark one key from the next, and the end keys sit flush on the screen edge.
  Published as 1.0.202610301908, versionCode 1793387283, APK 20,890,454 bytes;
  `latest` == 6723500. Four pushes went up in a row, so the workflow published
  each in turn before the last one took `latest`.
[COMPLETED] TerminalKeys' look, judged from a render instead of from the spec.
             The commit before this one shipped the M3 press morph with rounded
             keys at a 0 gap, and the operator asked the fair question: was the
             bar ever rendered before being called pretty? It had not been, so
             the toolchain was rebuilt on this host (JDK 17 at
             /usr/lib/jvm/java-17-openjdk-amd64, Android SDK 35 + build-tools
             35 at /root/android-sdk) and KeysSnapshot renders the bar with
             Paparazzi at PIXEL_5. What the first frame showed: with 8dp corners
             and no gap, every pair of neighbours left a dark diamond notch at
             the row seam, the two rows read as one undifferentiated block, and
             sixteen InkLine boxes were heavier than the grid above them. Termux
             was then fetched and looked at: its extra-keys row draws no boxes at
             all, just labels on the terminal's own background. So a key at rest
             is transparent with only its label; a press lays down M3's 10% state
             layer (#1F2429 over Ink) with a 120ms fade and dips to 96% on a
             no-bounce spring; a latched Ctrl/Alt/Shift keeps its fill but inset
             2dp inside the cell, so two latched keys side by side stay two keys
             instead of merging into one wide pill. A Dp.Hairline rule plus 6dp
             now owns the boundary with the terminal, labels are labelLarge, the
             arrow glyphs run 22sp (Roboto's arrows draw small at label size) and
             the two icons 16dp. Holdable keys get their pressed state from the
             same interaction source the gesture emits into (tryEmit, since
             AwaitPointerEventScope cannot suspend into emit). KeysSnapshot and
             its report are committed as a visual check for later changes.
             Verified: 376 unit tests, 0 failures; assembleDebug green.
             Known limits: Paparazzi renders one frame and 1.3.5 has no gif
             writer, so the spring/ripple were checked at their end state, not
             in motion; layoutlib is not a handset.

NOTE:
- 2026-10-28T23:46Z: the store-first switch is committed as fc1764b (+ docs
  46164db), pushed to main, and scripts/release.sh cut the rolling `latest`
  at it (versionName 1.0.202610282346, versionCode 1793231166, APK
  20,838,716 bytes, signature CN=AIxodia, AIxodia-version.json commit ==
  HEAD). Still not seen on a device — no handset and no KVM, so the
  operator's screen is the first real render.
- 2026-10-28 (switch fix): unit tests green (:app:testDebugUnitTest, 34 suites
  incl. ThreadRowsTest now 8 tests) and assembleDebug BUILD SUCCESSFUL. Not
  seen on a device: no handset, no KVM; the operator's screen is the first
  real render. Switching now costs one local Room read before the chip
  flips — the previous chat stays on screen during those milliseconds rather
  than blanking, which is the store-first trade the report asked for.
- Still open (unchanged): the resume path (app reopened mid-turn) sets no
  turnBaseSeq, so a replayed stream can sit beside stored rows; choosing a
  base there risks hiding rows the replay never covered.
- 2026-10-28T23:14Z: the thread fix above is committed as 515e9ec, pushed to
  main, and scripts/release.sh cut the rolling `latest` at it (versionName
  1.0.202610282313, versionCode 1793229184, APK 20,838,712 bytes, signature
  CN=AIxodia, AIxodia-version.json commit == HEAD). Phones on the old build are
  offered this one by the in-app checker. Still not seen on a device — no
  handset and no KVM on this host, so the operator's screen is the test.
- Also still open from last session's diagnosis, deliberately not touched here:
  the resume path (app reopened mid-turn) sets no turnBaseSeq, so a replayed
  stream can still sit beside stored rows. Choosing a base there risks *hiding*
  rows the replay never covered, which is worse than showing them twice.
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
