package io.github.jwyoon1220.glyph

import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/** Search mode for the find bar. */
enum class SearchMode { TEXT, WORD, REGEX }

/**
 * IntelliJ-style find bar.
 *
 * Shows at the bottom of the editor area. Supports:
 *  - Plain text search
 *  - Whole-word search
 *  - Regular-expression search
 *  - Case-sensitive toggle
 *  - Prev / Next navigation (also via Enter / Shift+Enter)
 *  - Escape to close
 */
class FindBar : JPanel(BorderLayout()) {

    var onSearch: ((query: String, mode: SearchMode, matchCase: Boolean) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val queryField = JTextField(22).apply {
        background = Color(24, 32, 46)
        foreground = Color(214, 222, 235)
        caretColor = Color(247, 140, 108)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(60, 80, 110)),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )
        font = Font("SansSerif", Font.PLAIN, 13)
    }

    private val matchCountLabel = JLabel("").apply {
        font = Font("SansSerif", Font.PLAIN, 12)
        foreground = Color(120, 130, 150)
        preferredSize = Dimension(70, 20)
        border = EmptyBorder(0, 6, 0, 6)
    }

    private var currentMode = SearchMode.TEXT
    private var matchCase = false

    private val btnText  = createModeButton("Text")
    private val btnWord  = createModeButton("Word")
    private val btnRegex = createModeButton("Regex")
    private val btnCase  = createModeButton("Aa")
    private val btnPrev  = createNavButton("◀ Prev")
    private val btnNext  = createNavButton("Next ▶")
    private val btnClose = createNavButton("✕")

    init {
        background = Color(22, 30, 44)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(50, 60, 80))
        preferredSize = Dimension(0, 32)
        isVisible = false

        applyModeStyle(btnText,  active = true)
        applyModeStyle(btnWord,  active = false)
        applyModeStyle(btnRegex, active = false)
        applyModeStyle(btnCase,  active = false)

        btnText.addActionListener  { selectMode(SearchMode.TEXT);  triggerSearch() }
        btnWord.addActionListener  { selectMode(SearchMode.WORD);  triggerSearch() }
        btnRegex.addActionListener { selectMode(SearchMode.REGEX); triggerSearch() }
        btnCase.addActionListener  {
            matchCase = !matchCase
            applyModeStyle(btnCase, active = matchCase)
            triggerSearch()
        }
        btnPrev.addActionListener  { onPrev?.invoke() }
        btnNext.addActionListener  { onNext?.invoke() }
        btnClose.addActionListener { onClose?.invoke() }

        queryField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER  -> if (e.isShiftDown) onPrev?.invoke() else onNext?.invoke()
                    KeyEvent.VK_ESCAPE -> onClose?.invoke()
                    else               -> triggerSearch()
                }
            }
        })

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(JLabel("Find:").apply {
                foreground = Color(130, 150, 180)
                font = Font("SansSerif", Font.PLAIN, 12)
            })
            add(queryField)
            add(matchCountLabel)
            add(btnPrev)
            add(btnNext)
        }

        val right = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(JLabel("Mode:").apply {
                foreground = Color(100, 120, 150)
                font = Font("SansSerif", Font.PLAIN, 12)
            })
            add(btnText)
            add(btnWord)
            add(btnRegex)
            add(JSeparator(JSeparator.VERTICAL).apply {
                preferredSize = Dimension(1, 18)
                foreground = Color(60, 70, 90)
            })
            add(JLabel("Case:").apply {
                foreground = Color(100, 120, 150)
                font = Font("SansSerif", Font.PLAIN, 12)
            })
            add(btnCase)
            add(btnClose)
        }

        add(left,  BorderLayout.WEST)
        add(right, BorderLayout.EAST)
    }

    /** Shows the bar and focuses the search field, pre-selecting any existing query. */
    fun focusField() {
        queryField.requestFocusInWindow()
        queryField.selectAll()
    }

    /** Update the "N / Total" counter. Pass -1 / 0 to show "No results". */
    fun updateMatchCount(current: Int, total: Int) {
        matchCountLabel.text = if (current < 0 || total == 0) "No results" else "${current + 1} / $total"
        queryField.foreground =
            if (total == 0 && queryField.text.isNotEmpty()) Color(255, 100, 100)
            else Color(214, 222, 235)
    }

    val query: String get() = queryField.text
    val mode: SearchMode get() = currentMode
    val isCaseSensitive: Boolean get() = matchCase

    // ---------------------------------------------------------------- private

    private fun triggerSearch() {
        onSearch?.invoke(queryField.text, currentMode, matchCase)
    }

    private fun selectMode(m: SearchMode) {
        currentMode = m
        applyModeStyle(btnText,  active = m == SearchMode.TEXT)
        applyModeStyle(btnWord,  active = m == SearchMode.WORD)
        applyModeStyle(btnRegex, active = m == SearchMode.REGEX)
    }

    private fun createModeButton(label: String) = JButton(label).apply {
        font = Font("SansSerif", Font.PLAIN, 11)
        border = BorderFactory.createEmptyBorder(2, 7, 2, 7)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusPainted = false
        isOpaque = true
        preferredSize = Dimension(if (label.length > 3) 55 else 32, 22)
    }

    private fun createNavButton(label: String) = JButton(label).apply {
        font = Font("SansSerif", Font.PLAIN, 11)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusPainted = false
        isOpaque = true
        background = Color(35, 45, 62)
        foreground = Color(180, 200, 220)
    }

    private fun applyModeStyle(btn: JButton, active: Boolean) {
        btn.background = if (active) Color(45, 75, 130) else Color(35, 45, 62)
        btn.foreground = if (active) Color(200, 220, 255) else Color(140, 160, 190)
    }
}
