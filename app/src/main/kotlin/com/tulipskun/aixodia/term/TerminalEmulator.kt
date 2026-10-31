package com.tulipskun.aixodia.term

/**
 * The terminal modes a program can set, kept so they can be put back when the
 * terminal reattaches to a session that is already running.
 *
 * The engine only replays the tail of a session's output. A full-screen program
 * (opencode, vim) turned on its mouse, alternate screen and cursor keys when it
 * started — long before the tail — so without this the fresh emulator would
 * think it is at a plain prompt and scroll its own history instead of the
 * program's.
 */
data class TerminalModes(
    val mouseTracking: Int = 0,
    val mouseSgr: Boolean = false,
    val mouseUtf8: Boolean = false,
    val alternate: Boolean = false,
    val applicationCursorKeys: Boolean = false,
    val bracketedPaste: Boolean = false,
    val cursorVisible: Boolean = true,
    val fullScreen: Boolean = false,
)

/**
 * A VT/ANSI terminal emulator, written here so the app owns it outright: the
 * bytes from the shell go in, a grid of cells comes out, and a small view draws
 * it. There is no dependancy to match a version against and no web engine in
 * the way of the keyboard or the finger.
 *
 * The interface is deliberately small: [write] feeds output, [resize] changes
 * the grid, and the rest is read by the renderer (the active [buffer], the
 * cursor, and the modes that decide how a key or a click is encoded).
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    private val output: TerminalOutput,
    scrollbackLimit: Int = 5000,
) {
    var columns = cols
        private set
    var rows = rows
        private set

    private val normal = TerminalBuffer(cols, rows, scrollbackLimit).apply {
        keepScrollback = true
        setDefaults(TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG)
    }
    private val alternate = TerminalBuffer(cols, rows, 0).apply {
        keepScrollback = false
        setDefaults(TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG)
    }

    /** The buffer the renderer should draw. */
    var buffer: TerminalBuffer = normal
        private set

    /** True while a full-screen program has the alternate screen up. */
    val alternateScreen: Boolean get() = activeAlternate

    /**
     * True once the output has painted cells anywhere but the top-left, which
     * is what a full-screen program does and a shell prompt does not. It is
     * used when the session was attached mid-stream and the mode sequences that
     * would have told us "this is a TUI, the mouse is on" are no longer in the
     * replay.
     */
    var fullScreenDetected = false
        private set

    // ---- current pen -------------------------------------------------------
    private var fg = TerminalColors.DEFAULT_FG
    private var bg = TerminalColors.DEFAULT_BG
    private var attr = 0

    // ---- modes -------------------------------------------------------------
    var autoWrap = true
        private set
    var originMode = false
        private set
    var insertMode = false
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var cursorStyle = 0
        private set

    /** 0 none, 1 X10, 2 VT200, 3 DRAG, 4 ANY. */
    var mouseTracking = 0
        private set
    var mouseSgr = false
        private set
    var mouseUtf8 = false
        private set

    /** Window title, set by OSC 0/2. */
    var title: String = ""
        private set

    // Save/restore cursor (DECSC/DECRC), one slot for the active screen.
    private var savedCol = 0
    private var savedRow = 0
    private var savedFg = TerminalColors.DEFAULT_FG
    private var savedBg = TerminalColors.DEFAULT_BG
    private var savedAttr = 0
    private var savedOrigin = false
    private var savedAutoWrap = true

    // Pending wrap: the cursor sits past the last column until the next glyph.
    private var wrapPending = false
    private var lastPrinted = 0

    private var tabStops = BooleanArray(cols) { it % 8 == 0 }
    private var activeAlternate = false
    private var absoluteCsiCount = 0

    // ---- parser state ------------------------------------------------------
    private var state = ST_GROUND
    private val params = IntArray(32)
    private var paramCount = 0
    private var paramFilled = 0
    private var privateMarker = 0
    private var intermediate = 0
    private val stringBuilder = StringBuilder()
    private val utf8 = IntArray(4)
    private var utf8Len = 0
    private var utf8Need = 0

    fun write(bytes: ByteArray, length: Int = bytes.size) {
        var i = 0
        while (i < length) {
            feed(bytes[i].toInt() and 0xFF)
            i++
        }
        output.onScreenChanged()
    }

    // =======================================================================
    // screen helpers
    // =======================================================================

    private fun row(): TerminalRow = buffer.lines[buffer.cursorRow]

    private fun index() {
        if (buffer.cursorRow == buffer.scrollBottom) {
            buffer.scrollUp(buffer.scrollTop, buffer.scrollBottom, 1, fg, bg)
        } else if (buffer.cursorRow < rows - 1) {
            buffer.cursorRow++
        }
        wrapPending = false
    }

    private fun reverseIndex() {
        if (buffer.cursorRow == buffer.scrollTop) {
            buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom, 1, fg, bg)
        } else if (buffer.cursorRow > 0) {
            buffer.cursorRow--
        }
        wrapPending = false
    }

    private fun carriageReturn() {
        buffer.cursorCol = 0
        wrapPending = false
    }

    private fun lineFeed() {
        index()
    }

    private fun backspace() {
        if (buffer.cursorCol > 0) buffer.cursorCol--
        wrapPending = false
    }

    private fun tab() {
        var c = buffer.cursorCol + 1
        while (c < columns && !tabStops[c]) c++
        buffer.cursorCol = if (c >= columns) columns - 1 else c
        wrapPending = false
    }

    private fun setCursor(col: Int, row: Int) {
        buffer.cursorCol = col.coerceIn(0, columns - 1)
        buffer.cursorRow = row.coerceIn(0, rows - 1)
        wrapPending = false
    }

    /**
     * The row the cursor is leaving soft-wrapped into the next one. Copy joins
     * the two halves back into one line; without the mark every screen row is
     * read as its own line and a long `ls` comes apart in the clipboard.
     */
    private fun markWrapped() {
        buffer.lines.getOrNull(buffer.cursorRow)?.wrapped = true
    }

    private fun printCodePoint(cp: Int) {
        val w = WcWidth.width(cp)
        if (w == 0) {
            // A combining mark joins the cell the cursor last left. Thai is
            // built this way, so dropping it would break the text outright.
            if (buffer.cursorCol > 0 || wrapPending) {
                val col = if (wrapPending) columns - 1 else buffer.cursorCol - 1
                if (col in 0 until columns) row().appendCombining(col, cp)
            }
            return
        }
        if (wrapPending) {
            markWrapped()
            carriageReturn()
            index()
            wrapPending = false
        }
        if (insertMode) buffer.insertChars(buffer.cursorRow, buffer.cursorCol, w, fg, bg)
        val line = row()
        // A wide glyph will not fit in the last column: wrap it rather than
        // split it, the way a terminal does.
        if (w == 2 && buffer.cursorCol == columns - 1) {
            if (autoWrap) {
                markWrapped()
                carriageReturn()
                index()
            } else {
                return
            }
        }
        val target = row()
        target.setChar(buffer.cursorCol, cp, w, fg, bg, attr)
        lastPrinted = cp
        buffer.cursorCol += w
        if (buffer.cursorCol >= columns) {
            buffer.cursorCol = columns - 1
            if (autoWrap) wrapPending = true
        }
    }

    // =======================================================================
    // parser
    // =======================================================================

    private fun feed(byte: Int) {
        when (state) {
            ST_GROUND -> ground(byte, utf8 = true)
            ST_ESC -> escape(byte)
            ST_ESC_INTERMEDIATE -> if (byte in 0x20..0x2F) { /* keep ignoring */ } else escape(byte)
            ST_CSI_ENTRY, ST_CSI_PARAM, ST_CSI_INTERMEDIATE -> csi(byte)
            ST_CSI_IGNORE -> if (byte in 0x40..0x7E) state = ST_GROUND
            ST_OSC -> osc(byte)
            ST_OSC_ESC -> { if (byte == '\\'.code) state = ST_GROUND else state = ST_OSC }
            ST_STRING -> if (byte == 0x07) state = ST_GROUND else if (byte == 0x1B) state = ST_STRING_ESC
            ST_STRING_ESC -> state = if (byte == '\\'.code) ST_GROUND else ST_STRING
        }
    }

    private fun ground(byte: Int, utf8: Boolean) {
        when {
            byte == 0x1B -> { resetSeq(); state = ST_ESC }
            byte < 0x20 -> control(byte)
            byte < 0x7F -> { flushUtf8(); printCodePoint(byte) }
            byte == 0x7F -> { /* DEL ignored */ }
            else -> decodeUtf8(byte)
        }
    }

    private fun control(byte: Int) {
        when (byte) {
            0x07 -> { /* BEL: nothing to ring */ }
            0x08 -> backspace()
            0x09 -> tab()
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> carriageReturn()
            0x0E, 0x0F -> { /* SO/SI: alternate charset, not used */ }
            else -> Unit
        }
    }

    private fun flushUtf8() {
        if (utf8Len > 0) { utf8Len = 0; utf8Need = 0 }
    }

    private fun decodeUtf8(byte: Int) {
        if (utf8Need == 0) {
            when {
                byte and 0xE0 == 0xC0 -> { utf8[0] = byte and 0x1F; utf8Need = 1 }
                byte and 0xF0 == 0xE0 -> { utf8[0] = byte and 0x0F; utf8Need = 2 }
                byte and 0xF8 == 0xF0 -> { utf8[0] = byte and 0x07; utf8Need = 3 }
                else -> { printCodePoint(byte); return } // stray continuation
            }
            utf8Len = 1
        } else if (byte and 0xC0 == 0x80) {
            utf8[utf8Len++] = byte and 0x3F
            utf8Need--
            if (utf8Need == 0) {
                var cp = utf8[0]
                for (i in 1 until utf8Len) cp = (cp shl 6) or utf8[i]
                printCodePoint(cp)
                utf8Len = 0
            }
        } else {
            // Malformed: drop the partial sequence and reprocess this byte.
            utf8Len = 0; utf8Need = 0
            ground(byte, utf8 = true)
        }
    }

    private fun resetSeq() {
        paramCount = 0
        paramFilled = 0
        privateMarker = 0
        intermediate = 0
        stringBuilder.setLength(0)
    }

    private fun escape(byte: Int) {
        when (byte) {
            '['.code -> { resetSeq(); state = ST_CSI_ENTRY }
            ']'.code -> { resetSeq(); state = ST_OSC }
            'P'.code, '_'.code, '^'.code, 'X'.code -> { resetSeq(); state = ST_STRING }
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> { index(); state = ST_GROUND }
            'E'.code -> { carriageReturn(); index(); state = ST_GROUND }
            'M'.code -> { reverseIndex(); state = ST_GROUND }
            'H'.code -> { tabStops[buffer.cursorCol] = true; state = ST_GROUND }
            'c'.code -> { reset(); output.onScreenChanged(); state = ST_GROUND }
            '('.code, ')'.code, '*'.code, '+'.code -> state = ST_ESC_INTERMEDIATE
            '#'.code, ' '.code, '%'.code -> state = ST_ESC_INTERMEDIATE
            '='.code -> { applicationKeypad = true; state = ST_GROUND }
            '>'.code -> { applicationKeypad = false; state = ST_GROUND }
            else -> state = ST_GROUND
        }
    }

    private fun parseParam(byte: Int) {
        if (byte == ';'.code || byte == ':'.code) {
            if (paramCount < params.size) paramCount++
            paramFilled = paramCount
        } else {
            if (paramCount >= params.size) return
            val v = if (params[paramCount] < 0) 0 else params[paramCount]
            params[paramCount] = (v * 10 + (byte - '0'.code)).coerceAtMost(65535)
            paramFilled = paramCount + 1
            if (paramCount + 1 > paramFilled) paramFilled = paramCount + 1
        }
    }

    private fun param(index: Int, default: Int): Int {
        if (index >= paramFilled) return default
        val v = params[index]
        return if (v < 0) default else v
    }

    private fun csi(byte: Int) {
        when {
            byte == 0x1B -> { resetSeq(); state = ST_ESC }
            byte < 0x20 -> control(byte)
            state == ST_CSI_ENTRY && byte in 0x30..0x3B -> {
                for (i in 0 until params.size) params[i] = -1
                paramCount = 0; paramFilled = 0
                state = ST_CSI_PARAM
                parseParam(byte)
            }
            state == ST_CSI_ENTRY && byte in 0x3C..0x3F -> {
                privateMarker = byte
                for (i in 0 until params.size) params[i] = -1
                paramCount = 0; paramFilled = 0
                state = ST_CSI_PARAM
            }
            byte in 0x20..0x2F -> { intermediate = byte; state = ST_CSI_INTERMEDIATE }
            byte in 0x30..0x39 -> { if (state == ST_CSI_ENTRY) state = ST_CSI_PARAM; parseParam(byte) }
            byte == ';'.code || byte == ':'.code -> { state = ST_CSI_PARAM; parseParam(byte) }
            byte in 0x40..0x7E -> { dispatchCsi(byte.toChar()); state = ST_GROUND }
            else -> state = ST_CSI_IGNORE
        }
    }

    private fun osc(byte: Int) {
        when (byte) {
            0x07 -> { handleOsc(stringBuilder.toString()); state = ST_GROUND }
            0x1B -> state = ST_OSC_ESC
            else -> if (stringBuilder.length < 4096) stringBuilder.append(byte.toChar())
        }
    }

    private fun handleOsc(s: String) {
        val sep = s.indexOf(';')
        val cmd = if (sep < 0) s else s.substring(0, sep)
        val rest = if (sep < 0) "" else s.substring(sep + 1)
        when (cmd) {
            "0", "2" -> { title = rest; output.onTitleChanged(rest) }
            "1" -> Unit
            "52" -> { // clipboard: 52;c;<base64>
                val parts = rest.split(';')
                if (parts.size >= 2 && parts[0].endsWith("c")) {
                    runCatching { java.util.Base64.getDecoder().decode(parts[1]) }
                        .onSuccess { output.onClipboard(String(it, Charsets.UTF_8)) }
                }
            }
        }
    }

    // =======================================================================
    // CSI dispatch
    // =======================================================================

    private fun dispatchCsi(final: Char) {
        when (final) {
            'A' -> setCursor(buffer.cursorCol, buffer.cursorRow - param(0, 1).coerceAtLeast(1))
            'B' -> setCursor(buffer.cursorCol, buffer.cursorRow + param(0, 1).coerceAtLeast(1))
            'C' -> setCursor(buffer.cursorCol + param(0, 1).coerceAtLeast(1), buffer.cursorRow)
            'D' -> setCursor(buffer.cursorCol - param(0, 1).coerceAtLeast(1), buffer.cursorRow)
            'E' -> { setCursor(buffer.cursorCol, buffer.cursorRow + param(0, 1).coerceAtLeast(1)); carriageReturn() }
            'F' -> { setCursor(buffer.cursorCol, buffer.cursorRow - param(0, 1).coerceAtLeast(1)); carriageReturn() }
            'G', '`' -> setCursor(param(0, 1) - 1, buffer.cursorRow)
            'd' -> setCursor(buffer.cursorCol, param(0, 1) - 1)
            'H', 'f' -> {
                val r = param(0, 1) - 1
                val c = param(1, 1) - 1
                setCursor(c, if (originMode) r + buffer.scrollTop else r)
                // A prompt occasionally parks the cursor, but a program that
                // keeps placing it further down the screen is painting a UI.
                if (r > 0 || c > 0) {
                    absoluteCsiCount++
                    if (absoluteCsiCount >= 3) fullScreenDetected = true
                }
            }
            'J' -> { buffer.eraseInDisplay(param(0, 0), fg, bg); wrapPending = false }
            'K' -> {
                when (param(0, 0)) {
                    1 -> buffer.eraseInLine(buffer.cursorRow, 0, buffer.cursorCol, fg, bg)
                    2 -> buffer.eraseInLine(buffer.cursorRow, 0, columns - 1, fg, bg)
                    else -> buffer.eraseInLine(buffer.cursorRow, buffer.cursorCol, columns - 1, fg, bg)
                }
                wrapPending = false
            }
            'L' -> buffer.insertLines(buffer.cursorRow, param(0, 1).coerceAtLeast(1), fg, bg)
            'M' -> buffer.deleteLines(buffer.cursorRow, param(0, 1).coerceAtLeast(1), fg, bg)
            'P' -> buffer.deleteChars(buffer.cursorRow, buffer.cursorCol, param(0, 1).coerceAtLeast(1), fg, bg)
            '@' -> buffer.insertChars(buffer.cursorRow, buffer.cursorCol, param(0, 1).coerceAtLeast(1), fg, bg)
            'X' -> buffer.eraseInLine(buffer.cursorRow, buffer.cursorCol, buffer.cursorCol + param(0, 1).coerceAtLeast(1) - 1, fg, bg)
            'S' -> buffer.scrollUp(buffer.scrollTop, buffer.scrollBottom, param(0, 1).coerceAtLeast(1), fg, bg)
            'T' -> buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom, param(0, 1).coerceAtLeast(1), fg, bg)
            'r' -> {
                val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                if (bottom > top) { buffer.scrollTop = top; buffer.scrollBottom = bottom }
                setCursor(0, if (originMode) top else 0)
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'g' -> when (param(0, 0)) {
                0 -> tabStops[buffer.cursorCol] = false
                3 -> tabStops = BooleanArray(columns)
                else -> Unit
            }
            'Z' -> repeatTimes(param(0, 1).coerceAtLeast(1)) {
                var c = buffer.cursorCol - 1
                while (c > 0 && !tabStops[c]) c--
                buffer.cursorCol = if (c < 0) 0 else c
            }
            'b' -> if (lastPrinted != 0) repeatTimes(param(0, 1).coerceAtLeast(1)) { printCodePoint(lastPrinted) }
            'm' -> sgr()
            'h' -> modes(set = true)
            'l' -> modes(set = false)
            'n' -> when (param(0, 0)) {
                5 -> respond("\u001b[0n")
                6 -> respond("\u001b[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R")
            }
            'c' -> when (privateMarker) {
                // A secondary DA (CSI > c) is not the same question as the
                // primary one. Answering it with the primary report left tmux
                // with a reply it was not waiting for, which came back as
                // keyboard input and echoed as "1;2c0;276;0c".
                '>'.code -> respond("\u001b[>0;276;0c")
                else -> respond("\u001b[?1;2c")
            }
            'q' -> if (intermediate == ' '.code) cursorStyle = param(0, 0)
            't' -> Unit
            else -> Unit
        }
    }

    private inline fun repeatTimes(times: Int, body: () -> Unit) {
        for (i in 0 until times) body()
    }

    private fun respond(s: String) {
        output.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun sgr() {
        if (paramFilled == 0) { resetSgr(); return }
        var i = 0
        while (i < paramFilled) {
            when (val p = param(i, 0)) {
                0 -> resetSgr()
                1 -> attr = attr or TerminalAttr.BOLD
                2 -> attr = attr or TerminalAttr.DIM
                3 -> attr = attr or TerminalAttr.ITALIC
                4 -> attr = attr or TerminalAttr.UNDERLINE
                5, 6 -> attr = attr or TerminalAttr.BLINK
                7 -> attr = attr or TerminalAttr.INVERSE
                8 -> attr = attr or TerminalAttr.INVISIBLE
                9 -> attr = attr or TerminalAttr.STRIKE
                21, 22 -> attr = attr and (TerminalAttr.BOLD or TerminalAttr.DIM).inv()
                23 -> attr = attr and TerminalAttr.ITALIC.inv()
                24 -> attr = attr and TerminalAttr.UNDERLINE.inv()
                25 -> attr = attr and TerminalAttr.BLINK.inv()
                27 -> attr = attr and TerminalAttr.INVERSE.inv()
                28 -> attr = attr and TerminalAttr.INVISIBLE.inv()
                29 -> attr = attr and TerminalAttr.STRIKE.inv()
                in 30..37 -> fg = TerminalColors.indexed(p - 30)
                39 -> fg = TerminalColors.DEFAULT_FG
                in 40..47 -> bg = TerminalColors.indexed(p - 40)
                49 -> bg = TerminalColors.DEFAULT_BG
                in 90..97 -> fg = TerminalColors.indexed(p - 90 + 8)
                in 100..107 -> bg = TerminalColors.indexed(p - 100 + 8)
                38 -> i = extendedColor(i, isForeground = true)
                48 -> i = extendedColor(i, isForeground = false)
                else -> Unit
            }
            i++
        }
    }

    /** Reads `5;n` or `2;r;g;b` starting at [index]; returns the last used index. */
    private fun extendedColor(index: Int, isForeground: Boolean): Int {
        val kind = param(index + 1, 0)
        return when (kind) {
            5 -> {
                val n = param(index + 2, 0)
                val c = TerminalColors.indexed(n)
                if (isForeground) fg = c else bg = c
                index + 2
            }
            2 -> {
                val c = TerminalColors.rgb(param(index + 2, 0), param(index + 3, 0), param(index + 4, 0))
                if (isForeground) fg = c else bg = c
                index + 4
            }
            else -> index
        }
    }

    private fun resetSgr() {
        fg = TerminalColors.DEFAULT_FG
        bg = TerminalColors.DEFAULT_BG
        attr = 0
    }

    private fun modes(set: Boolean) {
        if (privateMarker == '?'.code) {
            var i = 0
            while (i < paramFilled) {
                when (param(i, 0)) {
                    1 -> applicationCursorKeys = set
                    6 -> originMode = set
                    7 -> autoWrap = set
                    12 -> Unit
                    25 -> cursorVisible = set
                    47, 1047, 1049 -> switchAlternate(set, save = param(i, 0) == 1049)
                    1000 -> mouseTracking = if (set) 2 else 0
                    1002 -> mouseTracking = if (set) 3 else 0
                    1003 -> mouseTracking = if (set) 4 else 0
                    1005 -> mouseUtf8 = set
                    1006 -> mouseSgr = set
                    1015 -> Unit
                    1016 -> Unit
                    2004 -> bracketedPaste = set
                    else -> Unit
                }
                i++
            }
        } else {
            var i = 0
            while (i < paramFilled) {
                when (param(i, 0)) {
                    4 -> insertMode = set
                    else -> Unit
                }
                i++
            }
        }
    }

    private fun switchAlternate(on: Boolean, save: Boolean) {
        if (on == activeAlternate) return
        if (on) {
            if (save) saveCursor()
            activeAlternate = true
            fullScreenDetected = true
            buffer = alternate
            alternate.reset()
        } else {
            activeAlternate = false
            // The program that was painting a UI has finished; a swipe over the
            // prompt should scroll our own history again.
            fullScreenDetected = false
            absoluteCsiCount = 0
            buffer = normal
            if (save) restoreCursor()
        }
        wrapPending = false
    }

    private fun saveCursor() {
        savedCol = buffer.cursorCol; savedRow = buffer.cursorRow
        savedFg = fg; savedBg = bg; savedAttr = attr
        savedOrigin = originMode; savedAutoWrap = autoWrap
    }

    private fun restoreCursor() {
        buffer.cursorCol = savedCol.coerceIn(0, columns - 1)
        buffer.cursorRow = savedRow.coerceIn(0, rows - 1)
        fg = savedFg; bg = savedBg; attr = savedAttr
        originMode = savedOrigin; autoWrap = savedAutoWrap
        wrapPending = false
    }

    /** RIS: everything back to how a fresh terminal looks. */
    private fun reset() {        normal.reset()
        alternate.reset()
        buffer = normal
        activeAlternate = false
        fg = TerminalColors.DEFAULT_FG
        bg = TerminalColors.DEFAULT_BG
        attr = 0
        autoWrap = true
        originMode = false
        insertMode = false
        cursorVisible = true
        applicationCursorKeys = false
        applicationKeypad = false
        mouseTracking = 0
        mouseSgr = false
        mouseUtf8 = false
        cursorStyle = 0
        tabStops = BooleanArray(columns) { it % 8 == 0 }
        wrapPending = false
        fullScreenDetected = false
        absoluteCsiCount = 0
    }

    /**
     * Clears the screen and the history. A session switch replays the new
     * shell's screen from the engine, so the old one must not be underneath it.
     */
    fun hardReset() {
        reset()
        output.onScreenChanged()
    }

    // =======================================================================
    // resize
    // =======================================================================

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        columns = newColumns
        rows = newRows
        normal.resize(newColumns, newRows)
        alternate.resize(newColumns, newRows)
        tabStops = BooleanArray(newColumns) { it % 8 == 0 }
        buffer.scrollTop = 0
        buffer.scrollBottom = newRows - 1
        buffer.cursorRow = buffer.cursorRow.coerceIn(0, newRows - 1)
        buffer.cursorCol = buffer.cursorCol.coerceIn(0, newColumns - 1)
        wrapPending = false
        output.onScreenChanged()
    }

    // =======================================================================
    // input encoding (called by the view)
    // =======================================================================

    /** The arrow/Home/End form depends on DECCKM, set by the program. */
    fun cursorKey(final: Char, mods: KeyMods = KeyMods.NONE): ByteArray {
        val sequence = TerminalKeys.cursor(final, mods, applicationCursorKeys)
        return sequence.toByteArray(Charsets.US_ASCII)
    }

    /** True when the program asked for the mouse, so a tap should be a click. */
    fun mouseReporting(): Boolean = mouseTracking != 0

    /** The modes to restore if this session is reattached (see [TerminalModes]). */
    fun snapshotModes(): TerminalModes = TerminalModes(
        mouseTracking = mouseTracking,
        mouseSgr = mouseSgr,
        mouseUtf8 = mouseUtf8,
        alternate = activeAlternate,
        applicationCursorKeys = applicationCursorKeys,
        bracketedPaste = bracketedPaste,
        cursorVisible = cursorVisible,
        fullScreen = fullScreenDetected,
    )

    /**
     * Puts back the modes of a session that is already running. Called on a
     * fresh emulator before the engine's replay is written, so the replay lands
     * on the screen and in the mode the program expects.
     */
    fun applyModes(modes: TerminalModes) {
        mouseTracking = modes.mouseTracking
        mouseSgr = modes.mouseSgr
        mouseUtf8 = modes.mouseUtf8
        applicationCursorKeys = modes.applicationCursorKeys
        bracketedPaste = modes.bracketedPaste
        cursorVisible = modes.cursorVisible
        fullScreenDetected = modes.fullScreen
        if (modes.alternate != activeAlternate) {
            activeAlternate = modes.alternate
            buffer = if (modes.alternate) alternate else normal
            wrapPending = false
        }
    }

    /**
     * A mouse report, or null when the program has not asked for the mouse.
     * [button] uses the xterm codes (0 left, 1 middle, 2 right, 3 release,
     * 64/65 wheel up/down); [col]/[row] are zero-based cells.
     */
    fun mouseReport(button: Int, col: Int, row: Int, release: Boolean): ByteArray? {
        if (!mouseReporting()) return null
        if (mouseTracking == 1 && release) return null // X10: press only
        val cb = when {
            release && mouseSgr -> button
            release -> 3
            else -> button
        }
        val x = col + 1
        val y = row + 1
        return if (mouseSgr) {
            sgrMouse(cb, x, y, release)
        } else {
            byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (cb + 32).toByte(), (32 + x).toByte(), (32 + y).toByte())
        }
    }

    /**
     * A wheel notch in the SGR encoding, used even when the mouse mode was not
     * seen because the session was attached mid-stream. A program that never
     * enabled the mouse simply ignores it.
     */
    fun wheelReport(button: Int, col: Int, row: Int): ByteArray =
        sgrReport(button, col, row, release = false)

    /** An SGR mouse report regardless of the mode, for a reattached TUI. */
    fun sgrReport(button: Int, col: Int, row: Int, release: Boolean): ByteArray =
        sgrMouse(button, col + 1, row + 1, release)

    private fun sgrMouse(cb: Int, x: Int, y: Int, release: Boolean): ByteArray {
        val suffix = if (release) 'm' else 'M'
        return "\u001b[<$cb;$x;$y$suffix".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private const val ST_GROUND = 0
        private const val ST_ESC = 1
        private const val ST_ESC_INTERMEDIATE = 2
        private const val ST_CSI_ENTRY = 3
        private const val ST_CSI_PARAM = 4
        private const val ST_CSI_INTERMEDIATE = 5
        private const val ST_CSI_IGNORE = 6
        private const val ST_OSC = 7
        private const val ST_OSC_ESC = 8
        private const val ST_STRING = 9
        private const val ST_STRING_ESC = 10
    }
}
