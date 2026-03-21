package io.github.jwyoon1220.glyph

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme
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

class GlyphMainFrame(val dataRoot: File) : JFrame("Glyph - ${dataRoot.name}") {

    // Services
    private val repo = StorageRepository(dataRoot)
    private val gitManager = GitManager(dataRoot)
    private val dictClient = DictionaryClient()
    private val luceneSearcher = LuceneSearcher()
    private val fileWatcher = FileWatcher(dataRoot, luceneSearcher, repo)
    private val geminiClient = GeminiClient(
        java.util.prefs.Preferences.userNodeForPackage(GlyphMainFrame::class.java)
            .get("gemini_api_key", "")
    )

    private val rootPanel = JPanel(BorderLayout())

    private val tabbedPane = JTabbedPane().apply {
        putClientProperty("JTabbedPane.tabClosable", true)
        putClientProperty("JTabbedPane.tabCloseCallback", java.util.function.BiConsumer<JTabbedPane, Int> { tabPane, tabIndex ->
            val comp = tabPane.getComponentAt(tabIndex)
            tabPane.removeTabAt(tabIndex)
            openEditors.values.remove(comp)
        })
    }
    private val openEditors = mutableMapOf<String, Component>()

    val activeFilePath: String
        get() {
            val comp = tabbedPane.selectedComponent ?: return ""
            return openEditors.entries.firstOrNull { it.value == comp }?.key ?: ""
        }

    // Status bar labels
    private val branchLabel = JLabel().apply {
        font = Font("SansSerif", Font.PLAIN, 12)
        foreground = Color(130, 170, 255)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }
    private val remoteLabel = JLabel().apply {
        font = Font("SansSerif", Font.PLAIN, 12)
        foreground = Color(169, 183, 198)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    // Coroutine Scope for UI
    private val uiScope = CoroutineScope(Dispatchers.Swing + Job())

    init {
        size = Dimension(1280, 800)
        minimumSize = Dimension(800, 600)
        setLocationRelativeTo(null)
        defaultCloseOperation = EXIT_ON_CLOSE

        // Setup UI
        rootPanel.background = Color(1, 22, 39)
        jMenuBar = createMenuBar()

        // Track recent project
        RecentProjectsManager.addProject(dataRoot)

        // Build the tool-window layout via ToolWindowManager (interface-based injection)
        val toolWindowManager = ToolWindowManager(
            left   = object : ToolWindowPanel { override val component = createProjectToolWindow() },
            center = object : ToolWindowPanel { override val component = tabbedPane },
            right  = object : ToolWindowPanel { override val component = createCharacterToolWindow() },
            bottom = object : ToolWindowPanel { override val component = createVersionControlToolWindow() }
        )
        rootPanel.add(toolWindowManager.buildLayout(), BorderLayout.CENTER)
        rootPanel.add(createStatusBar(), BorderLayout.SOUTH)

        WindowResizer(rootPanel)
        contentPane = rootPanel

        // Start the file watcher for real-time Lucene indexing
        fileWatcher.start()

        // Open untitled by default
        openFile("Untitled.gle")

        // Initial status bar refresh
        refreshStatusBar()
    }

    private fun createStatusBar(): JPanel {
        val bar = JPanel(BorderLayout())
        bar.background = Color(30, 40, 55)
        bar.border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(50, 60, 75))
        bar.preferredSize = Dimension(0, 24)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        left.isOpaque = false
        left.add(JLabel("  ⎇ ").apply { foreground = Color(100, 140, 100); font = Font("SansSerif", Font.PLAIN, 12) })
        left.add(branchLabel)
        left.add(JLabel("|").apply { foreground = Color(60, 70, 85); font = Font("SansSerif", Font.PLAIN, 12) })
        left.add(remoteLabel)
        bar.add(left, BorderLayout.WEST)
        return bar
    }

