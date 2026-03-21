package io.github.jwyoon1220.glyph.vcs

import java.awt.*
import java.awt.geom.Path2D
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.max

data class GraphRow(
    val commit: CommitInfo,
    val laneIndex: Int,
    val colorIndex: Int,
    val incomingLanes: List<String>,
    val outgoingLanes: List<String>
)

class GitLogComponent(private val gitManager: GitManager) : JPanel(BorderLayout()) {

    private val colors = arrayOf(
        Color(88, 157, 246),  // Light Blue
        Color(204, 120, 50),  // Orange
        Color(152, 118, 170), // Purple
        Color(106, 135, 89),  // Green
        Color(255, 198, 109), // Yellow
        Color(232, 114, 114)  // Red
    )

    private val tableModel = LogTableModel()
    private val table = JTable(tableModel)

    init {
        table.background = Color(43, 43, 43)
        table.foreground = Color(169, 183, 198)
        table.font = Font("SansSerif", Font.PLAIN, 12)
        table.rowHeight = 24
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.selectionBackground = Color(47, 101, 202)
        table.selectionForeground = Color.WHITE

        table.columnModel.getColumn(0).cellRenderer = GraphCellRenderer()
        table.columnModel.getColumn(0).maxWidth = 150
        table.columnModel.getColumn(0).preferredWidth = 100

        table.columnModel.getColumn(1).cellRenderer = MessageCellRenderer()
        
        table.columnModel.getColumn(2).maxWidth = 150
        table.columnModel.getColumn(2).preferredWidth = 120

        val scrollPane = JScrollPane(table)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.viewport.background = Color(43, 43, 43)

        add(scrollPane, BorderLayout.CENTER)
    }

    fun refresh() {
        val commits = gitManager.getCommitLog()
        
        val rows = mutableListOf<GraphRow>()
        val lanes = mutableListOf<String>()

        for (commit in commits) {
            val incomingLanes = lanes.toList()
            
            var laneIndex = lanes.indexOf(commit.hash)
            if (laneIndex == -1) {
                // find first empty lane, or add new
                val emptyIdx = lanes.indexOf("")
                if (emptyIdx != -1) {
                    laneIndex = emptyIdx
                    lanes[laneIndex] = commit.hash
                } else {
                    laneIndex = lanes.size
                    lanes.add(commit.hash)
                }
            }

            val colorIndex = laneIndex % colors.size

            if (commit.parents.isNotEmpty()) {
                lanes[laneIndex] = commit.parents[0]
                for (i in 1 until commit.parents.size) {
                    val p = commit.parents[i]
                    if (!lanes.contains(p)) {
                        val emptyIdx = lanes.indexOf("")
                        if (emptyIdx != -1) lanes[emptyIdx] = p
                        else lanes.add(p)
                    }
                }
            } else {
                lanes[laneIndex] = ""
            }

            while (lanes.isNotEmpty() && lanes.last() == "") {
                lanes.removeAt(lanes.size - 1)
            }

            rows.add(GraphRow(commit, laneIndex, colorIndex, incomingLanes, lanes.toList()))
        }

        tableModel.setData(rows)
    }

