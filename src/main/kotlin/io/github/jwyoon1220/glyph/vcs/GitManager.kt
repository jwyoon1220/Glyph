package io.github.jwyoon1220.glyph.vcs

import org.eclipse.jgit.api.Git
import java.io.File

data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val parents: List<String>,
    val refs: List<String>
)

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
    
    fun getCommitLog(): List<CommitInfo> {
        val refsMap = mutableMapOf<String, MutableList<String>>()
        try {
            git.repository.refDatabase.refs.forEach { ref ->
                val objId = ref.peeledObjectId?.name ?: ref.objectId.name
                refsMap.computeIfAbsent(objId) { mutableListOf() }.add(ref.name)
            }
            
            val commits = git.log().all().call()
            return commits.map { rev ->
                val refs = refsMap[rev.name] ?: emptyList()
                CommitInfo(
                    hash = rev.name,
                    shortHash = rev.name.take(7),
                    message = rev.shortMessage,
                    author = rev.authorIdent.name,
                    parents = rev.parents.map { it.name },
                    refs = refs.map { it.removePrefix("refs/heads/").removePrefix("refs/remotes/").removePrefix("refs/tags/") }
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    fun restoreSnapshot(commitHash: String) {
        git.checkout().setName(commitHash).call()
    }
    
    fun undo(): Boolean {
        return try {
            val commits = git.log().setMaxCount(2).call().toList()
            if (commits.size > 1) {
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(commits[1].name).call()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
