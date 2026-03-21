package io.github.jwyoon1220.glyph

import io.github.jwyoon1220.glyph.data.WikiGraph
import io.github.jwyoon1220.glyph.data.WikiNode
import io.github.jwyoon1220.glyph.data.WikiNodeType
import java.awt.*
import java.awt.event.*
import java.awt.geom.CubicCurve2D
import kotlin.math.max
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class WikiGraphEditorComponent(
    initialGraph: WikiGraph?,
    val onStructureChanged: (WikiGraph) -> Unit
) : JPanel(BorderLayout()) {

    val graph = initialGraph ?: WikiGraph().apply {
        nodes.add(WikiNode(title = "Character Name", type = WikiNodeType.CHARACTER, x = 100, y = 100))
    }

    private val canvas = NodeCanvas()
    var onTypingStopped: (() -> Unit)? = null
    private var saveTimer: Timer? = null

    init {
        background = Color(20, 20, 20)
        
        // Toolbar
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            background = Color(30, 30, 30)
            val btnAdd = JButton("Add Node").apply {
                addActionListener {
                    val newNode = WikiNode(title = "New Property", type = WikiNodeType.PROPERTY, x = 100, y = 100)
                    graph.nodes.add(newNode)
                    triggerStructureChange()
                }
            }
            add(btnAdd)
        }
        add(topBar, BorderLayout.NORTH)

        // Scrollpane to allow infinite canvas sizing
        val scrollPane = JScrollPane(canvas).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = Color(20, 20, 20)
        }
        add(scrollPane, BorderLayout.CENTER)
        
        rebuildUI()
    }

    private fun rebuildUI() {
        canvas.removeAll()
        for (node in graph.nodes) {
            val nodePanel = NodePanel(node)
            canvas.add(nodePanel)
        }
        canvas.updateCanvasSize()
        canvas.repaint()
    }

    fun triggerSave() {
        saveTimer?.stop()
        saveTimer = Timer(1000) {
            onStructureChanged(graph)
            onTypingStopped?.invoke()
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun triggerStructureChange() {
        onStructureChanged(graph)
        onTypingStopped?.invoke()
        rebuildUI()
    }

    inner class NodeCanvas : JPanel(null) {
        var draggingConnectionStartNode: WikiNode? = null
        var mouseDragX: Int = 0
        var mouseDragY: Int = 0

        var scale = 1.0
        var panX = 0.0
        var panY = 0.0

        init {
            background = Color(24, 24, 24)
            
            // Panning logic (Middle Mouse Button)
            val panAdapter = object : MouseAdapter() {
                private var lastMousePos: Point? = null

                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        lastMousePos = e.point
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        lastMousePos = null
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        val currentPos = e.point
                        val dx = currentPos.x - (lastMousePos?.x ?: currentPos.x)
                        val dy = currentPos.y - (lastMousePos?.y ?: currentPos.y)
                        panX += dx
                        panY += dy
                        lastMousePos = currentPos
                        repaint()
                    }
                }
            }
            addMouseListener(panAdapter)
            addMouseMotionListener(panAdapter)

            // Zooming logic (Ctrl + Wheel)
            addMouseWheelListener { e ->
                if (e.isControlDown) {
                    val oldScale = scale
                    val zoomFactor = if (e.wheelRotation < 0) 1.1 else 0.9
                    scale *= zoomFactor
                    scale = scale.coerceIn(0.1, 5.0)

                    // Zoom towards mouse position
                    val mouseX = e.x.toDouble()
                    val mouseY = e.y.toDouble()
                    panX = mouseX - (mouseX - panX) * (scale / oldScale)
                    panY = mouseY - (mouseY - panY) * (scale / oldScale)
                    
                    repaint()
                }
            }

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (draggingConnectionStartNode != null) {
                        // Transform mouse coords back to canvas coords for line drawing
                        val p = transformPoint(e.point)
                        mouseDragX = p.x
                        mouseDragY = p.y
                        repaint()
                    }
                }
            })
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (draggingConnectionStartNode != null) {
                        val p = transformPoint(e.point)
                        val dropTarget = getComponentAt(p.x, p.y)
                        var targetNode: WikiNode? = null
                        if (dropTarget is NodePanel) targetNode = dropTarget.node
                        else if (dropTarget?.parent is NodePanel) targetNode = (dropTarget.parent as NodePanel).node

                        if (targetNode != null && targetNode != draggingConnectionStartNode) {
                            val startId = draggingConnectionStartNode!!.id
                            if (!targetNode.connectedToIds.contains(startId) && !draggingConnectionStartNode!!.connectedToIds.contains(targetNode.id)) {
                                draggingConnectionStartNode!!.connectedToIds.add(targetNode.id)
                                triggerStructureChange()
                            }
                        }
                        draggingConnectionStartNode = null
                        repaint()
                    }
                }
            })
        }

        fun transformPoint(p: Point): Point {
            val tx = (p.x - panX) / scale
            val ty = (p.y - panY) / scale
            return Point(tx.toInt(), ty.toInt())
        }

        fun updateCanvasSize() {
            // Under zoom/pan, we keep a large virtual area or just let panning handle it
            preferredSize = Dimension(5000, 5000)
            revalidate()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Apply global transformation
            val oldAt = g2d.transform
            g2d.translate(panX, panY)
            g2d.scale(scale, scale)

            // Draw grid dots
            g2d.color = Color(60, 60, 60)
            for (x in 0 until 5000 step 40) {
                for (y in 0 until 5000 step 40) {
                    g2d.fillRect(x, y, 2, 2)
                }
            }

            // Draw Bezier curves for connections
            g2d.stroke = BasicStroke(3f / scale.toFloat()) // Keep line width consistent
            for (node in graph.nodes) {
                for (childId in node.connectedToIds) {
                    val target = graph.nodes.find { it.id == childId }
                    if (target != null) drawBezier(g2d, node, target)
                }
            }

            // Draw dragging line
            val startNode = draggingConnectionStartNode
            if (startNode != null) {
                g2d.color = Color(247, 140, 108)
                val startX = startNode.x + 200
                val startY = startNode.y + 20
                val curve = CubicCurve2D.Float(
                    startX.toFloat(), startY.toFloat(),
                    (startX + (mouseDragX - startX) / 2).toFloat(), startY.toFloat(),
                    (mouseDragX - (mouseDragX - startX) / 2).toFloat(), mouseDragY.toFloat(),
                    mouseDragX.toFloat(), mouseDragY.toFloat()
                )
                g2d.draw(curve)
            }
            
            g2d.transform = oldAt
        }
        
        // Since we are using JComponents as nodes, we need to transform their drawing too
        override fun paintChildren(g: Graphics) {
            val g2d = g as Graphics2D
            val oldAt = g2d.transform
            g2d.translate(panX, panY)
            g2d.scale(scale, scale)
            super.paintChildren(g2d)
            g2d.transform = oldAt
        }
        
        // Override getComponentAt to handle scaled/panned children
        override fun getComponentAt(x: Int, y: Int): Component? {
            val tx = (x - panX) / scale
            val ty = (y - panY) / scale
            return super.getComponentAt(tx.toInt(), ty.toInt())
        }

        private fun drawBezier(g2d: Graphics2D, source: WikiNode, target: WikiNode) {
            val startX = source.x + 200 // Output port approx right edge
            val startY = source.y + 20
            val endX = target.x
            val endY = target.y + 20    // Input port approx left edge
            
            val curve = CubicCurve2D.Float(
                startX.toFloat(), startY.toFloat(),
                (startX + 100).toFloat(), startY.toFloat(),
                (endX - 100).toFloat(), endY.toFloat(),
                endX.toFloat(), endY.toFloat()
            )
            g2d.color = Color(130, 170, 255, 180) // Light Blue Glow
            g2d.draw(curve)
        }
    }

    inner class NodePanel(val node: WikiNode) : JPanel(BorderLayout(0, 0)) {
        private var initialClick: Point? = null

        init {
            setBounds(node.x, node.y, 200, 150)
            background = Color(43, 43, 43)
            border = BorderFactory.createLineBorder(Color(80, 80, 80), 2, true)

            // --- HEADER (Draggable) ---
            val header = JPanel(BorderLayout()).apply {
                background = Color(29, 59, 83)
                border = EmptyBorder(5, 5, 5, 5)
                
                val typeCombo = JComboBox(WikiNodeType.values()).apply {
                    selectedItem = node.type
                    addActionListener {
                        node.type = selectedItem as WikiNodeType
                        triggerSave()
                    }
                }
                add(typeCombo, BorderLayout.CENTER)
                
                val btnDelete = JButton("X").apply {
                    margin = Insets(0, 2, 0, 2)
                    isContentAreaFilled = false
                    foreground = Color.WHITE
                    addActionListener {
                        graph.nodes.remove(node)
                        for (n in graph.nodes) { n.connectedToIds.remove(node.id) }
                        triggerStructureChange()
                    }
                }
                add(btnDelete, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)

            // Make the header the drag handle
            val dragAdapter = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    initialClick = e.point
                    header.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
                override fun mouseReleased(e: MouseEvent) {
                    header.cursor = Cursor.getDefaultCursor()
                    canvas.updateCanvasSize()
                    triggerSave()
                }
                override fun mouseDragged(e: MouseEvent) {
                    val thisX = location.x
                    val thisY = location.y
                    val xMoved = e.x - (initialClick?.x ?: 0)
                    val yMoved = e.y - (initialClick?.y ?: 0)
                    val X = max(0, thisX + xMoved)
                    val Y = max(0, thisY + yMoved)
                    setLocation(X, Y)
                    node.x = X
                    node.y = Y
                    canvas.repaint()
                }
            }
            header.addMouseListener(dragAdapter)
            header.addMouseMotionListener(dragAdapter)
            
            // --- BODY ---
            val body = JPanel(BorderLayout(0, 5)).apply {
                background = Color(30, 30, 30)
                border = EmptyBorder(5, 5, 5, 5)
                
                val titleField = JTextField(node.title).apply {
                    font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 14)
                    background = Color(40, 40, 40)
                    foreground = Color.WHITE
                    caretColor = Color.WHITE
                    border = BorderFactory.createLineBorder(Color(60, 60, 60))
                                        
                    document.addDocumentListener(object: DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) { node.title = text; triggerSave() }
                        override fun removeUpdate(e: DocumentEvent?) { node.title = text; triggerSave() }
                        override fun changedUpdate(e: DocumentEvent?) { node.title = text; triggerSave() }
                    })
                }
                add(titleField, BorderLayout.NORTH)
                
                val contentArea = JTextArea(node.content).apply {
                    font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12)
                    background = Color(40, 40, 40)
                    foreground = Color(200, 200, 200)
                    caretColor = Color.WHITE
                    lineWrap = true
                    wrapStyleWord = true
                    border = BorderFactory.createLineBorder(Color(60, 60, 60))
                                        
                    document.addDocumentListener(object: DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) { node.content = text; triggerSave() }
                        override fun removeUpdate(e: DocumentEvent?) { node.content = text; triggerSave() }
                        override fun changedUpdate(e: DocumentEvent?) { node.content = text; triggerSave() }
                    })
                }
                add(JScrollPane(contentArea), BorderLayout.CENTER)
            }
            add(body, BorderLayout.CENTER)
            
            // --- PORTS ---
            // Just simple buttons on left/right edges for drawing lines
            val portPanel = JPanel(BorderLayout()).apply { isOpaque = false; preferredSize = Dimension(200, 10) }
            val portOut = JButton("●").apply {
                margin = Insets(0, 0, 0, 0)
                isContentAreaFilled = false
                foreground = Color.GREEN
                border = EmptyBorder(0, 0, 0, 0)
                setToolTipText("Drag to connect to another node's body")
                cursor = Cursor(Cursor.CROSSHAIR_CURSOR)
                
                val portDrag = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        canvas.draggingConnectionStartNode = node
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        // Pass event to canvas
                        val p = SwingUtilities.convertPoint(e.component, e.point, canvas)
                        val ev = MouseEvent(canvas, e.id, e.`when`, e.modifiersEx, p.x, p.y, e.clickCount, e.isPopupTrigger)
                        canvas.mouseListeners.forEach { it.mouseReleased(ev) }
                    }
                    override fun mouseDragged(e: MouseEvent) {
                        val p = SwingUtilities.convertPoint(e.component, e.point, canvas)
                        val ev = MouseEvent(canvas, e.id, e.`when`, e.modifiersEx, p.x, p.y, e.clickCount, e.isPopupTrigger)
                        canvas.mouseMotionListeners.forEach { it.mouseDragged(ev) }
                    }
                }
                addMouseListener(portDrag)
                addMouseMotionListener(portDrag)
            }
            portPanel.add(portOut, BorderLayout.EAST)
            
            // Add ports to the wrapper
            add(portPanel, BorderLayout.SOUTH)
        }
    }
}
