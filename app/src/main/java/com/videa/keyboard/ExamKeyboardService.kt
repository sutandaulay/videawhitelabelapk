package com.videa.keyboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ExamKeyboardService : InputMethodService() {

    private var isShiftOn = false
    private var isCapsLock = false
    private var isSymbolMode = false
    private var lastShiftTapTime = 0L

    private lateinit var rootLayout: LinearLayout
    private lateinit var letterLayout: LinearLayout
    private lateinit var symbolLayout: LinearLayout
    private val letterKeyButtons = mutableListOf<Button>()

    // Palet warna ala Gboard (Google Keyboard) Dark Mode modern
    private val colorBackground = Color.parseColor("#121212")
    private val colorKeyNormal = Color.parseColor("#3C4043")      // Tombol huruf (abu-abu gelap elegan)
    private val colorKeySpecial = Color.parseColor("#5F6368")     // Tombol fungsi (Shift, 123, Backspace)
    private val colorKeyEnter = Color.parseColor("#8AB4F8")       // Tombol Enter (Biru aksen Google)
    private val colorTextNormal = Color.WHITE
    private val colorTextEnter = Color.parseColor("#202124")

    private var keyHeight = 0

    private val rowsLower = listOf(
        "q w e r t y u i o p",
        "a s d f g h j k l",
        "z x c v b n m"
    )

    private val rowsSymbol = listOf(
        "1 2 3 4 5 6 7 8 9 0",
        "@ # $ % & * - + (",
        ") \" ' : ; ! ? /"
    )

    override fun onCreateInputView(): View {
        keyHeight = 50.dp // Ditinggikan sedikit agar sangat nyaman disentuh jari

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
            setPadding(6.dp, 8.dp, 6.dp, 8.dp)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val badge = TextView(this).apply {
            text = "🔒 Keyboard Videa — Aman Ujian"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6.dp)
        }
        rootLayout.addView(badge)

        letterLayout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            visibility = View.VISIBLE
        }
        symbolLayout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            visibility = View.GONE
        }

        buildLetterRows()
        buildSymbolRows()

        rootLayout.addView(letterLayout)
        rootLayout.addView(symbolLayout)

        return rootLayout
    }

    private fun updateLayoutVisibility() {
        if (isSymbolMode) {
            letterLayout.visibility = View.GONE
            symbolLayout.visibility = View.VISIBLE
        } else {
            letterLayout.visibility = View.VISIBLE
            symbolLayout.visibility = View.GONE
        }
    }

    private fun buildLetterRows() {
        rowsLower.forEach { row ->
            val rowLayout = newRow()
            row.split(" ").forEach { ch ->
                val btn = makeKey(ch, colorKeyNormal, colorTextNormal) { commitChar(ch) }
                btn.tag = ch
                letterKeyButtons.add(btn)
                rowLayout.addView(btn, rowParams())
            }
            letterLayout.addView(rowLayout)
        }

        val bottomRow = newRow()
        bottomRow.addView(makeKey("⇧", colorKeySpecial, colorTextNormal) { handleShiftTap() }, rowParams(weight = 1.5f))
        bottomRow.addView(makeKey("123", colorKeySpecial, colorTextNormal) { 
            isSymbolMode = true
            updateLayoutVisibility()
        }, rowParams(weight = 1.2f))
        bottomRow.addView(makeKey(",", colorKeyNormal, colorTextNormal) { commitChar(",") }, rowParams(weight = 0.8f))
        bottomRow.addView(makeKey("spasi", colorKeyNormal, colorTextNormal) { commitChar(" ") }, rowParams(weight = 3.5f))
        bottomRow.addView(makeKey(".", colorKeyNormal, colorTextNormal) { commitChar(".") }, rowParams(weight = 0.8f))
        bottomRow.addView(makeKey("⌫", colorKeySpecial, colorTextNormal) { doBackspace() }, rowParams(weight = 1.2f))
        letterLayout.addView(bottomRow)

        letterLayout.addView(newRow().apply {
            addView(makeEnterKey(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight))
        })
    }

    private fun buildSymbolRows() {
        rowsSymbol.forEach { row ->
            val rowLayout = newRow()
            row.split(" ").forEach { ch ->
                rowLayout.addView(makeKey(ch, colorKeyNormal, colorTextNormal) { commitChar(ch) }, rowParams())
            }
            symbolLayout.addView(rowLayout)
        }

        val bottomRow = newRow()
        bottomRow.addView(makeKey("ABC", colorKeySpecial, colorTextNormal) { 
            isSymbolMode = false
            updateLayoutVisibility()
        }, rowParams(weight = 1.5f))
        bottomRow.addView(makeKey(",", colorKeyNormal, colorTextNormal) { commitChar(",") }, rowParams(weight = 0.8f))
        bottomRow.addView(makeKey("spasi", colorKeyNormal, colorTextNormal) { commitChar(" ") }, rowParams(weight = 3.5f))
        bottomRow.addView(makeKey(".", colorKeyNormal, colorTextNormal) { commitChar(".") }, rowParams(weight = 0.8f))
        bottomRow.addView(makeKey("⌫", colorKeySpecial, colorTextNormal) { doBackspace() }, rowParams(weight = 1.2f))
        symbolLayout.addView(bottomRow)

        symbolLayout.addView(newRow().apply {
            addView(makeEnterKey(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight))
        })
    }

    private fun commitChar(char: String) {
        val out = if (isShiftOn && !isSymbolMode) char.uppercase() else char
        currentInputConnection?.commitText(out, 1)
        if (isShiftOn && !isCapsLock) {
            isShiftOn = false
            applyShiftVisual()
        }
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 350) {
            isCapsLock = true
            isShiftOn = true
        } else {
            isCapsLock = false
            isShiftOn = !isShiftOn
        }
        lastShiftTapTime = now
        applyShiftVisual()
    }

    private fun applyShiftVisual() {
        letterKeyButtons.forEach { btn ->
            val base = btn.tag as? String ?: return@forEach
            btn.text = if (isShiftOn) base.uppercase() else base
        }
    }

    private fun doBackspace() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    private fun doEnter() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun newRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 5.dp
        }
    }

    private fun rowParams(weight: Float = 1f) = LinearLayout.LayoutParams(0, keyHeight, weight).apply {
        marginStart = 3.dp
        marginEnd = 3.dp
    }

    private fun makeKey(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 18f
        isAllCaps = false
        
        // Desain rounded corner ala Gboard dengan efek sentuhan ripple
        val shape = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 6.dp.toFloat()
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#80FFFFFF"))
        background = RippleDrawable(rippleColor, shape, null)
        
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        setOnClickListener { 
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick() 
        }
    }

    private fun makeEnterKey(): Button = Button(this).apply {
        text = "Kirim / Enter"
        setTextColor(colorTextEnter)
        textSize = 15f
        isAllCaps = false
        
        val shape = GradientDrawable().apply {
            setColor(colorKeyEnter)
            cornerRadius = 6.dp.toFloat()
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#40000000"))
        background = RippleDrawable(rippleColor, shape, null)
        
        stateListAnimator = null
        setOnClickListener { doEnter() }
    }

    private val Int.dp: Int
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }
    override fun onEvaluateFullscreenMode(): Boolean = false
}
