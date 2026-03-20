package io.github.jwyoon1220.glyph

import io.github.jwyoon1220.glyph.search.DictionaryClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.*
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import javax.swing.*
import javax.swing.border.LineBorder

class GlyphTextArea(private val dictClient: DictionaryClient) : JComponent() {

    private val pieceTable = PieceTable()
    private val uiScope = CoroutineScope(Dispatchers.Swing + Job())

    private var caretOffset = 0
    private var selectionStart = -1
    private var selectionEnd = -1

    private var compositionText = ""
    private var showCaret = true

    private var hoveredWordBounds: IntRange? = null
    private var popupWindow: JPopupMenu? = null

    /** Listeners notified after a period of typing inactivity (for auto-commit). */
    private val typingStoppedListeners = mutableListOf<() -> Unit>()
    private var inactivityTimer: Timer? = null

    /** Optional undo handler supplied by the owner (e.g. git-based restore). */
    var onUndoRequested: (() -> Unit)? = null

    fun addTypingStoppedListener(listener: () -> Unit) {
        typingStoppedListeners.add(listener)
    }

    private fun resetInactivityTimer() {
        inactivityTimer?.stop()
        inactivityTimer = Timer(3000) {
            typingStoppedListeners.forEach { it() }
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    init {
        isFocusable = true
        
        val fallbackFonts = arrayOf("D2Coding", "Noto Sans KR", "Malgun Gothic", "Apple SD Gothic Neo", "SansSerif")
        val availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        val selectedFont = fallbackFonts.firstOrNull { availableFonts.contains(it) } ?: "SansSerif"
        font = Font(selectedFont, Font.PLAIN, 18)
        
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        enableInputMethods(true)

        Timer(500) {
            showCaret = !showCaret
            repaint()
        }.start()

        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                val iter = event.text
                var composed = ""
                if (iter != null) {
                    var c = iter.first()
                    while (c != java.text.CharacterIterator.DONE) {
                        composed += c
                        c = iter.next()
                    }
                }
                
                val committed = event.committedCharacterCount
                if (committed > 0) {
                    val comText = composed.substring(0, committed)
                    if (selectionStart != -1 && selectionStart != selectionEnd) deleteSelection()
                    pieceTable.insert(caretOffset, comText)
                    caretOffset += comText.length
                    resetInactivityTimer()
                }
                
                compositionText = if (composed.length > committed) composed.substring(committed) else ""
                showCaret = true
                event.consume()
                repaint()
            }

            override fun caretPositionChanged(event: InputMethodEvent) {
                event.consume()
            }
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (compositionText.isNotEmpty() || e.isControlDown || e.isAltDown || e.isMetaDown) return
                val c = e.keyChar
                if (!c.isISOControl() && c != '\b' && c != '\u007F') {
                    if (selectionStart != -1 && selectionStart != selectionEnd) deleteSelection()
                    pieceTable.insert(caretOffset, c.toString())
                    caretOffset++
                    showCaret = true
                    resetInactivityTimer()
                    repaint()
                }
            }

            override fun keyPressed(e: KeyEvent) {
                val shift = e.isShiftDown
                val ctrl = e.isControlDown || e.isMetaDown

                if (shift && selectionStart == -1) {
                    selectionStart = caretOffset
                    selectionEnd = caretOffset
                }

                when (e.keyCode) {
                    KeyEvent.VK_LEFT -> {
                        if (caretOffset > 0) caretOffset--
                        if (shift) selectionEnd = caretOffset else clearSelection()
                    }
                    KeyEvent.VK_RIGHT -> {
                        if (caretOffset < pieceTable.length) caretOffset++
                        if (shift) selectionEnd = caretOffset else clearSelection()
                    }
                    KeyEvent.VK_UP -> moveCaretLine(-1, shift)
                    KeyEvent.VK_DOWN -> moveCaretLine(1, shift)
                    KeyEvent.VK_ENTER -> {
                        if (selectionStart != -1 && selectionStart != selectionEnd) deleteSelection()
                        pieceTable.insert(caretOffset, "\n")
                        caretOffset++
                        clearSelection()
                        resetInactivityTimer()
                    }
                    KeyEvent.VK_BACK_SPACE -> {
                        if (selectionStart != -1 && selectionStart != selectionEnd) {
                            deleteSelection()
                        } else if (caretOffset > 0) {
                            pieceTable.delete(caretOffset - 1, 1)
                            caretOffset--
                        }
                        resetInactivityTimer()
                    }
                    KeyEvent.VK_DELETE -> {
                        if (selectionStart != -1 && selectionStart != selectionEnd) {
                            deleteSelection()
                        } else if (caretOffset < pieceTable.length) {
                            pieceTable.delete(caretOffset, 1)
                        }
                        resetInactivityTimer()
                    }
                    KeyEvent.VK_Z -> if (ctrl) {
                        onUndoRequested?.invoke()
                        return
                    }
                    KeyEvent.VK_A -> if (ctrl) {
                        selectionStart = 0
                        selectionEnd = pieceTable.length
                        caretOffset = pieceTable.length
                    }
                    KeyEvent.VK_C -> if (ctrl) copyToClipboard()
                    KeyEvent.VK_X -> if (ctrl) {
                        copyToClipboard()
                        deleteSelection()
                    }
                    KeyEvent.VK_V -> if (ctrl) pasteFromClipboard()
                }
                showCaret = true
                repaint()
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                popupWindow?.isVisible = false
                
                if (e.isControlDown && hoveredWordBounds != null) {
                    val word = pieceTable.getText().substring(hoveredWordBounds!!.first, hoveredWordBounds!!.last + 1)
                    showDictionaryPopup(word, e.x, e.y)
                    return
                }

                val offset = getOffsetFromPoint(e.x, e.y)
                caretOffset = offset
                clearSelection()
                repaint()
            }
        })
        
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (selectionStart == -1) selectionStart = caretOffset
                val offset = getOffsetFromPoint(e.x, e.y)
                caretOffset = offset
                selectionEnd = offset
                repaint()
            }

            override fun mouseMoved(e: MouseEvent) {
                if (e.isControlDown) {
                    val offset = getOffsetFromPoint(e.x, e.y)
                    val bounds = getWordBoundsAt(offset)
                    if (bounds != hoveredWordBounds) {
                        hoveredWordBounds = bounds
                        cursor = if (bounds != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                        repaint()
                    }
                } else {
                    if (hoveredWordBounds != null) {
                        hoveredWordBounds = null
                        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                        repaint()
                    }
                }
            }
        })
    }

    private fun getWordBoundsAt(offset: Int): IntRange? {
        val text = pieceTable.getText()
        if (offset < 0 || offset >= text.length) return null
        
        // Korean characters and letters/digits
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c in '\uAC00'..'\uD7A3'
        
        if (!isWordChar(text[offset])) return null

        var start = offset
        var end = offset
        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }
        while (end < text.length - 1 && isWordChar(text[end + 1])) {
            end++
        }
        return start..end
    }

    private fun showDictionaryPopup(word: String, x: Int, y: Int) {
        val popup = JPopupMenu()
        popup.border = LineBorder(Color.DARK_GRAY, 1)
        val textArea = JTextArea("Searching dictionary for: \$word...").apply {
            isEditable = false
            font = Font(this@GlyphTextArea.font.name, Font.PLAIN, 14)
            background = Color(60, 63, 65)
            foreground = Color.WHITE
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = Dimension(350, 200)
            border = null
        }
        popup.add(scrollPane)
        popup.show(this, x, y + 20)
        popupWindow = popup

        uiScope.launch {
            val results = dictClient.searchWord(word)
            if (results.isEmpty()) {
                textArea.text = "No standard dictionary definition found for: \$word"
            } else {
                val sb = java.lang.StringBuilder()
                for (item in results) {
                    sb.append("【\${item.word}】 " + if (item.pos.isNotEmpty()) "[\${item.pos}]\n" else "\n")
                    sb.append("\${item.sense.definition}\n\n")
                }
                textArea.text = sb.toString()
                textArea.caretPosition = 0
            }
        }
    }

    private fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
    }

    private fun deleteSelection() {
        if (selectionStart == -1 || selectionStart == selectionEnd) return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        pieceTable.delete(start, end - start)
        caretOffset = start
        clearSelection()
    }

    private fun copyToClipboard() {
        if (selectionStart == -1 || selectionStart == selectionEnd) return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        val text = pieceTable.getText().substring(start, end)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    private fun pasteFromClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = clipboard.getContents(null)
        if (transferable != null && transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
            if (selectionStart != -1 && selectionStart != selectionEnd) deleteSelection()
            pieceTable.insert(caretOffset, text)
            caretOffset += text.length
            clearSelection()
        }
    }

    private fun getLines(): List<String> {
        return pieceTable.getText().split('\n')
    }

    private fun moveCaretLine(dir: Int, shift: Boolean) {
        val text = pieceTable.getText()
        var currentOffset = 0
        var currentLineIdx = 0
        var colIdx = 0
        val lines = getLines()
        
        for (i in lines.indices) {
            val len = lines[i].length + 1
            if (caretOffset >= currentOffset && caretOffset < currentOffset + len) {
                currentLineIdx = i
                colIdx = caretOffset - currentOffset
                if (caretOffset == text.length && text.endsWith("\n")) {
                   currentLineIdx = lines.size - 1
                   colIdx = 0
                }
                break
            }
            currentOffset += len
        }

        var targetLineIdx = currentLineIdx + dir
        if (targetLineIdx < 0) targetLineIdx = 0
        if (targetLineIdx >= lines.size) targetLineIdx = lines.size - 1

        if (targetLineIdx != currentLineIdx) {
            var targetOffset = 0
            for (i in 0 until targetLineIdx) {
                targetOffset += lines[i].length + 1
            }
            val targetLineLen = lines[targetLineIdx].length
            caretOffset = targetOffset + minOf(colIdx, targetLineLen)
        }
        if (shift) selectionEnd = caretOffset else clearSelection()
    }

    private fun getOffsetFromPoint(x: Int, y: Int): Int {
        val fm = getFontMetrics(font)
        val lineHeight = fm.height
        val lineIdx = y / lineHeight
        
        val lines = getLines()
        if (lineIdx < 0) return 0
        if (lineIdx >= lines.size) return pieceTable.length

        val lineStr = lines[lineIdx]
        var accumulatedWidth = 0
        var colIdx = 0
        for (i in lineStr.indices) {
            val cw = fm.charWidth(lineStr[i])
            if (accumulatedWidth + cw / 2 > x) break
            accumulatedWidth += cw
            colIdx++
        }

        var offset = 0
        for (i in 0 until lineIdx) {
            offset += lines[i].length + 1
        }
        return offset + colIdx
    }

    override fun getInputMethodRequests(): InputMethodRequests {
        return object : InputMethodRequests {
            override fun getTextLocation(offset: TextHitInfo?): Rectangle {
                val fm = getFontMetrics(font)
                val lines = getLines()
                var currentOff = 0
                var cy = 0
                var cx = 0
                for (i in lines.indices) {
                    val len = lines[i].length + 1
                    if (caretOffset >= currentOff && caretOffset < currentOff + len) {
                        cy = i * fm.height
                        val strBefore = lines[i].substring(0, caretOffset - currentOff)
                        cx = fm.stringWidth(strBefore)
                        break
                    }
                    currentOff += len
                }
                val pt = try { locationOnScreen } catch (e: Exception) { Point(0,0) }
                return Rectangle(pt.x + cx, pt.y + cy, 0, fm.height)
            }

            override fun getLocationOffset(x: Int, y: Int): TextHitInfo? {
                val offset = try { getOffsetFromPoint(x - locationOnScreen.x, y - locationOnScreen.y) } catch (e: Exception) { 0 }
                return TextHitInfo.leading(offset)
            }

            override fun getInsertPositionOffset(): Int = caretOffset
            override fun getCommittedText(beginIndex: Int, endIndex: Int, attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator {
                val safeBegin = maxOf(0, beginIndex)
                val safeEnd = minOf(pieceTable.length, endIndex)
                val text = if (safeBegin <= safeEnd) pieceTable.getText().substring(safeBegin, safeEnd) else ""
                return AttributedString(text).iterator
            }
            override fun getCommittedTextLength(): Int = pieceTable.length
            override fun cancelLatestCommittedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator? = null
            override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator? = null
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        
        g2d.color = Color(43, 43, 43)
        g2d.fillRect(0, 0, width, height)

        if (pieceTable.length == 0 && compositionText.isEmpty()) {
            if (showCaret && hasFocus()) {
                val fm = g2d.fontMetrics
                g2d.color = Color.WHITE
                g2d.fillRect(0, 0, 1, fm.height)
            }
            return
        }

        val fm = g2d.fontMetrics
        val lineHeight = fm.height
        val ascent = fm.ascent

        val fullText = pieceTable.getText()
        val lines = fullText.split('\n')
        
        if (selectionStart != -1 && selectionStart != selectionEnd) {
            g2d.color = Color(33, 66, 131)
            val selStart = minOf(selectionStart, selectionEnd)
            val selEnd = maxOf(selectionStart, selectionEnd)
            
            var off = 0
            for (i in lines.indices) {
                val lineStart = off
                val lineEnd = off + lines[i].length
                
                if (selStart <= lineEnd && selEnd >= lineStart) {
                    val highlightStartIdx = maxOf(0, selStart - lineStart)
                    val highlightEndIdx = minOf(lines[i].length, selEnd - lineStart)
                    val prefixStr = lines[i].substring(0, highlightStartIdx)
                    val highlightStr = lines[i].substring(highlightStartIdx, highlightEndIdx)
                    
                    val px = fm.stringWidth(prefixStr)
                    val pw = fm.stringWidth(highlightStr)
                    val py = i * lineHeight
                    
                    g2d.fillRect(px, py, if (pw > 0) pw else fm.charWidth(' '), lineHeight)
                }
                
                off += lines[i].length + 1
            }
        }

        g2d.color = Color(169, 183, 198)
        var currentOff = 0
        var caretX = 0
        var caretY = 0
        var foundCaret = false

        for (i in lines.indices) {
            val lineStr = lines[i]
            val y = i * lineHeight + ascent
            g2d.drawString(lineStr, 0, y)

            val lineStart = currentOff
            val lineEnd = currentOff + lineStr.length
            
            if (hoveredWordBounds != null) {
                val wStart = hoveredWordBounds!!.first
                val wEnd = hoveredWordBounds!!.last + 1
                
                if (wEnd > lineStart && wStart < lineEnd) {
                    val overlapStart = maxOf(0, wStart - lineStart)
                    val overlapEnd = minOf(lineStr.length, wEnd - lineStart)
                    
                    val px = fm.stringWidth(lineStr.substring(0, overlapStart))
                    val pw = fm.stringWidth(lineStr.substring(overlapStart, overlapEnd))
                    val pyUnderline = y + 2
                    
                    g2d.color = Color(88, 157, 246)
                    g2d.drawLine(px, pyUnderline, px + pw, pyUnderline)
                    g2d.color = Color(169, 183, 198)
                }
            }

            if (caretOffset in lineStart..lineEnd) {
                val prefix = lineStr.substring(0, caretOffset - lineStart)
                caretX = fm.stringWidth(prefix)
                caretY = i * lineHeight
                foundCaret = true
                
                if (compositionText.isNotEmpty()) {
                    g2d.color = Color.YELLOW
                    g2d.drawString(compositionText, caretX, y)
                    g2d.color = Color(169, 183, 198)
                }
            }
            currentOff += lineStr.length + 1
        }
        
        if (showCaret && hasFocus() && foundCaret) {
            g2d.color = Color.WHITE
            val cx = caretX + if (compositionText.isNotEmpty()) fm.stringWidth(compositionText) else 0
            g2d.fillRect(cx, caretY, 1, lineHeight)
        }
    }
}