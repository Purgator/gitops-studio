package io.github.purgator.gitops.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import java.io.IOException

/** Thin wrappers over Git4Idea for the handful of local git operations the plugin needs. All blocking. */
object GitSupport {
    class Commit(val hash: String, val subject: String, val body: String)

    /** Local branches, current one first. */
    fun localBranches(repo: GitRepository): List<String> {
        val current = repo.currentBranchName
        return repo.branches.localBranches.map { it.name }.sortedWith(compareBy({ it != current }, { it.lowercase() }))
    }

    /** Branch names existing on [remote], as last fetched. */
    fun remoteBranches(repo: GitRepository, remote: GitRemote): List<String> =
        repo.branches.remoteBranches
            .filter { it.remote == remote }
            .map { it.nameForRemoteOperations }
            .filter { it != "HEAD" }
            .sortedBy { it.lowercase() }

    /** True when [branch] exists on [remote] at the same commit as locally. */
    fun isPushed(repo: GitRepository, remote: GitRemote, branch: String): Boolean {
        val local = repo.branches.findLocalBranch(branch) ?: return true
        val remoteBranch = repo.branches.remoteBranches.firstOrNull { it.remote == remote && it.nameForRemoteOperations == branch }
            ?: return false
        return repo.branches.getHash(local) == repo.branches.getHash(remoteBranch)
    }

    @Throws(IOException::class)
    fun push(repo: GitRepository, remote: GitRemote, branch: String) {
        val url = remote.firstUrl ?: throw IOException("Remote ${remote.name} has no URL.")
        val result = Git.getInstance().push(repo, remote.name, url, "refs/heads/$branch:refs/heads/$branch", true)
        if (!result.success()) throw IOException("Push failed: " + result.errorOutputAsJoinedString.ifBlank { "git exited with ${result.exitCode}" })
        repo.update()
    }

    @Throws(IOException::class)
    fun fetch(repo: GitRepository, remote: GitRemote, vararg refspecs: String) {
        val result = Git.getInstance().fetch(repo, remote, emptyList(), *refspecs)
        if (!result.success()) throw IOException("Fetch failed: " + result.errorOutputAsJoinedString.ifBlank { "git exited with ${result.exitCode}" })
        repo.update()
    }

    /** Commits reachable from [head] but not from [base], newest first. Empty when a ref is unknown. */
    fun commitsBetween(project: Project, repo: GitRepository, base: String, head: String, limit: Int = 100): List<Commit> {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.setSilent(true)
        handler.addParameters("--max-count=$limit", "--no-merges", "--format=%H%x1f%s%x1f%b%x1e", "$base..$head")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result.outputAsJoinedString.split('\u001e')
            .map { it.trim('\n', '\r', ' ') }
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val parts = record.split('\u001f')
                if (parts.size < 2) null else Commit(parts[0], parts[1], parts.getOrElse(2) { "" }.trim())
            }
    }

    @Throws(IOException::class)
    fun run(project: Project, repo: GitRepository, command: GitCommand, vararg args: String): List<String> {
        val handler = GitLineHandler(project, repo.root, command)
        handler.setSilent(true)
        handler.addParameters(*args)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw IOException(result.errorOutputAsJoinedString.ifBlank { "git ${command.name()} failed (${result.exitCode})" })
        return result.output
    }

    /** Changes a pull request introduces: what [head] added since it forked from [base]. */
    @Throws(IOException::class)
    fun changesBetween(repo: GitRepository, base: String, head: String): Collection<Change> =
        try {
            GitChangeUtils.getThreeDotDiffOrThrow(repo, base, head)
        } catch (e: VcsException) {
            throw IOException(e.message, e)
        }

    /** Best existing ref for a branch of [remote]: the remote-tracking branch if known, else the local one. */
    fun refFor(repo: GitRepository, remote: GitRemote, branch: String): String? = when {
        repo.branches.findRemoteBranch("${remote.name}/$branch") != null -> "${remote.name}/$branch"
        repo.branches.findLocalBranch(branch) != null -> branch
        else -> null
    }
}