    fun refreshStatusBar() {
        uiScope.launch(Dispatchers.IO) {
            val branch = gitManager.getCurrentBranch()
            val remote = gitManager.getRemoteUrl()
            withContext(Dispatchers.Swing) {
                branchLabel.text = branch
                remoteLabel.text = if (remote.isBlank()) "리모트 없음" else remote
            }
        }
    }

    private fun openFile(relPath: String) {
        if (openEditors.containsKey(relPath)) {
            tabbedPane.selectedComponent = openEditors[relPath]
            return
        }

        if (relPath.endsWith(".glw")) {
            uiScope.launch(Dispatchers.IO) {
                val fileExists = File(dataRoot, relPath).exists()
                var graph = repo.loadWiki(relPath)
                if (!fileExists || graph == null) {
                    graph = io.github.jwyoon1220.glyph.data.WikiGraph()
                    graph.nodes.add(io.github.jwyoon1220.glyph.data.WikiNode(title = File(relPath).nameWithoutExtension, x = 100, y = 100))
                    repo.saveWiki(relPath, graph)
                    gitManager.commitAll("Initial Wiki mapping ($relPath)")
                }

                withContext(Dispatchers.Swing) {
                    val editor = WikiGraphEditorComponent(graph) { changedGraph ->
                        uiScope.launch(Dispatchers.IO) {
                            repo.saveWiki(relPath, changedGraph)
                        }
                    }
                    editor.onTypingStopped = {
                        uiScope.launch(Dispatchers.IO) {
                            gitManager.commitAll("Auto-save Wiki tree ($relPath)")
                        }
                    }

                    openEditors[relPath] = editor
                    tabbedPane.addTab(File(relPath).name, editor)
                    tabbedPane.selectedComponent = editor
                }
            }
        } else {
            val editor = GlyphTextArea(dictClient)
            editor.geminiClient = geminiClient
            editor.addTypingStoppedListener {
                uiScope.launch(Dispatchers.IO) {
                    repo.saveFile(relPath, editor.text)
                    gitManager.commitAll("Auto-save snapshot ($relPath)")
                }
            }
            editor.onUndoRequested = {
                uiScope.launch(Dispatchers.IO) {
                    if (gitManager.undo()) {
                        val text = repo.loadFile(relPath)
                        withContext(Dispatchers.Swing) {
                            editor.text = text
                        }
                    }
                }
            }

            val scrollEditor = JScrollPane(editor).apply {
                border = BorderFactory.createEmptyBorder()
                viewport.background = Color(1, 22, 39)
            }
            openEditors[relPath] = scrollEditor
            tabbedPane.addTab(File(relPath).name, scrollEditor)
            tabbedPane.selectedComponent = scrollEditor

            uiScope.launch(Dispatchers.IO) {
                val fileExists = File(dataRoot, relPath).exists()
                if (!fileExists) {
                    repo.saveFile(relPath, "")
                    gitManager.commitAll("Initial commit ($relPath)")
                }
                val text = repo.loadFile(relPath)
                withContext(Dispatchers.Swing) {
                    editor.text = text
                }
            }
        }
    }

    /** Reload every open text editor from disk (used after git rollback). */
    private fun reloadAllEditors() {
        for ((relPath, comp) in openEditors) {
            val editor = findEditorInComponent(comp) ?: continue
            uiScope.launch(Dispatchers.IO) {
                val text = repo.loadFile(relPath)
                withContext(Dispatchers.Swing) {
                    editor.text = text
                }
            }
        }
    }

    private fun findEditorInComponent(comp: Component): GlyphTextArea? {
        if (comp is GlyphTextArea) return comp
        if (comp is JScrollPane) {
            val view = comp.viewport.view
            if (view is GlyphTextArea) return view
        }
        return null
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
        panel.background = Color(1, 22, 39)
        panel.preferredSize = Dimension(250, 0)
        panel.border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Project Explorer")
        (panel.border as javax.swing.border.TitledBorder).titleColor = Color.LIGHT_GRAY

        val explorer = ProjectExplorerComponent(dataRoot) { file ->
            val relative = file.toRelativeString(dataRoot).replace("\\", "/")
            openFile(relative)
        }
        panel.add(explorer, BorderLayout.CENTER)
        return panel
    }

