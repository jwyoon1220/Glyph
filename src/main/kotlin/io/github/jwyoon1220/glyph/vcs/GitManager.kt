package io.github.jwyoon1220.glyph.vcs

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.io.File

data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val date: Long,
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
        val refsMap = Object2ObjectOpenHashMap<String, MutableList<String>>()
        try {
            git.repository.refDatabase.refs.forEach { ref ->
                val objId = ref.peeledObjectId?.name ?: ref.objectId.name
                refsMap.computeIfAbsent(objId) { ObjectArrayList() }.add(ref.name)
            }

            val commits = git.log().all().call()
            return commits.map { rev ->
                val refs = refsMap[rev.name] ?: emptyList()
                CommitInfo(
                    hash = rev.name,
                    shortHash = rev.name.take(7),
                    message = rev.shortMessage,
                    author = rev.authorIdent.name,
                    date = rev.authorIdent.`when`.time,
                    parents = rev.parents.map { it.name },
                    refs = refs.map {
                        it.removePrefix("refs/heads/")
                            .removePrefix("refs/remotes/")
                            .removePrefix("refs/tags/")
                    }
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun getCurrentBranch(): String {
        return try {
            git.repository.branch ?: "HEAD"
        } catch (e: Exception) {
            "HEAD"
        }
    }

    fun getRemoteUrl(): String {
        return try {
            git.remoteList().call().firstOrNull()?.getURIs()?.firstOrNull()?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setRemoteUrl(url: String) {
        val existing = try { git.remoteList().call() } catch (e: Exception) { emptyList() }
        if (existing.any { it.name == "origin" }) {
            git.remoteSetUrl()
                .setRemoteName("origin")
                .setRemoteUri(URIish(url))
                .call()
        } else {
            git.remoteAdd()
                .setName("origin")
                .setUri(URIish(url))
                .call()
        }
    }

    fun push(username: String, token: String): String? {
        return try {
            val creds = UsernamePasswordCredentialsProvider(username, token)
            val results = git.push().setCredentialsProvider(creds).call()
            val msgs = results.flatMap { r -> r.remoteUpdates.mapNotNull { it.message } }
            if (msgs.isNotEmpty()) msgs.joinToString("\n") else null
        } catch (e: Exception) {
            e.message
        }
    }

    fun pull(username: String, token: String): String? {
        return try {
            val creds = UsernamePasswordCredentialsProvider(username, token)
            val result = git.pull().setCredentialsProvider(creds).call()
            if (!result.isSuccessful) result.mergeResult?.mergeStatus?.toString() else null
        } catch (e: Exception) {
            e.message
        }
    }

    fun rollbackTo(commitHash: String): Boolean {
        return try {
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(commitHash)
                .call()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun undo(): Boolean {
        return try {
            val commits = git.log().setMaxCount(2).call().toList()
            if (commits.size > 1) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commits[1].name).call()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
