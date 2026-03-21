package io.github.jwyoon1220.glyph

import io.github.jwyoon1220.glyph.hangul.HangulUtil
import io.github.jwyoon1220.glyph.hangul.KoreanMorphemeAnalyzer
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
import java.util.StringTokenizer
import javax.swing.*
import javax.swing.border.LineBorder

class GlyphTextArea(private val dictClient: DictionaryClient) : JComponent(), Scrollable {

    private val pieceTable = PieceTable()
    private val uiScope = CoroutineScope(Dispatchers.Swing + Job())

    private var caretOffset = 0
    private var selectionStart = -1
    private var selectionEnd = -1

    private var compositionText = ""
    private var showCaret = true

    private var hoveredWordBounds: IntRange? = null
    private var isCtrlDownForHover: Boolean = false
    private var popupWindow: JPopupMenu? = null
    private var hoverTimer: Timer? = null
    private var hideTimer: Timer? = null

    /** Listeners notified after a period of typing inactivity (for auto-commit). */
    private val typingStoppedListeners = mutableListOf<() -> Unit>()
    private var inactivityTimer: Timer? = null

    /** Optional undo handler supplied by the owner (e.g. git-based restore). */
    var onUndoRequested: (() -> Unit)? = null

    var text: String
        get() = pieceTable.getText(0, pieceTable.length)
        set(value) {
            pieceTable.setText(value)
            caretOffset = minOf(caretOffset, value.length)
            clearSelection()
            compositionText = ""
            clearSelection()
            compositionText = ""
            revalidate()
            repaint()
        }

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
                val offset = getOffsetFromPoint(e.x, e.y)
                val bounds = getWordBoundsAt(offset)
                
                val wasCtrlDown = isCtrlDownForHover
                isCtrlDownForHover = e.isControlDown && bounds != null

                if (bounds != hoveredWordBounds) {
                    hoveredWordBounds = bounds
                    repaint()

                    hoverTimer?.stop()
                    scheduleHidePopup()

                    if (bounds != null) {
                        scheduleHoverInfo(bounds, if (e.isControlDown) 300 else 500, e.x, e.y)
                    }
                } else if (bounds != null && wasCtrlDown != isCtrlDownForHover) {
                    repaint()
                    if (isCtrlDownForHover) {
                        scheduleHoverInfo(bounds, 300, e.x, e.y)
                    }
                } else if (bounds == null) {
                    if (popupWindow?.isVisible == true) {
                        scheduleHidePopup()
                    }
                }