    private fun createVersionControlToolWindow(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(60, 63, 65)
        panel.preferredSize = Dimension(0, 250)
        panel.border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Version Control")
        (panel.border as javax.swing.border.TitledBorder).titleColor = Color.LIGHT_GRAY

        val gitLogComponent = io.github.jwyoon1220.glyph.vcs.GitLogComponent(gitManager) { commitHash ->
            val confirm = JOptionPane.showConfirmDialog(
                panel,
                "이 커밋으로 롤백하시겠습니까?\n커밋 이후의 모든 변경 사항이 사라집니다.",
                "롤백 확인", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (confirm == JOptionPane.YES_OPTION) {
                uiScope.launch(Dispatchers.IO) {
                    val ok = gitManager.rollbackTo(commitHash)
                    withContext(Dispatchers.Swing) {
                        if (ok) {
                            reloadAllEditors()
                            gitLogComponent.refresh()
                        } else {
                            JOptionPane.showMessageDialog(
                                panel, "롤백에 실패했습니다.", "오류", JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                }
            }
        }

        val btnRefresh = JButton("Refresh Log")
        btnRefresh.addActionListener {
            gitLogComponent.refresh()
        }

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT))
        topBar.isOpaque = false
        topBar.add(btnRefresh)

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(gitLogComponent, BorderLayout.CENTER)

        // Initial refresh
        SwingUtilities.invokeLater { gitLogComponent.refresh() }

        return panel
    }

    private fun createMenuBar(): JMenuBar {
        val menuBar = JMenuBar()
        val fileMenu = JMenu("파일(F)")
        fileMenu.mnemonic = KeyEvent.VK_F

        val openItem = JMenuItem("열기(O)...", KeyEvent.VK_O)
        openItem.addActionListener {
            val chooser = FileChooser(this)
            chooser.isVisible = true
            if (chooser.selectedFile != null) {
                val frame = GlyphMainFrame(chooser.selectedFile!!)
                frame.isVisible = true
                this.dispose()
            }
        }

        val recentMenu = JMenu("최근 프로젝트 열기(R)")
        recentMenu.mnemonic = KeyEvent.VK_R

        val recents = RecentProjectsManager.getRecentProjects()
        if (recents.isEmpty()) {
            val emptyItem = JMenuItem("최근 프로젝트 없음").apply { isEnabled = false }
            recentMenu.add(emptyItem)
        } else {
            for (path in recents) {
                val file = File(path)
                val item = JMenuItem(file.name)
                item.toolTipText = path
                item.addActionListener {
                    if (file.exists()) {
                        val frame = GlyphMainFrame(file)
                        frame.isVisible = true
                        this.dispose()
                    } else {
                        NativeUtils.showError(this, "오류", "프로젝트 폴더를 찾을 수 없습니다: $path")
                    }
                }
                recentMenu.add(item)
            }
        }

        val exitItem = JMenuItem("종료(X)", KeyEvent.VK_X)
        exitItem.addActionListener { System.exit(0) }

        fileMenu.add(openItem)
        fileMenu.add(recentMenu)
        fileMenu.addSeparator()
        fileMenu.add(exitItem)

        menuBar.add(fileMenu)

        // Git menu
        val gitMenu = JMenu("Git")
        gitMenu.mnemonic = KeyEvent.VK_G

        val prefs = java.util.prefs.Preferences.userNodeForPackage(GlyphMainFrame::class.java)

        val remoteItem = JMenuItem("리모트 저장소 설정...")
        remoteItem.addActionListener {
            val current = gitManager.getRemoteUrl()
            val url = JOptionPane.showInputDialog(
                this, "GitHub 리모트 URL을 입력하세요:", "리모트 저장소 설정",
                JOptionPane.PLAIN_MESSAGE, null, null, current
            ) as? String ?: return@addActionListener
            if (url.isNotBlank()) {
                try {
                    gitManager.setRemoteUrl(url.trim())
                    refreshStatusBar()
                    JOptionPane.showMessageDialog(this, "리모트 URL이 설정되었습니다.", "완료", JOptionPane.INFORMATION_MESSAGE)
                } catch (ex: Exception) {
                    JOptionPane.showMessageDialog(this, "오류: ${ex.message}", "오류", JOptionPane.ERROR_MESSAGE)
                }
            }
        }

        val credItem = JMenuItem("GitHub 인증 정보 설정...")
        credItem.addActionListener {
            val panel = JPanel(GridLayout(2, 2, 8, 4))
            val userField = JTextField(prefs.get("git_username", ""), 20)
            val tokenField = JPasswordField(prefs.get("git_token", ""), 20)
            panel.add(JLabel("GitHub 사용자명:"))
            panel.add(userField)
            panel.add(JLabel("Personal Access Token:"))
            panel.add(tokenField)
            val result = JOptionPane.showConfirmDialog(
                this, panel, "GitHub 인증 정보 설정", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            )
            if (result == JOptionPane.OK_OPTION) {
                prefs.put("git_username", userField.text.trim())
                prefs.put("git_token", String(tokenField.password).trim())
            }
        }

        val pushItem = JMenuItem("Push")
        pushItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        pushItem.addActionListener {
            val username = prefs.get("git_username", "")
            val token = prefs.get("git_token", "")
            if (username.isBlank() || token.isBlank()) {
                JOptionPane.showMessageDialog(this, "GitHub 인증 정보를 먼저 설정하세요.", "인증 필요", JOptionPane.WARNING_MESSAGE)
                return@addActionListener
            }
            uiScope.launch(Dispatchers.IO) {
                val err = gitManager.push(username, token)
                withContext(Dispatchers.Swing) {
                    if (err == null) {
                        JOptionPane.showMessageDialog(this@GlyphMainFrame, "Push 완료!", "Git Push", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        JOptionPane.showMessageDialog(this@GlyphMainFrame, "Push 실패: $err", "오류", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
        }

        val pullItem = JMenuItem("Pull")
        pullItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        pullItem.addActionListener {
            val username = prefs.get("git_username", "")
            val token = prefs.get("git_token", "")
            if (username.isBlank() || token.isBlank()) {
                JOptionPane.showMessageDialog(this, "GitHub 인증 정보를 먼저 설정하세요.", "인증 필요", JOptionPane.WARNING_MESSAGE)
                return@addActionListener
            }
            uiScope.launch(Dispatchers.IO) {
                val err = gitManager.pull(username, token)
                withContext(Dispatchers.Swing) {
                    if (err == null) {
                        reloadAllEditors()
                        JOptionPane.showMessageDialog(this@GlyphMainFrame, "Pull 완료!", "Git Pull", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        JOptionPane.showMessageDialog(this@GlyphMainFrame, "Pull 실패: $err", "오류", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
        }

        gitMenu.add(remoteItem)
        gitMenu.add(credItem)
        gitMenu.addSeparator()
        gitMenu.add(pushItem)
        gitMenu.add(pullItem)
        menuBar.add(gitMenu)

        val settingsMenu = JMenu("설정(S)")
        settingsMenu.mnemonic = KeyEvent.VK_S

        val apiKeyItem = JMenuItem("Gemini API 키 설정...")
        apiKeyItem.addActionListener {
            val currentKey = prefs.get("gemini_api_key", "")
            val result = JOptionPane.showInputDialog(
                this,
                "Gemini API 키를 입력하세요:",
                "AI 설정",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                currentKey
            ) as? String
            if (result != null) {
                prefs.put("gemini_api_key", result)
                geminiClient.apiKey = result
            }
        }
        settingsMenu.add(apiKeyItem)
        menuBar.add(settingsMenu)

        return menuBar
    }
}

fun main() {
    // Ultra High-Performance Hardware Acceleration using JNI to Native Window APIs
    System.setProperty("sun.java2d.opengl", "true")
    System.setProperty("sun.java2d.d3d", "true")
    System.setProperty("sun.java2d.noddraw", "false")
    System.setProperty("sun.java2d.accthreshold", "0")

    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    SwingUtilities.invokeLater {
        // Enable Hardware Accelerated Custom Native JNI window decorations
        FlatLaf.registerCustomDefaultsSource("io.github.jwyoon1220.glyph")
        System.setProperty("flatlaf.useWindowDecorations", "true")
        System.setProperty("flatlaf.menuBarEmbedded", "true")

        FlatMaterialDesignDarkIJTheme.setup()

        showProjectLauncher()
    }
}

fun showProjectLauncher() {
    val launcherFrame = JFrame("Glyph - Open Project")
    launcherFrame.setSize(500, 300)
    launcherFrame.setLocationRelativeTo(null)
    launcherFrame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    val panel = JPanel(GridBagLayout())
    panel.background = Color(1, 22, 39)
    panel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

    val titleLbl = JLabel("Welcome to Glyph")
    titleLbl.font = Font("SansSerif", Font.BOLD, 24)
    titleLbl.foreground = Color.WHITE

    val gbc = GridBagConstraints()
    gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
    gbc.insets = Insets(0, 0, 30, 0)
    panel.add(titleLbl, gbc)

    val btnCreate = JButton("Create New Project / Open Project Folder")
    btnCreate.font = Font("SansSerif", Font.PLAIN, 14)
    btnCreate.preferredSize = Dimension(300, 40)
    btnCreate.addActionListener {
        val chooser = FileChooser(launcherFrame)
        chooser.isVisible = true
        if (chooser.selectedFile != null) {
            val projectDir = chooser.selectedFile!!
            launcherFrame.dispose()

            val frame = GlyphMainFrame(projectDir)
            frame.isVisible = true
        }
    }

    gbc.gridy = 1
    gbc.insets = Insets(10, 0, 10, 0)
    panel.add(btnCreate, gbc)

    val recents = RecentProjectsManager.getRecentProjects()
    if (recents.isNotEmpty()) {
        val recentPanel = JPanel(GridLayout(0, 1, 0, 5))
        recentPanel.isOpaque = false
        val recentLbl = JLabel("Recent Projects")
        recentLbl.foreground = Color.GRAY
        recentPanel.add(recentLbl)

        for (path in recents.take(3)) {
            val file = File(path)
            val btn = JButton(file.name).apply {
                toolTipText = path
                font = Font("SansSerif", Font.PLAIN, 13)
                foreground = Color(130, 170, 255) // NightOwl Blue
                isContentAreaFilled = false
                isBorderPainted = false
                cursor = Cursor(Cursor.HAND_CURSOR)
                horizontalAlignment = SwingConstants.LEFT
            }
            btn.addActionListener {
                if (file.exists()) {
                    launcherFrame.dispose()
                    val frame = GlyphMainFrame(file)
                    frame.isVisible = true
                } else {
                    NativeUtils.showError(launcherFrame, "오류", "프로젝트 폴더를 찾을 수 없습니다: $path")
                }
            }
            recentPanel.add(btn)
        }
        gbc.gridy = 2
        gbc.insets = Insets(20, 0, 0, 0)
        panel.add(recentPanel, gbc)
    }

    launcherFrame.contentPane = panel
    launcherFrame.rootPane.putClientProperty("JRootPane.titleBarBackground", Color(1, 22, 39))
    launcherFrame.isVisible = true
}