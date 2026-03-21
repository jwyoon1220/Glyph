package io.github.jwyoon1220.glyph

import java.awt.*
import java.awt.geom.*
import java.io.File
import javax.swing.Icon

object IconProvider {
    fun getIconForFile(file: File): Icon {
        if (file.isDirectory) return FolderIcon()
        return when (file.extension.lowercase()) {
            "gle" -> DocumentIcon(Color(130, 170, 255), "E") // Blue
            "glp" -> DocumentIcon(Color(199, 146, 234), "P") // Purple
            "glw" -> DocumentIcon(Color(195, 232, 141), "W") // Green
            else -> DocumentIcon(Color(176, 190, 197), "")   // Gray
        }
    }
}

class FolderIcon : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.translate(x, y)
        
        g2.color = Color(236, 196, 115)
        val path = Path2D.Float()
        path.moveTo(1f, 3f)
        path.lineTo(6f, 3f)
        path.lineTo(8f, 5f)
        path.lineTo(15f, 5f)
        path.lineTo(15f, 13f)
        path.lineTo(1f, 13f)
        path.closePath()
        g2.fill(path)
        g2.dispose()
    }
    override fun getIconWidth() = 16
    override fun getIconHeight() = 16
}

class DocumentIcon(val color: Color, val badgeText: String) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.translate(x, y)
        
        g2.color = color
        g2.fillRect(3, 2, 10, 12)
        
        // Folded corner
        g2.color = c?.background ?: Color(1, 22, 39)
        g2.fillRect(10, 2, 3, 3)
        g2.color = color.darker()
        g2.fillPolygon(intArrayOf(10, 13, 10), intArrayOf(2, 5, 5), 3)
        
        if (badgeText.isNotEmpty()) {
            g2.color = c?.background ?: Color(1, 22, 39)
            g2.font = Font("SansSerif", Font.BOLD, 9)
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(badgeText)
            g2.drawString(badgeText, 3 + (10 - tw) / 2, 12)
        }
        
        g2.dispose()
    }
    override fun getIconWidth() = 16
    override fun getIconHeight() = 16
}
