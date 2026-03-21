package io.github.jwyoon1220.glyph

import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * IntelliJ-style floating overlay for creating new files/folders.
 * Shows a title, a text-field for the name, and a list of file types.
 *
 * Usage:
 *   NewFileOverlay.show(owner, anchorPoint, targetDir) { createdFile ->
 *       // handle the new file
 *   }
 */
object NewFileOverlay {

    private val NIGHT_OWL_BG = Color(14, 28, 42)
    private val NIGHT_OWL_FIELD_BG = Color(24, 44, 62)
    private val NIGHT_OWL_SELECTION = Color(45, 100, 170)
    private val NIGHT_OWL_BORDER = Color(60, 100, 140)
    private val NIGHT_OWL_TEXT = Color(214, 222, 235)
    private val NIGHT_OWL_ACCENT = Color(130, 170, 255)

    data class FileType(val label: String, val icon: String, val iconColor: Color, val extension: String, val isDir: Boolean = false)

    private val FILE_TYPES = listOf(
        FileType("에피소드", "✎", Color(175, 220, 255), ".gle"),
        FileType("위키 페이지", "⊕", Color(199, 146, 234), ".glw"),
        FileType("폴더", "▶", Color(255, 200, 100), "", isDir = true)
    )

    fun show(owner: Window, anchorPoint: Point, targetDir: File, onCreate: (File) -> Unit) {
        val dialog = JDialog(owner).apply {
            isUndecorated = true
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0) // transparent root
        }

        val panel = buildPanel(dialog, targetDir, anchorPoint, onCreate)

        dialog.contentPane = panel
        dialog.pack()

        // Position it near the anchor point, but ensure it stays on screen
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .maximumWindowBounds
        var px = anchorPoint.x
        var py = anchorPoint.y
        if (px + dialog.width > screen.x + screen.width) px = screen.x + screen.width - dialog.width
        if (py + dialog.height > screen.y + screen.height) py = screen.y + screen.height - dialog.height
        dialog.setLocation(px, py)
        dialog.isVisible = true
    }

    private fun buildPanel(dialog: JDialog, targetDir: File, anchorPoint: Point, onCreate: (File) -> Unit): JPanel {
        val panel = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = NIGHT_OWL_BG
                g2.fillRoundRect(0, 0, width, height, 12, 12)
            }
        }.apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(NIGHT_OWL_BORDER, 1, true),
                EmptyBorder(12, 14, 12, 14)
            )
            preferredSize = Dimension(380, 220)
        }

        // Title
        val title = JLabel("새 파일").apply {
            font = Font("SansSerif", Font.BOLD, 16)
            foreground = Color.WHITE
            border = EmptyBorder(0, 2, 10, 0)
        }

        // Name input field
        val nameField = JTextField("Untitled").apply {
            font = Font("SansSerif", Font.PLAIN, 14)
            background = NIGHT_OWL_FIELD_BG
            foreground = NIGHT_OWL_TEXT
            caretColor = Color.WHITE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(NIGHT_OWL_ACCENT, 1, true),
                EmptyBorder(5, 8, 5, 8)
            )
        }
        // Select all text on show
        SwingUtilities.invokeLater { nameField.selectAll() }

        // File type list
        val listModel = DefaultListModel<FileType>()
        FILE_TYPES.forEach(listModel::addElement)

        val typeList = JList(listModel).apply {
            background = NIGHT_OWL_BG
            foreground = NIGHT_OWL_TEXT
            selectionBackground = NIGHT_OWL_SELECTION
            selectionForeground = Color.WHITE
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            font = Font("SansSerif", Font.PLAIN, 14)
            border = EmptyBorder(4, 0, 4, 0)
            fixedCellHeight = 34

            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val ft = value as FileType
                    val label = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        isOpaque = true
                        background = if (isSelected) NIGHT_OWL_SELECTION else NIGHT_OWL_BG

                        val iconLabel = JLabel(ft.icon).apply {
                            foreground = ft.iconColor
                            font = Font("SansSerif", Font.BOLD, 14)
                            preferredSize = Dimension(18, 18)
                        }
                        val textLabel = JLabel(ft.label).apply {
                            foreground = if (isSelected) Color.WHITE else NIGHT_OWL_TEXT
                            font = Font("SansSerif", Font.PLAIN, 14)
                        }
                        add(iconLabel)
                        add(textLabel)
                    }
                    return label
                }
            }
        }

        // Dismiss on Escape
        val escaper = { dialog.dispose() }
        val escapeKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(escapeKey, "escape")
        panel.actionMap.put("escape", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = escaper()
        })

        // Confirm action
        val confirm = confirmLabel@{
            val ft = typeList.selectedValue ?: return@confirmLabel
            val rawName = nameField.text.trim()
            if (rawName.isNotEmpty()) {
                val fileName = if (ft.isDir || rawName.contains(".")) rawName else rawName + ft.extension
                val file = File(targetDir, fileName)
                if (ft.isDir) file.mkdir() else file.createNewFile()
                dialog.dispose()
                onCreate(file)
            }
        }

        nameField.addActionListener { confirm() } // Enter in text field
        typeList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) confirm()
            }
        })
        typeList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) confirm()
            }
        })

        // Layout
        val top = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(nameField, BorderLayout.CENTER)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(JScrollPane(typeList).apply {
            border = BorderFactory.createLineBorder(NIGHT_OWL_BORDER, 1, true)
            viewport.background = NIGHT_OWL_BG
            isOpaque = false
        }, BorderLayout.CENTER)

        // Close when clicking outside
        dialog.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {}
            override fun windowLostFocus(e: WindowEvent?) = dialog.dispose()
        })

        return panel
    }
}
