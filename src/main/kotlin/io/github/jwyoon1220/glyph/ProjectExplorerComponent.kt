package io.github.jwyoon1220.glyph

import java.awt.*
import java.awt.event.*
import java.io.File
import java.util.EventObject
import javax.swing.*
import javax.swing.tree.*

class ProjectExplorerComponent(
    private val rootDir: File,
    private val onFileSelected: (File) -> Unit
) : JPanel(BorderLayout()) {

    private val treeModel: DefaultTreeModel
    private val tree: JTree

    init {
        background = Color(1, 22, 39)

        val topBar = JToolBar()
        topBar.isFloatable = false
        topBar.isOpaque = false
        topBar.border = BorderFactory.createEmptyBorder(2, 2, 2, 2)

        val btnNew = JButton("New...").apply {
            isOpaque = false
            foreground = Color.WHITE
            isContentAreaFilled = false
            isFocusPainted = false
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }
        val btnRefresh = JButton("Refresh").apply {
            isOpaque = false
            foreground = Color.WHITE
            isContentAreaFilled = false
            isFocusPainted = false
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }

        topBar.add(btnNew)
        topBar.add(btnRefresh)
        add(topBar, BorderLayout.NORTH)

        val rootNode = DefaultMutableTreeNode(rootDir)
        treeModel = DefaultTreeModel(rootNode)
        tree = JTree(treeModel)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.background = Color(1, 22, 39)
        tree.foreground = Color(214, 222, 235)
        // Enable inline editing for IntelliJ-style file creation
        tree.isEditable = true
        tree.cellEditor = InlineNameEditor()
        tree.cellRenderer = CustomTreeCellRenderer()

        btnRefresh.addActionListener { refreshTree() }
        btnNew.addActionListener {
            val selectedFile = getSelectedFile() ?: rootDir
            val targetDir = if (selectedFile.isDirectory) selectedFile else selectedFile.parentFile ?: rootDir
            val owner = SwingUtilities.getWindowAncestor(this@ProjectExplorerComponent)
            val anchorOnScreen = btnNew.locationOnScreen
            val anchor = Point(anchorOnScreen.x, anchorOnScreen.y + btnNew.height + 4)
            NewFileOverlay.show(owner, anchor, targetDir) { newFile ->
                refreshTree()
                selectFile(newFile)
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val file = getSelectedFile()
                    if (file != null && file.isFile) onFileSelected(file)
                }
            }
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = tree.getRowForLocation(e.x, e.y)
                if (row >= 0) tree.setSelectionRow(row)
                val selectedFile = getSelectedFile()
                val targetDir: File = when {
                    selectedFile == null -> rootDir
                    selectedFile.isDirectory -> selectedFile
                    else -> selectedFile.parentFile ?: rootDir
                }

                val popup = JPopupMenu()
                val miNew = JMenuItem("New...")
                miNew.addActionListener {
                    popup.isVisible = false
                    val owner = SwingUtilities.getWindowAncestor(tree)
                    val anchorOnScreen = tree.locationOnScreen
                    val anchor = Point(anchorOnScreen.x + e.x, anchorOnScreen.y + e.y)
                    NewFileOverlay.show(owner, anchor, targetDir) { newFile ->
                        refreshTree()
                        selectFile(newFile)
                    }
                }
                popup.add(miNew)

                if (selectedFile != null && selectedFile != rootDir) {
                    popup.addSeparator()

                    // Inline rename: set the tree cell into edit mode
                    val miRename = JMenuItem("Rename")
                    miRename.addActionListener {
                        val path = tree.selectionPath ?: return@addActionListener
                        tree.startEditingAtPath(path)
                    }
                    popup.add(miRename)

                    val miDelete = JMenuItem("Delete")
                    miDelete.foreground = Color(247, 140, 108)
                    miDelete.addActionListener {
                        val confirm = JOptionPane.showConfirmDialog(
                            this@ProjectExplorerComponent,
                            "\"${selectedFile.name}\"을(를) 삭제할까요?",
                            "삭제 확인", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
                        )
                        if (confirm == JOptionPane.YES_OPTION) {
                            selectedFile.deleteRecursively()
                            refreshTree()
                        }
                    }
                    popup.add(miDelete)
                }

                popup.show(tree, e.x, e.y)
            }
        })

        val scrollPane = JScrollPane(tree)
        scrollPane.border = null
        scrollPane.viewport.background = Color(1, 22, 39)
        add(scrollPane, BorderLayout.CENTER)

        refreshTree()
    }

    /** Creates a menu item that, when clicked, inserts an inline-editable node in the tree. */
    private fun newMenuItem(
        label: String,
        parentDir: File,
        defaultName: String,
        isDir: Boolean = false,
        transform: (File) -> File
    ): JMenuItem {
        val item = JMenuItem(label)
        item.addActionListener {
            // Create a placeholder file (0 bytes) so the node has a file reference
            val placeholder = File(parentDir, defaultName)
            if (!placeholder.exists()) {
                if (isDir) placeholder.mkdir() else placeholder.createNewFile()
            }
            refreshTree()

            // Find the new node and start editing it immediately
            SwingUtilities.invokeLater {
                val rootNode = treeModel.root as DefaultMutableTreeNode
                val newNode = findNodeForFile(rootNode, placeholder)
                if (newNode != null) {
                    val path = TreePath(newNode.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                    tree.startEditingAtPath(path)
                }
            }
        }
        return item
    }

    private fun showNewMenu(anchor: JComponent, targetDir: File) {
        val popup = JPopupMenu()
        val mnuNew = JMenu("New")
        mnuNew.add(newMenuItem("Episode (.gle)", targetDir, "Untitled.gle") { it })
        mnuNew.add(newMenuItem("Wiki Page (.glw)", targetDir, "Untitled.glw") { it })
        mnuNew.add(newMenuItem("Folder", targetDir, "NewFolder", isDir = true) { it })
        popup.add(mnuNew as JMenuItem)
        popup.show(anchor, 0, anchor.height)
    }

    private fun getSelectedFile(): File? {
        val path = tree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? File
    }

    private fun findNodeForFile(node: DefaultMutableTreeNode, target: File): DefaultMutableTreeNode? {
        if (node.userObject == target) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val found = findNodeForFile(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun selectFile(target: File) {
        val rootNode = treeModel.root as DefaultMutableTreeNode
        val path = findPathForFile(rootNode, target)
        if (path != null) {
            val treePath = TreePath(path)
            tree.selectionPath = treePath
            tree.scrollPathToVisible(treePath)
        }
    }

    private fun findPathForFile(node: DefaultMutableTreeNode, target: File): Array<TreeNode>? {
        if (node.userObject == target) return node.path
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val found = findPathForFile(child, target)
            if (found != null) return found
        }
        return null
    }

    fun refreshTree() {
        val rootNode = treeModel.root as DefaultMutableTreeNode
        rootNode.removeAllChildren()
        populateNode(rootDir, rootNode)
        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun populateNode(dir: File, node: DefaultMutableTreeNode) {
        val files = dir.listFiles() ?: return
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { file ->
            if (file.name.startsWith(".")) return@forEach
            val childNode = DefaultMutableTreeNode(file)
            node.add(childNode)
            if (file.isDirectory) populateNode(file, childNode)
        }
    }

    // ─── Inline editor ──────────────────────────────────────────────────────────

    /**
     * Custom cell editor that shows a bare JTextField inline in the tree.
     * On commit (Enter / focus lost) it renames the underlying file.
     */
    inner class InlineNameEditor : DefaultCellEditor(JTextField()) {
        private val textField = component as JTextField
        private var currentFile: File? = null

        init {
            textField.background = Color(30, 50, 70)
            textField.foreground = Color.WHITE
            textField.caretColor = Color.WHITE
            textField.border = BorderFactory.createLineBorder(Color(130, 170, 255), 1)
            textField.font = Font("SansSerif", Font.PLAIN, 13)
        }

        override fun getTreeCellEditorComponent(
            tree: JTree, value: Any?, isSelected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int
        ): Component {
            val node = value as? DefaultMutableTreeNode
            currentFile = node?.userObject as? File
            textField.text = currentFile?.name ?: ""
            return textField
        }

        override fun getCellEditorValue(): Any {
            val newName = textField.text.trim()
            val file = currentFile ?: return newName
            if (newName.isNotEmpty() && newName != file.name) {
                val renamed = File(file.parentFile, newName)
                file.renameTo(renamed)
                refreshTree()
            }
            return newName
        }

        override fun isCellEditable(event: EventObject?): Boolean {
            // Only allow editing programmatically via startEditingAtPath, not by double-click
            return event == null
        }
    }
}

class CustomTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        val file = node?.userObject as? File
        if (file != null) {
            text = file.name
            icon = IconProvider.getIconForFile(file)
        }
        backgroundNonSelectionColor = Color(1, 22, 39)
        backgroundSelectionColor = Color(45, 44, 93)
        textNonSelectionColor = Color(214, 222, 235)
        textSelectionColor = Color.WHITE
        return this
    }
}
