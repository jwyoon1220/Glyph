package io.github.jwyoon1220.glyph

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
import kotlin.system.exitProcess

class GlyphMainFrame(val dataRoot: File) : JFrame("Glyph - ${dataRoot.name}") {

    // Services
    private val repo = StorageRepository(dataRoot)
    private val gitManager = GitManager(dataRoot)
    private val dictClient = DictionaryClient()
    private val luceneSearcher = LuceneSearcher()
    private val fileWatcher = FileWatcher(dataRoot, luceneSearcher, repo)

    private val prefs = java.util.prefs.Preferences.userNodeForPackage(GlyphMainFrame::class.java)

    private val geminiClient = GeminiClient(prefs.get("gemini_api_key", ""))
    private val groqClient = GroqClient(
        apiKey = prefs.get("groq_api_key", ""),
        model = prefs.get("groq_model", "qwen/qwen3-32b")
    )

    /** Returns the currently selected AI client based on user preference. */
    private val activeAiClient: AiClient?
        get() = when (prefs.get("ai_provider", "gemini")) {
            "groq"  -> groqClient.takeIf { it.apiKey.isNotBlank() }
            "gemini" -> geminiClient.takeIf { it.apiKey.isNotBlank() }
            else    -> null
        }

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
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        // Save in-memory recovery snapshot before the JVM exits
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                saveRecoveryData()
                luceneSearcher.close()
                dispose()
                // Use displayable check so minimized sibling windows don't block exit
                if (JFrame.getFrames().none { it.isDisplayable && it != this@GlyphMainFrame }) {
                    exitProcess(0)
                }
            }
        })

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
        
        // Scan for Wiki files
        uiScope.launch { io.github.jwyoon1220.glyph.wiki.WikiIndexer.scanProject(dataRoot) }

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
        } else if (relPath.endsWith(".md") || relPath.endsWith(".glh") || relPath.endsWith(".glhr")) {
            val mdEditor = MarkdownEditorComponent()
            mdEditor.onTypingStopped = {
                prefs.remove("recovery_${relPath.hashCode()}")
                uiScope.launch(Dispatchers.IO) {
                    repo.saveFile(relPath, mdEditor.text)
                    gitManager.commitAll("Auto-save ($relPath)")
                }
            }
            openEditors[relPath] = mdEditor
            tabbedPane.addTab(File(relPath).name, mdEditor)
            tabbedPane.selectedComponent = mdEditor

            uiScope.launch(Dispatchers.IO) {
                val fileExists = File(dataRoot, relPath).exists()
                if (!fileExists) {
                    repo.saveFile(relPath, "")
                    gitManager.commitAll("Initial commit ($relPath)")
                }
                val text = repo.loadFile(relPath)
                withContext(Dispatchers.Swing) { mdEditor.text = text }
            }
        } else {
            val editor = GlyphTextArea(dictClient)
            editor.aiClient = activeAiClient
            editor.addTypingStoppedListener {
                // Clear any in-memory crash recovery data once saved to disk
                prefs.remove("recovery_${relPath.hashCode()}")
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
                // Try to recover unsaved in-memory content from last session
                val recoveryKey = "recovery_${relPath.hashCode()}"
                val recoveryText = prefs.get(recoveryKey, null)
                val diskText = repo.loadFile(relPath)
                withContext(Dispatchers.Swing) {
                    if (recoveryText != null && recoveryText != diskText) {
                        val choice = JOptionPane.showConfirmDialog(
                            this@GlyphMainFrame,
                            "이전 세션에서 저장되지 않은 변경 사항이 있습니다.\n복구하시겠습니까?",
                            "변경 사항 복구",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                        )
                        editor.text = if (choice == JOptionPane.YES_OPTION) recoveryText else diskText
                        if (choice != JOptionPane.YES_OPTION) prefs.remove(recoveryKey)
                    } else {
                        editor.text = diskText
                        prefs.remove(recoveryKey)
                    }
                }
            }
        }
    }

    /** Save all open editor contents to Preferences for crash recovery. */
    fun saveRecoveryData() {
        for ((relPath, comp) in openEditors) {
            val text = getEditorText(comp) ?: continue
            if (text.isNotEmpty()) {
                prefs.put("recovery_${relPath.hashCode()}", text)
            }
        }
    }

    /** Reload every open text editor from disk (used after git rollback). */
    private fun reloadAllEditors() {
        for ((relPath, comp) in openEditors) {
            uiScope.launch(Dispatchers.IO) {
                val text = repo.loadFile(relPath)
                withContext(Dispatchers.Swing) {
                    setEditorText(comp, text)
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

    /** Returns the text content of any editor component we know about. */
    private fun getEditorText(comp: Component): String? =
        when {
            comp is MarkdownEditorComponent -> comp.text
            else -> findEditorInComponent(comp)?.text
        }

    /** Sets the text content of any editor component we know about. */
    private fun setEditorText(comp: Component, text: String) {
        when {
            comp is MarkdownEditorComponent -> comp.text = text
            else -> findEditorInComponent(comp)?.text = text
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
                        } else {
                            JOptionPane.showMessageDialog(
                                panel, "롤백에 실패했습니다.", "오류", JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                }
            }
        }
        gitLogComponent.refresh()

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

        val aiSettingsItem = JMenuItem("AI 설정...")
        aiSettingsItem.addActionListener {
            showAiSettingsDialog()
        }
        settingsMenu.add(aiSettingsItem)
        menuBar.add(settingsMenu)

        return menuBar
    }

    private fun showAiSettingsDialog() {
        val providers = arrayOf("없음 (비활성화)", "Gemini", "Groq")
        val currentProvider = when (prefs.get("ai_provider", "gemini")) {
            "gemini" -> 1
            "groq"   -> 2
            else     -> 0
        }

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 4, 4, 4)
        }

        val providerBox = JComboBox(providers).apply { selectedIndex = currentProvider }
        val geminiKeyField = JPasswordField(prefs.get("gemini_api_key", ""), 30)
        val groqKeyField = JPasswordField(prefs.get("groq_api_key", ""), 30)
        val groqModelField = JTextField(prefs.get("groq_model", "qwen/qwen3-32b"), 30)

        gbc.gridx = 0; gbc.gridy = 0; panel.add(JLabel("AI 제공자:"), gbc)
        gbc.gridx = 1; panel.add(providerBox, gbc)

        gbc.gridx = 0; gbc.gridy = 1; panel.add(JLabel("Gemini API 키:"), gbc)
        gbc.gridx = 1; panel.add(geminiKeyField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; panel.add(JLabel("Groq API 키:"), gbc)
        gbc.gridx = 1; panel.add(groqKeyField, gbc)

        gbc.gridx = 0; gbc.gridy = 3; panel.add(JLabel("Groq 모델:"), gbc)
        gbc.gridx = 1; panel.add(groqModelField, gbc)

        val result = JOptionPane.showConfirmDialog(
            this, panel, "AI 설정", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val provider = when (providerBox.selectedIndex) {
                1 -> "gemini"
                2 -> "groq"
                else -> "none"
            }
            prefs.put("ai_provider", provider)
            prefs.put("gemini_api_key", String(geminiKeyField.password).trim())
            prefs.put("groq_api_key", String(groqKeyField.password).trim())
            prefs.put("groq_model", groqModelField.text.trim())

            geminiClient.apiKey = prefs.get("gemini_api_key", "")
            groqClient.apiKey = prefs.get("groq_api_key", "")
            groqClient.model = prefs.get("groq_model", "qwen/qwen3-32b")

            // Apply the new client to all open text editors
            for (comp in openEditors.values) {
                findEditorInComponent(comp)?.aiClient = activeAiClient
            }
        }
    }
}