    inner class LogTableModel : AbstractTableModel() {
        private var rows = listOf<GraphRow>()

        fun setData(newRows: List<GraphRow>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row
                1 -> row.commit
                2 -> row.commit.author
                else -> ""
            }
        }
        override fun getColumnName(column: Int) = when(column) {
            0 -> "Graph"
            1 -> "Message"
            2 -> "Author"
            else -> ""
        }
    }

    inner class GraphCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
            background = if (isSelected) table.selectionBackground else table.background
            return object : JPanel() {
                init {
                    isOpaque = true
                    background = this@GraphCellRenderer.background
                }
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    
                    if (value !is GraphRow) return
                    val graphRow = value
                    
                    val w = 15
                    val h = height
                    val centerX = { lane: Int -> 10 + lane * w + w / 2 }
                    val midY = h / 2

                    // Draw incoming lines (from above)
                    for ((i, hash) in graphRow.incomingLanes.withIndex()) {
                        if (hash.isNotEmpty() && hash != graphRow.commit.hash) {
                            val outIdx = graphRow.outgoingLanes.indexOf(hash)
                            if (outIdx != -1) {
                                g2.color = colors[outIdx % colors.size]
                                val path = Path2D.Float()
                                path.moveTo(centerX(i).toFloat(), 0f)
                                path.curveTo(
                                    centerX(i).toFloat(), (h/2).toFloat(),
                                    centerX(outIdx).toFloat(), (h/2).toFloat(),
                                    centerX(outIdx).toFloat(), h.toFloat()
                                )
                                g2.stroke = BasicStroke(2f)
                                g2.draw(path)
                            }
                        }
                    }

                    // Draw connections from this node to its outgoing lanes (parents)
                    for (parentHash in graphRow.commit.parents) {
                        val outIdx = graphRow.outgoingLanes.indexOf(parentHash)
                        if (outIdx != -1) {
                            g2.color = colors[outIdx % colors.size]
                            val path = Path2D.Float()
                            path.moveTo(centerX(graphRow.laneIndex).toFloat(), midY.toFloat())
                            path.curveTo(
                                centerX(graphRow.laneIndex).toFloat(), (midY + h/4).toFloat(),
                                centerX(outIdx).toFloat(), (midY + h/4).toFloat(),
                                centerX(outIdx).toFloat(), h.toFloat()
                            )
                            g2.stroke = BasicStroke(2f)
                            g2.draw(path)
                        }
                    }

                    // Draw a line from the node to the top if this lane was active before
                    val inIdx = graphRow.incomingLanes.indexOf(graphRow.commit.hash)
                    if (inIdx != -1) {
                        g2.color = colors[graphRow.colorIndex]
                        g2.stroke = BasicStroke(2f)
                        g2.drawLine(centerX(inIdx), 0, centerX(graphRow.laneIndex), midY)
                    }

                    // Draw the node
                    g2.color = colors[graphRow.colorIndex]
                    val nx = centerX(graphRow.laneIndex)
                    g2.fillOval(nx - 4, midY - 4, 8, 8)
                    g2.color = table.background
                    g2.drawOval(nx - 4, midY - 4, 8, 8)
                }
            }
        }
    }

    inner class MessageCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val commit = value as? CommitInfo
            val msg = commit?.message ?: ""
            super.getTableCellRendererComponent(table, msg, isSelected, hasFocus, row, column)
            
            return object : JPanel(BorderLayout()) {
                init {
                    isOpaque = true
                    background = if (isSelected) table.selectionBackground else table.background
                    
                    val box = Box.createHorizontalBox()
                    if (commit != null && commit.refs.isNotEmpty()) {
                        for (ref in commit.refs) {
                            val isHead = ref == "HEAD" || ref == "master"
                            val lbl = JLabel(" $ref ").apply {
                                font = Font("SansSerif", Font.BOLD, 10)
                                foreground = Color.WHITE
                                isOpaque = false
                            }
                            val tagPanel = object : JPanel(BorderLayout()) {
                                init {
                                    isOpaque = false
                                    add(lbl, BorderLayout.CENTER)
                                }
                                override fun paintComponent(g: Graphics) {
                                    val g2 = g as Graphics2D
                                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                    g2.color = if (isHead) Color(60, 120, 180) else Color(100, 140, 100)
                                    g2.fillRoundRect(2, 4, width - 4, height - 8, 8, 8)
                                    super.paintComponent(g)
                                }
                            }
                            box.add(tagPanel)
                            box.add(Box.createHorizontalStrut(4))
                        }
                    }
                    
                    val msgLbl = JLabel(msg).apply {
                        font = table.font
                        foreground = if (isSelected) table.selectionForeground else table.foreground
                    }
                    box.add(msgLbl)
                    
                    add(box, BorderLayout.WEST)
                    
                    // Add short hash on the right
                    val hashLbl = JLabel(commit?.shortHash ?: "").apply {
                        font = Font("Monospaced", Font.PLAIN, 12)
                        foreground = Color.GRAY
                        border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
                    }
                    add(hashLbl, BorderLayout.EAST)
                }
            }
        }
    }
}
