package com.riad.bio_marker.collectors

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.riad.bio_marker.core.ServiceLocator


class BioMarkerIME : InputMethodService() {

    private enum class Layer { ALPHA, SYMBOLS_1, SYMBOLS_2 }
    private enum class Special { NONE, SHIFT, BACKSPACE, ENTER, SPACE, LAYER_TOGGLE, SYMBOL_PAGE }

    private data class KeySpec(
        val label: String,
        val emit: String?,
        val weight: Float = 1f,
        val accent: Boolean = false,
        val special: Special = Special.NONE,
    )

    // ---- timing telemetry --------------------------------------------------
    private var lastKeyMs: Long = 0L

    // ---- layout state ------------------------------------------------------
    private var layer: Layer = Layer.ALPHA
    private var shifted: Boolean = false
    private var capsLock: Boolean = false
    private var lastShiftMs: Long = 0L
    private var rootView: LinearLayout? = null

    // Cached key views so a shift toggle only re-letters existing keys instead
    // of rebuilding the whole keyboard (the old behaviour caused visible lag
    // on fast typing because every capital re-inflated ~40 views).
    private val letterKeyViews = ArrayList<Pair<TextView, Char>>(32)
    private var shiftKeyView: TextView? = null

    // Decorative animated background (love / smile / bird …).
    private var emojiView: KeyboardEmojiView? = null

    // ---- backspace repeat --------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRepeating = false

