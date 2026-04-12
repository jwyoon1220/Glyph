package io.github.jwyoon1220.glyph.ui

import java.awt.*
import java.awt.event.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * High-performance custom single-line text field component.
 *
 * Avoids the heavyweight Swing [javax.swing.JTextField] overhead:
 *  - Renders directly via [Graphics2D] with hardware-accelerated anti-aliasing.
 *  - Uses a plain [StringBuilder] (not a [javax.swing.text.Document]) as its
 *    backing store – no document-event or element-tree overhead.
 *  - Minimal allocations during [paintComponent].
 */
class GlyphTextField(columns: Int = 20) : JComponent() {

    // ----- Colors (Night-Owl palette) -----
    private val colorBg       = Color(18, 35, 52)
    private val colorBgFocus  = Color(22, 42, 62)
    private val colorBorder   = Color(60, 80, 100)
    private val colorBorderFocus = Color(100, 150, 210)
    private val colorText     = Color(214, 222, 235)
    private val colorCaret    = Color(247, 140, 108)
    private val colorSel      = Color(29, 59, 83)
    private val colorHint     = Color(90, 105, 120)

    private val buffer = StringBuilder()
    private var caretPos = 0
    private var selStart = -1
    private var selEnd   = -1

    private var showCaret = true
    private val caretTimer = Timer(500) { showCaret = !showCaret; repaint() }

    private val changeListeners = mutableListOf<() -> Unit>()
    private val actionListeners = mutableListOf<() -> Unit>()

    /** Placeholder text shown when the field is empty and unfocused. */
    var hint: String = ""
        set(value) { field = value; repaint() }

    var text: String
        get() = buffer.toString()
        set(value) {
            buffer.clear()
            buffer.append(value)
            caretPos = value.length
            clearSelection()
            repaint()
        }

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        font = Font("SansSerif", Font.PLAIN, 13)
        preferredSize = Dimension(columns * 8 + 16, 28)
        caretTimer.start()

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { showCaret = true; repaint() }
            override fun focusLost(e: FocusEvent)   { showCaret = false; repaint() }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                caretPos = offsetAt(e.x)
                clearSelection()
                repaint()
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (selStart == -1) selStart = caretPos
                caretPos = offsetAt(e.x)
                selEnd = caretPos
                repaint()
            }
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                val c = e.keyChar
                if (!c.isISOControl() && c != '\b' && c != '\u007F' && !e.isControlDown && !e.isMetaDown) {
                    deleteSelection()
                    buffer.insert(caretPos, c)
                    caretPos++
                    clearSelection()
                    repaint()
                    notifyChange()
                }
            }

            override fun keyPressed(e: KeyEvent) {
                val ctrl = e.isControlDown || e.isMetaDown
                val shift = e.isShiftDown

                if (shift && selStart == -1) { selStart = caretPos; selEnd = caretPos }

                when (e.keyCode) {
                    KeyEvent.VK_LEFT  -> { if (caretPos > 0) caretPos--; if (shift) selEnd = caretPos else clearSelection() }
                    KeyEvent.VK_RIGHT -> { if (caretPos < buffer.length) caretPos++; if (shift) selEnd = caretPos else clearSelection() }
                    KeyEvent.VK_HOME  -> { caretPos = 0; if (shift) selEnd = caretPos else clearSelection() }
                    KeyEvent.VK_END   -> { caretPos = buffer.length; if (shift) selEnd = caretPos else clearSelection() }
                    KeyEvent.VK_BACK_SPACE -> {
                        if (hasSelection()) deleteSelection()
                        else if (caretPos > 0) { buffer.deleteCharAt(caretPos - 1); caretPos-- }
                        repaint(); notifyChange()
                    }
                    KeyEvent.VK_DELETE -> {
                        if (hasSelection()) deleteSelection()
                        else if (caretPos < buffer.length) buffer.deleteCharAt(caretPos)
                        repaint(); notifyChange()
                    }
                    KeyEvent.VK_ENTER -> { clearSelection(); repaint(); notifyAction() }
                    KeyEvent.VK_A -> if (ctrl) { selStart = 0; selEnd = buffer.length; caretPos = buffer.length; repaint() }
                    KeyEvent.VK_C -> if (ctrl) copyToClipboard()
                    KeyEvent.VK_X -> if (ctrl) { copyToClipboard(); deleteSelection(); repaint(); notifyChange() }
                    KeyEvent.VK_V -> if (ctrl) { pasteFromClipboard(); repaint(); notifyChange() }
                }
                showCaret = true
                repaint()
            }
        })
    }

    fun addChangeListener(l: () -> Unit)  { changeListeners.add(l) }
    fun addActionListener(l: () -> Unit)  { actionListeners.add(l) }

    private fun notifyChange()  { changeListeners.forEach { it() } }
    private fun notifyAction()  { actionListeners.forEach { it() } }

    private fun hasSelection() = selStart != -1 && selStart != selEnd

    private fun clearSelection() { selStart = -1; selEnd = -1 }

    private fun deleteSelection() {
        if (!hasSelection()) return
        val s = minOf(selStart, selEnd).coerceIn(0, buffer.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, buffer.length)
        buffer.delete(s, e)
        caretPos = s
        clearSelection()
    }

    private fun copyToClipboard() {
        if (!hasSelection()) return
        val s = minOf(selStart, selEnd).coerceIn(0, buffer.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, buffer.length)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(buffer.substring(s, e)), null)
    }

    private fun pasteFromClipboard() {
        val transferable = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return
        val pasted = transferable.getTransferData(DataFlavor.stringFlavor) as String
        deleteSelection()
        buffer.insert(caretPos, pasted)
        caretPos += pasted.length
    }

    private fun offsetAt(x: Int): Int {
        val fm = getFontMetrics(font)
        val padding = 8
        var acc = padding
        for (i in buffer.indices) {
            val cw = fm.charWidth(buffer[i])
            if (acc + cw / 2 > x) return i
            acc += cw
        }
        return buffer.length
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width.toFloat(); val h = height.toFloat()
        val arc = 5f
        val shape = RoundRectangle2D.Float(0f, 0f, w, h, arc, arc)

        g2.color = if (hasFocus()) colorBgFocus else colorBg
        g2.fill(shape)
        g2.color = if (hasFocus()) colorBorderFocus else colorBorder
        g2.draw(shape)

        val fm = g2.getFontMetrics(font)
        val ty = (height + fm.ascent - fm.descent) / 2
        val padding = 8

        if (buffer.isEmpty() && hint.isNotEmpty() && !hasFocus()) {
            g2.color = colorHint
            g2.font = font
            g2.drawString(hint, padding, ty)
        } else {
            // Selection highlight
            if (hasSelection()) {
                val s = minOf(selStart, selEnd).coerceIn(0, buffer.length)
                val e = maxOf(selStart, selEnd).coerceIn(0, buffer.length)
                val sx = padding + fm.stringWidth(buffer.substring(0, s))
                val sw = fm.stringWidth(buffer.substring(s, e))
                g2.color = colorSel
                g2.fillRect(sx, (height - fm.height) / 2, sw, fm.height)
            }

            g2.color = colorText
            g2.font = font
            g2.drawString(buffer.toString(), padding, ty)

            // Caret
            if (showCaret && hasFocus()) {
                val cx = padding + fm.stringWidth(buffer.substring(0, caretPos))
                g2.color = colorCaret
                g2.fillRect(cx, (height - fm.height) / 2, 1, fm.height)
            }
        }
    }
}
