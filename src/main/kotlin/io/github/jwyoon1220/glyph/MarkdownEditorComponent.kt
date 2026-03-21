package io.github.jwyoon1220.glyph

import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A split-panel component for editing Markdown (and .glh / Glyph Header) files.
 * Left side: plain-text editor. Right side: live HTML preview.
 */
class MarkdownEditorComponent : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        background = Color(1, 22, 39)
        foreground = Color(214, 222, 235)
        caretColor = Color(247, 140, 108)
        font = Font("Monospaced", Font.PLAIN, 15)
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
    }

    private val preview = JEditorPane("text/html", "").apply {
        isEditable = false
        background = Color(10, 30, 50)
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
    }

    var text: String
        get() = textArea.text
        set(value) {
            textArea.text = value
            refreshPreview()
        }

    /** Notified when typing has stopped (used for auto-save). */
    var onTypingStopped: (() -> Unit)? = null
    private var inactivityTimer: Timer? = null

    init {
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(textArea).apply {
                border = BorderFactory.createEmptyBorder()
                viewport.background = Color(1, 22, 39)
            },
            JScrollPane(preview).apply {
                border = BorderFactory.createEmptyBorder()
                viewport.background = Color(10, 30, 50)
            }
        ).apply {
            resizeWeight = 0.5
            border = BorderFactory.createEmptyBorder()
            dividerSize = 4
            isContinuousLayout = true
        }
        add(split, BorderLayout.CENTER)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
    }

    private fun onTextChanged() {
        refreshPreview()
        inactivityTimer?.stop()
        inactivityTimer = Timer(3000) {
            onTypingStopped?.invoke()
        }.also { it.isRepeats = false; it.start() }
    }

    private fun refreshPreview() {
        val html = markdownToHtml(textArea.text)
        val styled = """
            <html><head><style>
              body { background:#0a1e32; color:#d6dee7; font-family:sans-serif; font-size:15px; margin:12px 16px; line-height:1.6; }
              h1,h2,h3,h4,h5,h6 { color:#82aaff; }
              a { color:#80cbc4; }
              code { background:#1d3b53; padding:1px 4px; border-radius:3px; }
              pre { background:#1d3b53; padding:10px; border-radius:6px; }
              blockquote { border-left:3px solid #82aaff; margin-left:8px; padding-left:12px; color:#7a8fa6; }
              hr { border:none; border-top:1px solid #1d3b53; }
              table { border-collapse:collapse; }
              th,td { border:1px solid #1d3b53; padding:4px 10px; }
              th { background:#1d3b53; }
            </style></head><body>$html</body></html>
        """.trimIndent()
        preview.text = styled
        preview.caretPosition = 0
    }

    companion object {
        /** Minimal Markdown → HTML converter (covers common patterns). */
        fun markdownToHtml(md: String): String {
            val lines = md.split('\n')
            val sb = StringBuilder()
            var inCodeBlock = false
            var inList = false

            for (line in lines) {
                // Fenced code block toggle
                if (line.startsWith("```")) {
                    if (inList) { sb.append("</ul>"); inList = false }
                    if (inCodeBlock) { sb.append("</code></pre>"); inCodeBlock = false }
                    else { sb.append("<pre><code>"); inCodeBlock = true }
                    continue
                }
                if (inCodeBlock) {
                    sb.append(escapeHtml(line)).append('\n')
                    continue
                }

                // Close list if line is not a list item
                if (inList && !line.startsWith("- ") && !line.startsWith("* ")) {
                    sb.append("</ul>"); inList = false
                }

                val result = when {
                    line.startsWith("# ")     -> "<h1>${inline(line.substring(2))}</h1>"
                    line.startsWith("## ")    -> "<h2>${inline(line.substring(3))}</h2>"
                    line.startsWith("### ")   -> "<h3>${inline(line.substring(4))}</h3>"
                    line.startsWith("#### ")  -> "<h4>${inline(line.substring(5))}</h4>"
                    line.startsWith("##### ") -> "<h5>${inline(line.substring(6))}</h5>"
                    line.startsWith("> ")     -> "<blockquote>${inline(line.substring(2))}</blockquote>"
                    line == "---" || line == "***" || line == "___" -> "<hr/>"
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        if (!inList) { sb.append("<ul>"); inList = true }
                        "<li>${inline(line.substring(2))}</li>"
                    }
                    line.isBlank() -> "<br/>"
                    else -> "<p>${inline(line)}</p>"
                }
                sb.append(result)
            }
            if (inList) sb.append("</ul>")
            if (inCodeBlock) sb.append("</code></pre>")
            return sb.toString()
        }

        /** Apply inline markdown: bold, italic, code, links */
        private fun inline(s: String): String {
            var r = escapeHtml(s)
            // Bold + italic: ***text***
            r = r.replace(Regex("""\*\*\*(.+?)\*\*\*"""), "<strong><em>$1</em></strong>")
            // Bold: **text**
            r = r.replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            // Italic: *text* or _text_
            r = r.replace(Regex("""\*(.+?)\*"""), "<em>$1</em>")
            r = r.replace(Regex("""_(.+?)_"""), "<em>$1</em>")
            // Inline code: `code`
            r = r.replace(Regex("""`(.+?)`"""), "<code>$1</code>")
            // Links: [text](url)
            r = r.replace(Regex("""\[(.+?)]\((.+?)\)"""), """<a href="$2">$1</a>""")
            return r
        }

        private fun escapeHtml(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