    // ---- typed-text capture ------------------------------------------------
    // Buffers the actual characters the user types so we can store readable
    // phrases (gated on an active window + consent) into VoiceTranscriptStore
    // alongside the spoken transcripts. Flushed on sentence boundaries, Enter,
    // a short idle, or when the field/keyboard changes.
    private val typedBuffer = StringBuilder()
    private val typingFlushRunnable = Runnable { flushTypedBuffer() }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Moving to a new field — persist whatever was typed in the old one.
        flushTypedBuffer()
        lastKeyMs = 0L
        // Auto-shift first letter of a fresh field.
        shifted = true
        capsLock = false
        layer = Layer.ALPHA
        rebuildLayout()
        emojiView?.startAnimation()
    }

    override fun onCreateInputView(): View {
        val keys = buildRoot().also { rootView = it; rebuildLayout() }
        val emoji = KeyboardEmojiView(this).also { emojiView = it }
        // FrameLayout stack: animated emoji at the back, the (transparent)
        // key grid on top so the decoration shows through the gaps between
        // keys without ever intercepting touches. The base is a medium tone
        // so the white keys clearly stand out (the old cream-on-cream made
        // the keyboard look washed-out / "not showing").
        return FrameLayout(this).apply {
            setBackgroundColor(if (isDark()) 0xFF0E1216.toInt() else 0xFFCBC1AE.toInt())
            addView(emoji, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(keys, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        emojiView?.stopAnimation()
    }

    private fun buildRoot(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(7), dp(5), dp(8))
            // Transparent so the animated emoji background shows through.
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /** Recomposes the keyboard for the current layer. Cheap because it only
     *  runs on layer switches (ALPHA ↔ symbols), never on shift toggles. */
    private fun rebuildLayout() {
        val root = rootView ?: return
        root.removeAllViews()
        letterKeyViews.clear()
        shiftKeyView = null
        val rows = currentRows()
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
                ).apply {
                    topMargin = dp(3); bottomMargin = dp(3)
                }
            }
            for (spec in row) rowLayout.addView(makeKey(spec))
            root.addView(rowLayout)
        }
    }

    // ---- layout definitions ------------------------------------------------

    private fun currentRows(): List<List<KeySpec>> = when (layer) {
        Layer.ALPHA -> alphaRows()
        Layer.SYMBOLS_1 -> symbols1Rows()
        Layer.SYMBOLS_2 -> symbols2Rows()
    }

    private fun alphaRows(): List<List<KeySpec>> {
        // Always build lowercase base keys; case is applied live in
        // [applyShiftState] (labels) and at emit time (committed char), so
        // toggling shift never rebuilds the view tree.
        fun letters(s: String) = s.map { KeySpec(it.toString(), it.toString()) }
        return listOf(
            "1234567890".map { KeySpec(it.toString(), it.toString()) },
            letters("qwertyuiop"),
            letters("asdfghjkl"),
            buildList {
                add(KeySpec(if (capsLock) "⇪" else "⇧", null,
                    weight = 1.4f, accent = true, special = Special.SHIFT))
                addAll(letters("zxcvbnm"))
                add(KeySpec("⌫", null, weight = 1.4f, accent = true,
                    special = Special.BACKSPACE))
            },
            listOf(
                KeySpec("?123", null, weight = 1.4f, accent = true,
                    special = Special.LAYER_TOGGLE),
                KeySpec(",", ","),
                KeySpec("space", " ", weight = 5f, special = Special.SPACE),
                KeySpec(".", "."),
                KeySpec("Enter", null, weight = 1.8f, accent = true,
                    special = Special.ENTER),
            ),
        )
    }

    private fun symbols1Rows(): List<List<KeySpec>> {
        fun row(s: String) = s.map { KeySpec(it.toString(), it.toString()) }
        return listOf(
            row("1234567890"),
            row("@#\$_&-+()/"),
            buildList {
                add(KeySpec("=\\<", null, weight = 1.4f, accent = true,
                    special = Special.SYMBOL_PAGE))
                addAll(row("*\"':;!?"))
                add(KeySpec("⌫", null, weight = 1.4f, accent = true,
                    special = Special.BACKSPACE))
            },
            listOf(
                KeySpec("ABC", null, weight = 1.4f, accent = true,
                    special = Special.LAYER_TOGGLE),
                KeySpec(",", ","),
                KeySpec("space", " ", weight = 5f, special = Special.SPACE),
                KeySpec(".", "."),
                KeySpec("Enter", null, weight = 1.8f, accent = true,
                    special = Special.ENTER),
            ),
        )
    }

    private fun symbols2Rows(): List<List<KeySpec>> {
        fun row(s: String) = s.map { KeySpec(it.toString(), it.toString()) }
        return listOf(
            row("~`|•√π÷×§∆"),
            row("£¢€¥^°={}\\"),
            buildList {
                add(KeySpec("?123", null, weight = 1.4f, accent = true,
                    special = Special.SYMBOL_PAGE))
                addAll(row("%©®™✓[]"))
                add(KeySpec("⌫", null, weight = 1.4f, accent = true,
                    special = Special.BACKSPACE))
            },
            listOf(
                KeySpec("ABC", null, weight = 1.4f, accent = true,
                    special = Special.LAYER_TOGGLE),
                KeySpec("<", "<"),
                KeySpec("space", " ", weight = 5f, special = Special.SPACE),
                KeySpec(">", ">"),
                KeySpec("Enter", null, weight = 1.8f, accent = true,
                    special = Special.ENTER),
            ),
        )
    }

    // ---- view construction -------------------------------------------------

    private fun makeKey(spec: KeySpec): View {
        val tv = TextView(this).apply {
            text = spec.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP,
                if (spec.label.length > 1) 14f else 18f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isAllCaps = false
            setTextColor(keyTextColor(spec))
            background = keyBackground(spec)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, spec.weight
            ).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
        }
        // Register live-updatable keys so a shift toggle only re-letters them.
        if (layer == Layer.ALPHA && spec.special == Special.NONE) {
            val e = spec.emit
            if (e != null && e.length == 1 && e[0].isLetter()) {
                letterKeyViews.add(tv to e[0].lowercaseChar())
            }
        }
        if (spec.special == Special.SHIFT) shiftKeyView = tv
        attachKeyListener(tv, spec)
        return tv
    }

    /** Updates letter-key labels + the shift glyph for the current shift /
     *  caps state WITHOUT rebuilding the view tree — the key to smooth,
     *  lag-free fast typing. */
    private fun applyShiftState() {
        val upper = shifted || capsLock
        for ((tv, base) in letterKeyViews) {
            tv.text = (if (upper) base.uppercaseChar() else base).toString()
        }
        shiftKeyView?.text = if (capsLock) "⇪" else "⇧"
    }

    private fun attachKeyListener(view: View, spec: KeySpec) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animateDown(v)
                    v.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    )
                    if (spec.special == Special.BACKSPACE) scheduleBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    animateUp(v)
                    cancelBackspaceRepeat()
                    if (!backspaceRepeating) dispatchKey(spec)
                    backspaceRepeating = false
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    animateUp(v)
                    cancelBackspaceRepeat()
                    backspaceRepeating = false
                    true
                }
                else -> false
            }
        }
    }

    private fun animateDown(v: View) {
        v.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.85f)
            .setDuration(40).start()
    }

    private fun animateUp(v: View) {
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(80).start()
    }

    // ---- key dispatch ------------------------------------------------------

    private fun dispatchKey(spec: KeySpec) {
        when (spec.special) {
            Special.SHIFT -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftMs < 350 && shifted) {
                    capsLock = !capsLock
                    shifted = capsLock
                } else {
                    capsLock = false
                    shifted = !shifted
                }
                lastShiftMs = now
                applyShiftState()
            }
            Special.LAYER_TOGGLE -> {
                layer = if (layer == Layer.ALPHA) Layer.SYMBOLS_1 else Layer.ALPHA
                rebuildLayout()
            }
            Special.SYMBOL_PAGE -> {
                layer = if (layer == Layer.SYMBOLS_1) Layer.SYMBOLS_2 else Layer.SYMBOLS_1
                rebuildLayout()
            }
            Special.BACKSPACE -> deleteOne()
            Special.ENTER -> sendEnter()
            Special.SPACE -> commitChar(' ')
            Special.NONE -> {
                val raw = spec.emit ?: return
                // Apply the live shift/caps state to letters at commit time.
                val ch = if (layer == Layer.ALPHA && (shifted || capsLock) &&
                    raw.length == 1 && raw[0].isLetter()
                ) raw[0].uppercaseChar() else raw[0]
                commitChar(ch)
                if (shifted && !capsLock && layer == Layer.ALPHA) {
                    shifted = false
                    applyShiftState()
                }
            }
        }
    }

    private fun commitChar(ch: Char) {
        currentInputConnection?.commitText(ch.toString(), 1)
        logKeystroke(isBackspace = false)
        onTypedChar(ch)
    }

    private fun deleteOne() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        logKeystroke(isBackspace = true)
        // Keep the capture buffer in sync with on-screen corrections.
        if (typedBuffer.isNotEmpty()) typedBuffer.deleteCharAt(typedBuffer.length - 1)
        scheduleTypingFlush()
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        val noEnterAction =
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = info != null &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        // In a multi-line field (or when the field declares no editor action)
        // Enter must insert a newline. Otherwise perform the field's action
        // (Search / Send / Go / Next …).
        if (multiLine || noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == 0) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
        logKeystroke(isBackspace = false)
        // Enter usually ends a message / line — treat it as a flush boundary.
        flushTypedBuffer()
    }

    // ---- typed-text capture buffer ----------------------------------------

    private fun onTypedChar(ch: Char) {
        typedBuffer.append(ch)
        when {
            ch == '\n' -> flushTypedBuffer()
            typedBuffer.length >= TYPED_BUFFER_CAP -> flushTypedBuffer()
            else -> scheduleTypingFlush()
        }
    }

    private fun scheduleTypingFlush() {
        handler.removeCallbacks(typingFlushRunnable)
        handler.postDelayed(typingFlushRunnable, TYPING_IDLE_MS)
    }

    /** Hands the buffered text to [TypedTextCollector] (which decides whether
     *  to store it based on an active window + consent), then clears it. */
    private fun flushTypedBuffer() {
        handler.removeCallbacks(typingFlushRunnable)
        if (typedBuffer.isEmpty()) return
        val text = typedBuffer.toString()
        typedBuffer.setLength(0)
        TypedTextCollector.capture(text)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard is being hidden — persist anything still buffered.
        flushTypedBuffer()
        super.onFinishInputView(finishingInput)
    }

    private fun logKeystroke(isBackspace: Boolean) {
        val nowMs = System.currentTimeMillis()
        val isFirst = lastKeyMs == 0L
        val latency = if (isFirst) 0L else (nowMs - lastKeyMs).coerceAtLeast(0L)
        lastKeyMs = nowMs
        ServiceLocator.metricsBus.onKeystroke(latency, isBackspace)
        ServiceLocator.sampleLogger.appendKeystroke(nowMs, latency, isBackspace)
        TypingSessionAggregator.onKeystroke(nowMs, latency, isBackspace)
    }

    // ---- backspace long-press repeat --------------------------------------

    private val backspaceRunnable = object : Runnable {
        override fun run() {
            backspaceRepeating = true
            deleteOne()
            handler.postDelayed(this, 60)
        }
    }
    private fun scheduleBackspaceRepeat() {
        handler.postDelayed(backspaceRunnable, 350)
    }
    private fun cancelBackspaceRepeat() {
        handler.removeCallbacks(backspaceRunnable)
    }

    // ---- hardware keyboard --------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isBs = keyCode == KeyEvent.KEYCODE_DEL
        logKeystroke(isBackspace = isBs)
        return super.onKeyDown(keyCode, event)
    }

    // ---- styling helpers ---------------------------------------------------

    private fun keyBackground(spec: KeySpec): RippleDrawable {
        val dark = isDark()
        val enter = spec.special == Special.ENTER
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            setColor(
                when {
                    // Amber primary Enter key — the prominent, easy-to-find key.
                    enter -> 0xFF9C6B2F.toInt()
                    spec.special == Special.SPACE && !dark -> 0xFFFFFFFF.toInt()
                    spec.special == Special.SPACE && dark -> 0xFF2B313A.toInt()
                    spec.accent && !dark -> 0xFFEAE0CC.toInt()
                    spec.accent && dark -> 0xFF272D36.toInt()
                    !dark -> 0xFFFFFFFF.toInt()
                    else -> 0xFF1E232B.toInt()
                }
            )
            if (!dark && !enter) setStroke(dp(1), 0xFFC7BBA3.toInt())
        }
        val ripple = when {
            enter -> Color.parseColor("#55FFFFFF")
            dark -> Color.parseColor("#3FFFFFFF")
            else -> Color.parseColor("#22000000")
        }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), bg, null)
    }

    private fun keyTextColor(spec: KeySpec): Int {
        val dark = isDark()
        return when {
            spec.special == Special.ENTER -> 0xFFFFFFFF.toInt() // white on amber
            spec.accent && !dark -> 0xFF3A2F1B.toInt()
            spec.accent && dark -> 0xFFE7ECF3.toInt()
            !dark -> 0xFF3A2F1B.toInt()
            else -> 0xFFE7ECF3.toInt()
        }
    }

    private fun isDark(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Idle gap after which a buffered phrase is flushed to the store. */
        private const val TYPING_IDLE_MS = 4_000L
        /** Hard cap so a very long message is flushed in chunks. */
        private const val TYPED_BUFFER_CAP = 280
    }
}
