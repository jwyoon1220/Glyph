package io.github.jwyoon1220.glyph.vcs

import org.eclipse.jgit.api.Git
import java.io.File

class GitManager(private val rootDirectory: File) {
    private val git: Git

    init {
        val repoDir = File(rootDirectory, ".git")
        git = if (repoDir.exists()) {
            Git.open(rootDirectory)
        } else {
            Git.init().setDirectory(rootDirectory).call()
        }
    }

    fun commitAll(message: String) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor("Glyph", "glyph@local").call()
    }
    
    fun getUndoHistory(): List<String> {
        return try {
            git.reflog().call().map { it.newId.name }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun restoreSnapshot(commitHash: String) {
        git.checkout().setName(commitHash).call()
    }
}
