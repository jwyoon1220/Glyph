package io.github.jwyoon1220.glyph

import java.awt.Component
import java.io.File
import javax.swing.JFileChooser

class FileChooser(val parent: Component?) {
    var selectedFile: File? = null
        private set

    var isVisible: Boolean = false
        set(value) {
            if (value) {
                val chooser = JFileChooser()
                chooser.dialogTitle = "Select Project Folder"
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY

                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                    selectedFile = chooser.selectedFile
                }
            }
            field = value
        }
}
