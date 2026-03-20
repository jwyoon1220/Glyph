package io.github.jwyoon1220.glyph

import com.formdev.flatlaf.FlatDarculaLaf
import io.github.jwyoon1220.glyph.data.StorageRepository
import io.github.jwyoon1220.glyph.search.DictionaryClient
import io.github.jwyoon1220.glyph.search.FileWatcher
import io.github.jwyoon1220.glyph.search.LuceneSearcher
import io.github.jwyoon1220.glyph.vcs.GitManager
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder

class GlyphMainFrame : JFrame("Glyph - Narrative Development Environment") {

    // Services
    private val dataRoot = File(System.getProperty("user.dir"), "glyph_data")
    private val repo = StorageRepository(dataRoot)
    private val gitManager = GitManager(dataRoot)
    private val dictClient = DictionaryClient()
    private val luceneSearcher = LuceneSearcher()
    private val fileWatcher = FileWatcher(dataRoot, luceneSearcher, repo)

    private val rootPanel = JPanel(BorderLayout())
    private val centerEditorArea = GlyphTextArea(dictClient)
    
    // Coroutine Scope for UI
    private val uiScope = CoroutineScope(Dispatchers.Swing + Job())

    init {
        isUndecorated = true
        size = Dimension(1280, 800)
        minimumSize = Dimension(800, 600)
        setLocationRelativeTo(null)
        defaultCloseOperation = EXIT_ON_CLOSE

        // Setup UI
        rootPanel.background = Color(43, 43, 43)
        rootPanel.add(createCustomTitleBar(), BorderLayout.NORTH)
        
        // Build the tool-window layout via ToolWindowManager (interface-based injection)
        val toolWindowManager = ToolWindowManager(
            left   = object : ToolWindowPanel { override val component = createProjectToolWindow() },
            center = object : ToolWindowPanel { override val component = centerEditorArea },
            right  = object : ToolWindowPanel { override val component = createCharacterToolWindow() },
            bottom = object : ToolWindowPanel { override val component = createVersionControlToolWindow() }
        )
        rootPanel.add(toolWindowManager.buildLayout(), BorderLayout.CENTER)

        WindowResizer(rootPanel)
        contentPane = rootPanel

        // Wire up typing-inactivity auto-commit
        centerEditorArea.addTypingStoppedListener {
            uiScope.launch(Dispatchers.IO) {
                gitManager.commitAll("Auto-save snapshot")
            }
        }

        // Wire up Ctrl+Z git-based undo
        centerEditorArea.onUndoRequested = ::performGitUndo

        // Start the file watcher for real-time Lucene indexing
        fileWatcher.start()
    }

