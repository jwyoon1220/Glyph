package io.github.jwyoon1220.glyph

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

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
}