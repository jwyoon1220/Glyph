package io.github.jwyoon1220.glyph

import java.awt.*
import java.io.File
import javax.swing.*

object NewFilePopup {
    fun showPopup(owner: Component, parentDir: File, onCreated: (File) -> Unit) {
        val window = SwingUtilities.getWindowAncestor(owner) as? JFrame ?: return
        
        val dialog = JDialog(window, "New Item", true)
        dialog.isUndecorated = true
        
        val panel = JPanel(GridBagLayout())
        panel.background = Color(1, 22, 39) // Night Owl background
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(95, 126, 151), 1),
            BorderFactory.createEmptyBorder(15, 15, 15, 15)
        )
        
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        val titleLabel = JLabel("새 파일 / 폴더 만들기")
        titleLabel.foreground = Color.WHITE
        titleLabel.font = Font("SansSerif", Font.BOLD, 14)
        
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        panel.add(titleLabel, gbc)
        
        val typeComboBox = JComboBox(arrayOf("회차 (Episode)", "프롤로그 (Prologue)", "위키 (Wiki)", "일반 텍스트", "폴더 (Folder)"))
        typeComboBox.background = Color(29, 59, 83)
        typeComboBox.foreground = Color.WHITE
        
        gbc.gridy = 1
        gbc.gridwidth = 2
        panel.add(typeComboBox, gbc)
        
        val nameField = JTextField(15)
        nameField.background = Color(29, 59, 83)
        nameField.foreground = Color.WHITE
        nameField.caretColor = Color.WHITE
        
        gbc.gridy = 2
        panel.add(nameField, gbc)
        
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        btnPanel.isOpaque = false
        val okBtn = JButton("확인")
        val cancelBtn = JButton("취소")
        
        btnPanel.add(okBtn)
        btnPanel.add(cancelBtn)
        
        gbc.gridy = 3
        panel.add(btnPanel, gbc)
        
        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(window)
        
        val createAction = {
            val name = nameField.text.trim()
            if (name.isNotEmpty()) {
                val isFolder = typeComboBox.selectedIndex == 4
                val ext = when(typeComboBox.selectedIndex) {
                    0 -> ".gle"
                    1 -> ".glp"
                    2 -> ".glw"
                    3 -> ".md"
                    else -> ""
                }
                val fileName = if (isFolder || name.contains(".")) name else name + ext
                val newFile = File(parentDir, fileName)
                
                if (!newFile.exists()) {
                    if (isFolder) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        newFile.writeText("")
                    }
                    dialog.dispose()
                    onCreated(newFile)
                } else {
                    NativeUtils.showError(dialog, "오류", "이미 존재하는 이름입니다.")
                }
            }
        }
        
        okBtn.addActionListener { createAction() }
        nameField.addActionListener { createAction() }
        cancelBtn.addActionListener { dialog.dispose() }
        
        dialog.isVisible = true
    }
}