                cursor = if (isCtrlDownForHover) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            }
        })
    }

    private fun getWordBoundsAt(offset: Int): IntRange? {
        val text = pieceTable.getText(0, pieceTable.length)
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

    private fun scheduleHidePopup() {
        hideTimer?.stop()
        if (popupWindow?.isVisible != true) return
        
        hideTimer = Timer(300) {
            var isInPopup = false
            try {
                val mouseLoc = MouseInfo.getPointerInfo()?.location
                if (mouseLoc != null) {
                    val popupScreenLoc = popupWindow?.locationOnScreen
                    val size = popupWindow?.size
                    if (popupScreenLoc != null && size != null) {
                        // Add 10px margin around the popup for leniency
                        val rect = Rectangle(popupScreenLoc.x - 10, popupScreenLoc.y - 10, size.width + 20, size.height + 20)
                        if (rect.contains(mouseLoc)) isInPopup = true
                    }
                }
            } catch (ex: Exception) {}
            
            if (!isInPopup) {
                popupWindow?.isVisible = false
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun scheduleHoverInfo(bounds: IntRange, delay: Int, pointerX: Int, pointerY: Int) {
        hoverTimer?.stop()
        if (popupWindow?.isVisible == true) return // Do not reschedule if already showing
        
        hoverTimer = Timer(delay) {
            val rawWord = pieceTable.getText(0, pieceTable.length).substring(bounds.first, bounds.last + 1)
            val st = StringTokenizer(rawWord, " .,!?\n\t")
            if (st.hasMoreTokens()) {
                val cleanWord = st.nextToken()
                val analyzer = io.github.jwyoon1220.glyph.hangul.KoreanMorphemeAnalyzer(cleanWord)
                val targetWord = analyzer.extractNouns().firstOrNull() ?: cleanWord
                
                val sbToken = java.lang.StringBuilder()
                for (token in analyzer.getTokens()) {
                    val color = when {
                        token.pos.startsWith("N") -> "#9876AA" // Noun: Purple
                        token.pos.startsWith("J") -> "#CC7832" // Josa: Orange
                        token.pos.startsWith("V") -> "#FFC66D" // Verb: Yellow
                        token.pos.startsWith("M") -> "#6A8759" // Modifier: Green
                        else -> "#A9B7C6" // Default Gray
                    }
                    sbToken.append("<span style='color: $color;'>${token.morph}</span>")
                }
                
                showDictionaryPopup(targetWord, sbToken.toString(), pointerX, pointerY)
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun showDictionaryPopup(word: String, styledWord: String, x: Int, y: Int) {
        val popup = JPopupMenu()
        popup.isFocusable = false
        popup.border = LineBorder(Color(60, 63, 65), 1)
        
        val textPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            background = Color(43, 45, 48)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        val scrollPane = JScrollPane(textPane).apply {
            preferredSize = Dimension(400, 250)
            border = null
        }
        
        val closePopupAdapter = object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                scheduleHidePopup()
            }
            override fun mouseEntered(e: MouseEvent) {
                hideTimer?.stop()
            }
        }
        textPane.addMouseListener(closePopupAdapter)
        scrollPane.addMouseListener(closePopupAdapter)

        popup.add(scrollPane)
        popup.show(this, x, y + 20)
        popupWindow = popup

        val htmlPrefix = "<html><body style='font-family: sans-serif; font-size: 14px; color: #A9B7C6; margin: 0;'>"
        val htmlSuffix = "</body></html>"
        val headerHtml = "<div style='font-size: 16px; margin-bottom: 6px;'><b>$styledWord</b></div><hr style='border: 0; border-top: 1px solid #4C5052; margin-top: 0px; margin-bottom: 8px;'/>"

        textPane.text = "$htmlPrefix$headerHtml<i>단어 뜻을 찾는 중...</i>$htmlSuffix"

        uiScope.launch {
            val results = dictClient.searchWord(word)
            if (results.isEmpty()) {
                textPane.text = htmlPrefix + headerHtml + "사전에서 뜻을 찾을 수 없습니다." + htmlSuffix
            } else {
                val sb = java.lang.StringBuilder()
                for (item in results) {
                    sb.append("<div style='margin-bottom: 4px;'>")
                    sb.append("<span style='color: #6AAB73; font-weight: bold;'>【${item.word}】</span> ")
                    if (item.pos.isNotEmpty()) sb.append("<span style='color: #E8BF6A;'>[${item.pos}]</span> ")
                    sb.append("</div>")
                    sb.append("<div style='margin-bottom: 12px; margin-left: 10px; line-height: 1.4;'>${item.sense.definition}</div>")
                }
                textPane.text = htmlPrefix + headerHtml + sb.toString() + htmlSuffix
                textPane.caretPosition = 0
            }
        }
    }

    private fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
    }

    private fun deleteSelection() {
        if (selectionStart == -1 || selectionStart == selectionEnd) return
        val start = maxOf(0, minOf(selectionStart, selectionEnd))
        val end = minOf(pieceTable.length, maxOf(selectionStart, selectionEnd))
        if (start < end) {
            pieceTable.delete(start, end - start)
            caretOffset = start
        }
        clearSelection()
    }

    private fun copyToClipboard() {
        if (selectionStart == -1 || selectionStart == selectionEnd) return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        val text = pieceTable.getText(0, pieceTable.length).substring(start, end)
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
        return pieceTable.getText(0, pieceTable.length).split('\n')
    }

    private fun moveCaretLine(dir: Int, shift: Boolean) {
        val text = pieceTable.getText(0, pieceTable.length)
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
                val text = if (safeBegin <= safeEnd) pieceTable.getText(
                    0,
                    pieceTable.length
                ).substring(safeBegin, safeEnd) else ""
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
        
        g2d.color = Color(1, 22, 39) // Night Owl background
        g2d.fillRect(0, 0, width, height)

        if (pieceTable.length == 0 && compositionText.isEmpty()) {
            if (showCaret && hasFocus()) {
                val fm = g2d.fontMetrics
                g2d.color = Color(247, 140, 108) // Night Owl Caret
                g2d.fillRect(0, 0, 1, fm.height)
            }
            return
        }

        val fm = g2d.fontMetrics
        val lineHeight = fm.height
        val ascent = fm.ascent

        val fullText = pieceTable.getText(0, pieceTable.length)
        val lines = fullText.split('\n')
        
        if (selectionStart != -1 && selectionStart != selectionEnd) {
            g2d.color = Color(29, 59, 83) // Night Owl Selection Background
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

        g2d.color = Color(214, 222, 235) // Night Owl Foreground
        var currentOff = 0
        var caretX = 0
        var caretY = 0
        var foundCaret = false

        for (i in lines.indices) {
            val lineStr = lines[i]
            val y = i * lineHeight + ascent
            
            val lineStart = currentOff
            val lineEnd = currentOff + lineStr.length
            
            if (caretOffset in lineStart..lineEnd) {
                foundCaret = true
                caretY = i * lineHeight
                
                val relOff = caretOffset - lineStart
                val prefix = lineStr.substring(0, relOff)
                val suffix = lineStr.substring(relOff)
                
                // 1. Draw Prefix
                g2d.drawString(prefix, 0, y)
                val px = fm.stringWidth(prefix)
                caretX = px
                
                var currentX = px
                
                // 2. Draw Composition Text
                if (compositionText.isNotEmpty()) {
                    g2d.color = Color(247, 140, 108) // Night Owl Composition Highlight
                    g2d.drawString(compositionText, px, y)
                    
                    val compWidth = fm.stringWidth(compositionText)
                    // Draw composition underline (IntelliJ-ish)
                    g2d.drawLine(px, y + 2, px + compWidth, y + 2)
                    
                    g2d.color = Color(214, 222, 235)
                    currentX += compWidth
                }
                
                // 3. Draw Suffix (Offset by composition width)
                g2d.drawString(suffix, currentX, y)

                // Handover caret position for later caret drawing
                // (cx is usually after composition)
            } else {
                g2d.drawString(lineStr, 0, y)
            }
            
            // Draw Hover Underline if any
            if (hoveredWordBounds != null && isCtrlDownForHover) {
                val wStart = hoveredWordBounds!!.first
                val wEnd = hoveredWordBounds!!.last + 1
                
                if (wEnd > lineStart && wStart < lineEnd) {
                    val overlapStart = maxOf(0, wStart - lineStart)
                    val overlapEnd = minOf(lineStr.length, wEnd - lineStart)
                    
                    // Simple underline for word hover (Note: this doesn't account for composition offset 
                    // because hover is unlikely during composition, but for correctness we draw on raw lineStr)
                    val hPrefix = lineStr.substring(0, overlapStart)
                    val hWord = lineStr.substring(overlapStart, overlapEnd)
                    val hx = fm.stringWidth(hPrefix)
                    val hw = fm.stringWidth(hWord)
                    
                    g2d.color = Color(130, 170, 255)
                    g2d.drawLine(hx, y + 2, hx + hw, y + 2)
                    g2d.color = Color(214, 222, 235)
                }
            }
            currentOff += lineStr.length + 1
        }
        
        if (showCaret && hasFocus() && foundCaret) {
            g2d.color = Color(247, 140, 108) // Night Owl Caret
            val cx = caretX + (if (compositionText.isNotEmpty()) fm.stringWidth(compositionText) else 0)
            g2d.fillRect(cx, caretY, 1, lineHeight)
        }

        // Auto-scroll after paint to ensure layout is ready
        if (hasFocus()) {
            SwingUtilities.invokeLater { scrollToCaret() }
        }
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val lines = getLines()
        val h = lines.size * fm.height + 200
        val w = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0
        return Dimension(maxOf(400, w + 100), maxOf(400, h))
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 20
    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 100
    override fun getScrollableTracksViewportWidth(): Boolean = false
    override fun getScrollableTracksViewportHeight(): Boolean = false

    private fun scrollToCaret() {
        val fm = getFontMetrics(font)
        val lines = getLines()
        var currentOff = 0
        for (i in lines.indices) {
            val len = lines[i].length + 1
            if (caretOffset >= currentOff && caretOffset < currentOff + len) {
                val linePart = lines[i].substring(0, caretOffset - currentOff)
                val cx = fm.stringWidth(linePart)
                val cy = i * fm.height
                scrollRectToVisible(Rectangle(cx, cy, 2, fm.height + 20))
                break
            }
            currentOff += len
        }
    }

    override fun repaint() {
        revalidate()
        super.repaint()
    }
}