    private fun performGitUndo() {
        val history = gitManager.getUndoHistory()
        if (history.size >= 2) {
            try {
                gitManager.restoreSnapshot(history[1])
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- TOOL WINDOWS ---

    private fun createCharacterToolWindow(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(60, 63, 65)
        panel.preferredSize = Dimension(300, 0)
        panel.border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Character / World Settings")
        (panel.border as javax.swing.border.TitledBorder).titleColor = Color.LIGHT_GRAY

        val topBar = JPanel(BorderLayout())
        topBar.isOpaque = false
        val inputField = JTextField()
        inputField.background = Color(69, 73, 74)
        inputField.foreground = Color.WHITE
        inputField.caretColor = Color.WHITE
        val searchBtn = JButton("Search")
        topBar.add(inputField, BorderLayout.CENTER)
        topBar.add(searchBtn, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        val resultArea = JTextArea()
        resultArea.background = Color(43, 43, 43)
        resultArea.foreground = Color.WHITE
        resultArea.font = Font("SansSerif", Font.PLAIN, 14)
        resultArea.lineWrap = true
        resultArea.wrapStyleWord = true
        resultArea.isEditable = false
        panel.add(JScrollPane(resultArea), BorderLayout.CENTER)

        val executeSearch = {
            val query = inputField.text
            if (query.isNotBlank()) {
                resultArea.text = "Searching '$query'..."
                uiScope.launch {
                    val results = dictClient.searchWord(query)
                    if (results.isEmpty()) {
                        resultArea.text = "No results found for '$query'."
                    } else {
                        val sb = StringBuilder()
                        for (item in results) {
                            sb.append("【${item.word}】 " + if (item.pos.isNotEmpty()) "[${item.pos}]\n" else "\n")
                            sb.append("${item.sense.definition}\n\n")
                        }
                        resultArea.text = sb.toString()
                    }
                }
            }
        }
        searchBtn.addActionListener { executeSearch() }
        inputField.addActionListener { executeSearch() }

        return panel
    }

    private fun createProjectToolWindow(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(60, 63, 65)
        panel.preferredSize = Dimension(200, 0)
        panel.border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Project")
        (panel.border as javax.swing.border.TitledBorder).titleColor = Color.LIGHT_GRAY

        val listModel = DefaultListModel<String>()
        listModel.addElement("Chapter 1.md")
        listModel.addElement("Chapter 2.md")
        listModel.addElement("ProjectSettings.glb")

        val list = JList(listModel)
        list.font = Font("Noto Sans KR", Font.PLAIN, 14)
        list.background = Color(43, 43, 43)
        list.foreground = Color(169, 183, 198)
        panel.add(JScrollPane(list), BorderLayout.CENTER)
        return panel
    }

    private fun createVersionControlToolWindow(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(60, 63, 65)
        panel.preferredSize = Dimension(0, 150)
        panel.border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Version Control (Git Reflog)")
        (panel.border as javax.swing.border.TitledBorder).titleColor = Color.LIGHT_GRAY

        val logArea = JTextArea()
        logArea.background = Color(43, 43, 43)
        logArea.foreground = Color.GREEN
        logArea.font = Font("Monospaced", Font.PLAIN, 12)
        logArea.isEditable = false
        
        val btnRefresh = JButton("Refresh Log")
        btnRefresh.addActionListener {
            val history = gitManager.getUndoHistory()
            logArea.text = if (history.isEmpty()) "No commits yet." else history.joinToString("\n") { "Commit: $it" }
        }
        
        panel.add(btnRefresh, BorderLayout.NORTH)
        panel.add(JScrollPane(logArea), BorderLayout.CENTER)
        return panel
    }

    // --- TITLE BAR ---

    private fun createCustomTitleBar(): JPanel {
        val titleBar = object : JPanel(BorderLayout()) {
            private var initialClick: Point? = null
            init {
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        initialClick = e.point
                    }
                })
                addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        val frame = SwingUtilities.getWindowAncestor(e.component) as? JFrame ?: return
                        if (initialClick != null && frame.extendedState != JFrame.MAXIMIZED_BOTH) {
                            val thisX = frame.location.x
                            val thisY = frame.location.y
                            val xMoved = e.x - initialClick!!.x
                            val yMoved = e.y - initialClick!!.y
                            frame.setLocation(thisX + xMoved, thisY + yMoved)
                        }
                    }
                })
            }
        }
        titleBar.preferredSize = Dimension(width, 30)
        titleBar.background = Color(60, 63, 65)

        val titleLabel = JLabel(" Glyph Project: Untitled", SwingConstants.LEFT)
        titleLabel.foreground = Color.LIGHT_GRAY
        titleBar.add(titleLabel, BorderLayout.CENTER)

        val controlPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        controlPanel.isOpaque = false
        
        fun createButton(text: String, hoverColor: Color, action: () -> Unit): JButton {
            return JButton(text).apply {
                isFocusable = false
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = true
                background = Color(60, 63, 65)
                foreground = Color.LIGHT_GRAY
                preferredSize = Dimension(45, 30)
                font = Font("SansSerif", Font.PLAIN, 12)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { background = hoverColor }
                    override fun mouseExited(e: MouseEvent) { background = Color(60, 63, 65) }
                })
                addActionListener { action() }
            }
        }

        val btnMin = createButton("-", Color(80, 83, 85)) { 
            val frame = SwingUtilities.getWindowAncestor(titleBar) as? JFrame
            frame?.extendedState = JFrame.ICONIFIED 
        }
        val btnMax = createButton("□", Color(80, 83, 85)) {
            val frame = SwingUtilities.getWindowAncestor(titleBar) as? JFrame
            if (frame != null) {
                frame.extendedState = if (frame.extendedState == JFrame.MAXIMIZED_BOTH) JFrame.NORMAL else JFrame.MAXIMIZED_BOTH
            }
        }
        val btnClose = createButton("X", Color(232, 17, 35)) { 
            val frame = SwingUtilities.getWindowAncestor(titleBar) as? JFrame
            frame?.dispose()
            System.exit(0)
        }

        controlPanel.add(btnMin)
        controlPanel.add(btnMax)
        controlPanel.add(btnClose)
        titleBar.add(controlPanel, BorderLayout.EAST)

        return titleBar
    }
}

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")
    
    SwingUtilities.invokeLater {
        FlatDarculaLaf.setup()
        val frame = GlyphMainFrame()
        frame.isVisible = true
    }
}