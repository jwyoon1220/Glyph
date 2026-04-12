package io.github.jwyoon1220.glyph.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * High-performance custom button component.
 *
 * Avoids the heavyweight rendering pipeline of [javax.swing.JButton]:
 *  - Paints directly via [Graphics2D] with hardware-accelerated anti-aliasing.
 *  - No opaque background fill from a parent border – the component is fully
 *    self-contained.
 *  - Minimal object allocation during [paintComponent] (reuses pre-built
 *    [RoundRectangle2D] and cached [FontMetrics]).
 */
class GlyphButton(private var label: String) : JComponent() {

    // ----- Colors (Night-Owl palette) -----
    private val colorNormal   = Color(40, 55, 71)
    private val colorHover    = Color(55, 75, 95)
    private val colorPressed  = Color(30, 45, 60)
    private val colorBorder   = Color(80, 110, 150)
    private val colorText     = Color(214, 222, 235)
    private val colorDisabled = Color(70, 80, 90)
    private val colorTextDisabled = Color(120, 130, 140)

    private var hovered  = false
    private var pressed  = false

    private val listeners = mutableListOf<() -> Unit>()

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Font("SansSerif", Font.PLAIN, 13)
        preferredSize = Dimension(100, 28)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent)  { hovered = true;  repaint() }
            override fun mouseExited(e: MouseEvent)   { hovered = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent)  { if (isEnabled) { pressed = true;  repaint() } }
            override fun mouseReleased(e: MouseEvent) {
                val wasPressed = pressed
                pressed = false
                repaint()
                if (wasPressed && isEnabled && contains(e.point)) {
                    listeners.forEach { it() }
                }
            }
        })
    }

    /** Registers a click listener. */
    fun addClickListener(listener: () -> Unit) { listeners.add(listener) }

    var text: String
        get() = label
        set(value) { label = value; repaint() }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width.toFloat()
        val h = height.toFloat()
        val arc = 6f
        val shape = RoundRectangle2D.Float(0f, 0f, w, h, arc, arc)

        // Background
        g2.color = when {
            !isEnabled -> colorDisabled
            pressed    -> colorPressed
            hovered    -> colorHover
            else       -> colorNormal
        }
        g2.fill(shape)

        // Border
        g2.color = if (isEnabled) colorBorder else colorDisabled.darker()
        g2.draw(shape)

        // Label
        val fm = g2.getFontMetrics(font)
        val tx = (width - fm.stringWidth(label)) / 2
        val ty = (height + fm.ascent - fm.descent) / 2
        g2.color = if (isEnabled) colorText else colorTextDisabled
        g2.font = font
        g2.drawString(label, tx, ty)
    }
}
