package io.github.jwyoon1220.glyph

import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import java.awt.BorderLayout

/**
 * A tool window panel that can be plugged into the main layout.
 */
interface ToolWindowPanel {
    val component: JComponent
}

/**
 * Manages the dynamic layout of tool windows within [GlyphMainFrame].
 *
 * Components are injected via the [ToolWindowPanel] interface so the manager
 * has no direct dependency on any concrete panel implementation.
 */
class ToolWindowManager(
    private val left: ToolWindowPanel,
    private val center: ToolWindowPanel,
    private val right: ToolWindowPanel,
    private val bottom: ToolWindowPanel
) {
    /**
     * Builds and returns the assembled layout panel ready to be added to a frame.
     */
    fun buildLayout(): JPanel {
        // Center + Bottom
        val centerBottomSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            center.component,
            bottom.component
        ).apply {
            resizeWeight = 0.8
            dividerSize = 4
            border = null
        }

        // Left + Center/Bottom
        val leftCenterSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            left.component,
            centerBottomSplit
        ).apply {
            resizeWeight = 0.2
            dividerSize = 4
            border = null
        }

        // Left/Center + Right
        val mainSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            leftCenterSplit,
            right.component
        ).apply {
            resizeWeight = 0.8
            dividerSize = 4
            border = null
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(mainSplit, BorderLayout.CENTER)
        }
    }
}
