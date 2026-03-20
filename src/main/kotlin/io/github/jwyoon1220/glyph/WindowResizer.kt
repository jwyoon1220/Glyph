package io.github.jwyoon1220.glyph

import java.awt.*
import java.awt.event.*
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingUtilities

class WindowResizer(private val component: JComponent, private val border: Int = 5) : MouseAdapter() {
    private var cursorType = Cursor.DEFAULT_CURSOR
    private var resizing = false
    private var initialRect: Rectangle? = null
    private var initialPoint: Point? = null
    private var resizeDir = -1

    init {
        component.addMouseListener(this)
        component.addMouseMotionListener(this)
    }

    override fun mouseMoved(e: MouseEvent) {
        if (resizing) return
        val w = component.width
        val h = component.height
        val inTop = e.y <= border
        val inBottom = e.y >= h - border
        val inLeft = e.x <= border
        val inRight = e.x >= w - border

        cursorType = when {
            inTop && inLeft -> Cursor.NW_RESIZE_CURSOR
            inTop && inRight -> Cursor.NE_RESIZE_CURSOR
            inBottom && inLeft -> Cursor.SW_RESIZE_CURSOR
            inBottom && inRight -> Cursor.SE_RESIZE_CURSOR
            inTop -> Cursor.N_RESIZE_CURSOR
            inBottom -> Cursor.S_RESIZE_CURSOR
            inLeft -> Cursor.W_RESIZE_CURSOR
            inRight -> Cursor.E_RESIZE_CURSOR
            else -> Cursor.DEFAULT_CURSOR
        }
        component.cursor = Cursor.getPredefinedCursor(cursorType)
    }

    override fun mousePressed(e: MouseEvent) {
        if (cursorType != Cursor.DEFAULT_CURSOR) {
            val frame = SwingUtilities.getWindowAncestor(component) as? JFrame ?: return
            if (frame.extendedState == JFrame.MAXIMIZED_BOTH) return
            resizing = true
            initialRect = frame.bounds
            initialPoint = e.locationOnScreen
            resizeDir = cursorType
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        if (resizing && initialRect != null && initialPoint != null) {
            val frame = SwingUtilities.getWindowAncestor(component) as? JFrame ?: return
            val dx = e.locationOnScreen.x - initialPoint!!.x
            val dy = e.locationOnScreen.y - initialPoint!!.y
            
            var x = initialRect!!.x
            var y = initialRect!!.y
            var w = initialRect!!.width
            var h = initialRect!!.height

            when (resizeDir) {
                Cursor.E_RESIZE_CURSOR -> w += dx
                Cursor.W_RESIZE_CURSOR -> { x += dx; w -= dx }
                Cursor.N_RESIZE_CURSOR -> { y += dy; h -= dy }
                Cursor.S_RESIZE_CURSOR -> h += dy
                Cursor.NE_RESIZE_CURSOR -> { y += dy; h -= dy; w += dx }
                Cursor.NW_RESIZE_CURSOR -> { x += dx; w -= dx; y += dy; h -= dy }
                Cursor.SE_RESIZE_CURSOR -> { w += dx; h += dy }
                Cursor.SW_RESIZE_CURSOR -> { x += dx; w -= dx; h += dy }
            }

            // Enforce minimum size
            val minSize = frame.minimumSize
            if (w < minSize.width) {
                if (resizeDir == Cursor.W_RESIZE_CURSOR || resizeDir == Cursor.NW_RESIZE_CURSOR || resizeDir == Cursor.SW_RESIZE_CURSOR) {
                    x -= (minSize.width - w)
                }
                w = minSize.width
            }
            if (h < minSize.height) {
                if (resizeDir == Cursor.N_RESIZE_CURSOR || resizeDir == Cursor.NW_RESIZE_CURSOR || resizeDir == Cursor.NE_RESIZE_CURSOR) {
                    y -= (minSize.height - h)
                }
                h = minSize.height
            }

            frame.setBounds(x, y, w, h)
            frame.revalidate()
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        resizing = false
        component.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
    }
}
