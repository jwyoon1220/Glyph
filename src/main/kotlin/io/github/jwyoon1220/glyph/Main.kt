package io.github.jwyoon1220.glyph

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme
import io.github.jwyoon1220.glyph.data.StorageRepository
import io.github.jwyoon1220.glyph.search.DictionaryClient
import io.github.jwyoon1220.glyph.search.FileWatcher
import io.github.jwyoon1220.glyph.search.LuceneSearcher
import io.github.jwyoon1220.glyph.vcs.GitManager
import io.github.jwyoon1220.glyph.hangul.koreanTokens
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
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
    private val fileWatcher = FileWatcher(dataRoot)
    private val wikiIndexer = WikiIndexer()

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
    private val openEditors = Object2ObjectOpenHashMap<String, Component>()

    /** Find bar shown at the bottom of the editor area when Ctrl+F is pressed. */
    private val findBar = FindBar()
    /** Wraps tabbedPane + findBar so the bar slides in without disturbing the split-pane layout. */
    private val editorAreaPanel = JPanel(BorderLayout()).also { p ->
        p.isOpaque = false
        p.add(tabbedPane, BorderLayout.CENTER)
        p.add(findBar, BorderLayout.SOUTH)
    }
    /** Accumulated search matches for the active editor. */
    private val currentSearchMatches = ObjectArrayList<IntRange>()
    private var currentSearchMatchIdx = 0

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

    /** Right-panel pane that displays morphological analysis for selected text. */
    private val morphAnalysisPane = JTextPane().apply {
        isOpaque = true
        isEditable = false
        contentType = "text/html"
        background = Color(43, 45, 48)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    // Coroutine Scope for UI
    private val uiScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

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
                    System.exit(0)
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
            center = object : ToolWindowPanel { override val component = editorAreaPanel },
            right  = object : ToolWindowPanel { override val component = createCharacterToolWindow() },
            bottom = object : ToolWindowPanel { override val component = createVersionControlToolWindow() }
        )
        rootPanel.add(toolWindowManager.buildLayout(), BorderLayout.CENTER)
        rootPanel.add(createStatusBar(), BorderLayout.SOUTH)

        WindowResizer(rootPanel)
        contentPane = rootPanel

        // Start the file watcher for real-time Lucene indexing
        fileWatcher.start()

        // Reactively collect and index file changes
        uiScope.launch {
            fileWatcher.eventFlow.collect { file ->
                indexFile(file)
            }
        }

        // Background scan: index all existing project files on startup
        uiScope.launch {
            dataRoot.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { file ->
                    indexFile(file)
                }
        }

        // Wire up Ctrl+F find bar
        setupFindBar()

        // Open untitled by default
        openFile("Untitled.gle")

        // Initial status bar refresh
        refreshStatusBar()
    }

    private suspend fun indexFile(file: File) = withContext(Dispatchers.IO) {
        try {
            val content = when {
                file.name.endsWith(".gle") || file.name.endsWith(".md") ||
                file.name.endsWith(".glhr") || file.name.endsWith(".glp") ->
                    file.readText()
                file.name.endsWith(".glh") -> {
                    val relPath = file.relativeTo(dataRoot).path.replace('\\', '/')
                    repo.loadFile(relPath)
                }
                file.name.endsWith(".glw") -> {
                    val relPath = file.relativeTo(dataRoot).path.replace('\\', '/')
                    val graph = repo.loadWiki(relPath)
                    if (graph != null) {
                        wikiIndexer.indexGraph(graph)
                    }
                    graph?.nodes?.joinToString(" ") { it.title + " " + it.content } ?: ""
                }
                else -> return@withContext
            }
            if (content.isNotEmpty()) {
                luceneSearcher.indexDocument(file.nameWithoutExtension, content)
            }
        } catch (e: Exception) {
            System.err.println("[GlyphMainFrame] Failed to index '${file.path}': ${e.message}")
        }
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
                // Index wiki terms for autocomplete
                wikiIndexer.indexGraph(graph)

                withContext(Dispatchers.Swing) {
                    val editor = WikiGraphEditorComponent(graph) { changedGraph ->
                        uiScope.launch(Dispatchers.IO) {
                            repo.saveWiki(relPath, changedGraph)
                            wikiIndexer.indexGraph(changedGraph)
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
            editor.wikiIndexer = wikiIndexer
            editor.onSelectionChanged = { selectedText ->
                updateMorphAnalysisPanel(selectedText)
            }
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

    /**
     * Updates the morphological analysis panel in the right tool window with
     * a colour-coded breakdown of the selected text.
     */
    private fun updateMorphAnalysisPanel(selectedText: String) {
        if (selectedText.isBlank()) {
            morphAnalysisPane.text = ""
            return
        }
        uiScope.launch {
            val sentence = selectedText.take(300)
            val html = buildString {
                append("<html><body style='font-family:sans-serif;font-size:13px;color:#A9B7C6;margin:4px;'>")
                append("<div style='font-size:11px;color:#6A9955;margin-bottom:6px;'>형태소 분석 (Ctrl+T: 유의어)</div>")

                try {
                    for (token in sentence.koreanTokens) {
                        val color = when {
                            token.pos.startsWith("N") -> "#9876AA"
                            token.pos.startsWith("J") -> "#CC7832"
                            token.pos.startsWith("V") -> "#FFC66D"
                            token.pos.startsWith("M") -> "#6A8759"
                            else -> "#A9B7C6"
                        }
                        append("<span style='color:$color;' title='${token.pos}'>${token.morph}</span>")
                        append("<span style='color:#4C5052;font-size:10px;'>/</span>")
                        append("<span style='color:$color;font-size:10px;'>${token.pos}</span> ")
                    }
                } catch (e: Exception) {
                    System.err.println("[GlyphMainFrame] Morphological analysis failed: ${e.message}")
                    append(sentence)
                }

                // Show wiki term matches for the selected text (ConcurrentHashMap — safe on any thread)
                val wikiMatches = wikiIndexer.getSuggestions(selectedText.trim(), limit = 3, minPrefixLength = 1)
                if (wikiMatches.isNotEmpty()) {
                    append("<hr style='border:0;border-top:1px solid #3C4050;margin:6px 0;'/>")
                    append("<div style='font-size:11px;color:#6A9955;margin-bottom:4px;'>Wiki 관련 항목</div>")
                    for (term in wikiMatches) {
                        val excerpt = wikiIndexer.getExcerpt(term)?.take(80) ?: ""
                        append("<div style='margin-bottom:4px;'>")
                        append("<span style='color:#82AAFF;font-weight:bold;'>$term</span>")
                        if (excerpt.isNotEmpty()) {
                            append("<div style='color:#777;font-size:11px;margin-left:8px;'>$excerpt…</div>")
                        }
                        append("</div>")
                    }
                }

                append("</body></html>")
            }
            morphAnalysisPane.text = html
            morphAnalysisPane.caretPosition = 0
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
        val inputField = io.github.jwyoon1220.glyph.ui.GlyphTextField(20).apply {
            hint = "단어 검색…"
            preferredSize = Dimension(0, 28)
        }
        val searchBtn = io.github.jwyoon1220.glyph.ui.GlyphButton("Search").apply {
            preferredSize = Dimension(70, 28)
        }
        topBar.add(inputField, BorderLayout.CENTER)
        topBar.add(searchBtn, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        // Dictionary search results pane
        val resultPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            background = Color(43, 43, 43)
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        }
        val searchScrollPane = JScrollPane(resultPane).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(0, 180)
        }

        // Morphological analysis pane (shows analysis of selected text)
        val morphLabel = JLabel("  형태소 분석").apply {
            font = Font("SansSerif", Font.PLAIN, 11)
            foreground = Color(100, 150, 100)
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(50, 55, 65))
        }
        val morphScrollPane = JScrollPane(morphAnalysisPane).apply {
            border = BorderFactory.createEmptyBorder()
        }

        val centerPanel = JPanel(BorderLayout())
        centerPanel.isOpaque = false
        centerPanel.add(searchScrollPane, BorderLayout.NORTH)
        centerPanel.add(morphLabel, BorderLayout.CENTER)
        centerPanel.add(morphScrollPane, BorderLayout.SOUTH)
        // Give the morphological pane most of the space via a JSplitPane
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, searchScrollPane, JPanel(BorderLayout()).apply {
            isOpaque = false
            add(morphLabel, BorderLayout.NORTH)
            add(morphScrollPane, BorderLayout.CENTER)
        }).apply {
            resizeWeight = 0.35
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
        }
        panel.add(splitPane, BorderLayout.CENTER)

        val htmlPrefix = "<html><body style='font-family:sans-serif;font-size:13px;color:#A9B7C6;margin:4px;'>"
        val htmlSuffix = "</body></html>"

        val executeSearch = {
            val query = inputField.text
            if (query.isNotBlank()) {
                resultPane.text = "${htmlPrefix}Searching '${query}'…${htmlSuffix}"
                uiScope.launch {
                    val results = dictClient.searchWord(query)
                    val html = if (results.isEmpty()) {
                        "${htmlPrefix}결과 없음: '${query}'${htmlSuffix}"
                    } else {
                        buildString {
                            append(htmlPrefix)
                            for (item in results) {
                                append("<div style='margin-bottom:4px;'>")
                                append("<span style='color:#6AAB73;font-weight:bold;'>【${item.word}】</span> ")
                                if (item.pos.isNotEmpty()) append("<span style='color:#E8BF6A;'>[${item.pos}]</span>")
                                append("</div>")
                                append("<div style='margin-bottom:10px;margin-left:10px;'>${item.sense.definition}</div>")
                            }
                            append(htmlSuffix)
                        }
                    }
                    resultPane.text = html
                    resultPane.caretPosition = 0
                }
            }
        }
        searchBtn.addClickListener { executeSearch() }
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

        lateinit var gitLogComponent: io.github.jwyoon1220.glyph.vcs.GitLogComponent
        gitLogComponent = io.github.jwyoon1220.glyph.vcs.GitLogComponent(gitManager) { commitHash ->
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

        val btnRefresh = io.github.jwyoon1220.glyph.ui.GlyphButton("Refresh Log").apply {
            preferredSize = Dimension(110, 26)
        }
        btnRefresh.addClickListener { gitLogComponent.refresh() }

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT))
        topBar.isOpaque = false
        topBar.add(btnRefresh)

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(gitLogComponent, BorderLayout.CENTER)

        // Initial refresh
        SwingUtilities.invokeLater { gitLogComponent.refresh() }

        return panel
    }

    // --- FIND BAR ---

    private fun setupFindBar() {
        findBar.onSearch = { query, mode, matchCase -> performSearch(query, mode, matchCase) }
        findBar.onNext   = { navigateMatch(+1) }
        findBar.onPrev   = { navigateMatch(-1) }
        findBar.onClose  = { hideFindBar() }

        // Ctrl+F opens/re-focuses the find bar
        val im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rootPane.actionMap
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "glyph.showFindBar")
        am.put("glyph.showFindBar", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { showFindBar() }
        })

        // Re-run search when the user switches tabs
        tabbedPane.addChangeListener {
            if (findBar.isVisible) performSearch(findBar.query, findBar.mode, findBar.isCaseSensitive)
        }
    }

    private fun showFindBar() {
        findBar.isVisible = true
        editorAreaPanel.revalidate()
        editorAreaPanel.repaint()
        findBar.focusField()
        // Run search immediately with the current query (may be from a previous session)
        if (findBar.query.isNotEmpty()) performSearch(findBar.query, findBar.mode, findBar.isCaseSensitive)
    }

    private fun hideFindBar() {
        findBar.isVisible = false
        activeTextEditor()?.clearSearchHighlights()
        currentSearchMatches.clear()
        editorAreaPanel.revalidate()
        editorAreaPanel.repaint()
    }

    /** Returns the GlyphTextArea in the currently selected tab, or null. */
    private fun activeTextEditor(): GlyphTextArea? =
        findEditorInComponent(tabbedPane.selectedComponent ?: return null)

    private fun performSearch(query: String, mode: SearchMode, matchCase: Boolean) {
        val editor = activeTextEditor() ?: return
        currentSearchMatches.clear()

        if (query.isEmpty()) {
            editor.clearSearchHighlights()
            findBar.updateMatchCount(0, 0)
            return
        }

        val flags = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = try {
            when (mode) {
                SearchMode.TEXT  -> Regex(Regex.escape(query), flags)
                SearchMode.WORD  -> Regex("\\b${Regex.escape(query)}\\b", flags)
                SearchMode.REGEX -> Regex(query, flags)
            }
        } catch (_: Exception) {
            editor.clearSearchHighlights()
            findBar.updateMatchCount(0, 0)
            return
        }

        for (match in pattern.findAll(editor.text)) {
            currentSearchMatches.add(match.range)
        }

        currentSearchMatchIdx = if (currentSearchMatches.isNotEmpty()) 0 else -1
        editor.setSearchHighlights(currentSearchMatches, currentSearchMatchIdx)
        findBar.updateMatchCount(currentSearchMatchIdx, currentSearchMatches.size)
    }

    private fun navigateMatch(dir: Int) {
        if (currentSearchMatches.isEmpty()) return
        currentSearchMatchIdx = (currentSearchMatchIdx + dir + currentSearchMatches.size) % currentSearchMatches.size
        activeTextEditor()?.setSearchHighlights(currentSearchMatches, currentSearchMatchIdx)
        findBar.updateMatchCount(currentSearchMatchIdx, currentSearchMatches.size)
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

fun main() {
    System.setProperty("sun.java2d.opengl", "true")
    System.setProperty("sun.java2d.d3d", "true")
    System.setProperty("sun.java2d.noddraw", "false")
    System.setProperty("sun.java2d.accthreshold", "0")

    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    SwingUtilities.invokeLater {
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