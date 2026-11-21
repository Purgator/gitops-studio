package io.github.purgator.gitops.git

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.modules.GitOpsModule
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.runWithProgress
import io.github.purgator.gitops.core.util.userMessage
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/** The local Git module: commit message helpers and branch housekeeping. It talks to no server. */
class GitModule : GitOpsModule

object BranchRules {
    /** True when [branch] matches one of the protected patterns from the settings (`*` is a wildcard). */
    fun isProtected(branch: String, patterns: String = GitOpsSettings.get().values.protectedBranches): Boolean =
        patterns.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { pattern ->
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(branch)
        }
}

abstract class GitModuleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ModuleRegistry.get().isEnabled(ModuleRegistry.GIT) &&
            GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }

    /** Repository of the file in context, else the project's first one. */
    protected fun repository(e: AnActionEvent): GitRepository? {
        val project = e.project ?: return null
        val manager = GitRepositoryManager.getInstance(project)
        return e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { manager.getRepositoryForFileQuick(it) } ?: manager.repositories.firstOrNull()
    }
}

/** Fetches every remote and fast-forwards the default branch, even when another branch is checked out. */
class SyncDefaultBranchAction : GitModuleAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = repository(e) ?: return
        val prune = GitOpsSettings.get().values.pruneOnSync
        project.runWithProgress("Syncing ${repo.root.name}", { indicator ->
            val report = ArrayList<String>()
            for (remote in repo.remotes) {
                indicator.text = "Fetching ${remote.name}"
                if (prune) GitSupport.fetch(repo, remote, "--prune") else GitSupport.fetch(repo, remote)
                report += "fetched ${remote.name}"
            }
            val remote = repo.remotes.firstOrNull { it.name == GitRemote.ORIGIN } ?: repo.remotes.firstOrNull()
            val default = remote?.let { defaultBranch(repo, it) }
            if (remote != null && default != null && repo.branches.findLocalBranch(default) != null) {
                indicator.text = "Fast-forwarding $default"
                if (repo.currentBranchName == default) {
                    GitSupport.run(project, repo, GitCommand.MERGE, "--ff-only", "${remote.name}/$default")
                    VfsUtil.markDirtyAndRefresh(true, true, false, repo.root)
                } else {
                    GitSupport.fetch(repo, remote, "$default:$default")
                }
                report += "$default is up to date"
            }
            repo.update()
            report.joinToString(", ")
        }, onError = { Notify.error(project, "Sync failed", it.userMessage()) }) { report ->
            Notify.info(project, "Synced ${repo.root.name}", report.replaceFirstChar { it.uppercase() } + ".")
        }
    }

    companion object {
        /** The remote's default branch as git knows it, with the usual names as a fallback. */
        fun defaultBranch(repo: GitRepository, remote: GitRemote): String? {
            val names = repo.branches.remoteBranches.filter { it.remote == remote }.map { it.nameForRemoteOperations }.toSet()
            return listOf("main", "master", "develop", "trunk").firstOrNull { it in names }
        }
    }
}

/** Lists local branches that are merged or whose upstream is gone, and deletes the ones you tick. */
class CleanUpBranchesAction : GitModuleAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = repository(e) ?: return
        CleanUpBranchesDialog(project, repo).show()
    }
}

private class CleanUpBranchesDialog(private val project: Project, private val repo: GitRepository) : DialogWrapper(project) {
    private class Candidate(val name: String, val reason: String) {
        override fun toString(): String = "$name   —   $reason"
    }

    private val list = CheckBoxList<Candidate>()
    private val hint = JBLabel(" ")
    private val fetchAction = object : AbstractAction("Fetch and Prune") {
        override fun actionPerformed(e: ActionEvent) = scan(fetchFirst = true)
    }

    init {
        title = "Clean Up Branches — ${repo.root.name}"
        setOKButtonText("Delete Selected")
        list.emptyText.text = "Nothing to clean up"
        init()
        scan(fetchFirst = false)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(JBLabel("Local branches that are already merged, or whose remote branch was deleted:"), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
        preferredSize = Dimension(JBUI.scale(560), JBUI.scale(340))
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(fetchAction)

    private fun scan(fetchFirst: Boolean) {
        hint.text = if (fetchFirst) "Fetching…" else "Looking for stale branches…"
        fetchAction.isEnabled = false
        isOKActionEnabled = false
        runAsync(contentPanel, {
            if (fetchFirst) repo.remotes.forEach { GitSupport.fetch(repo, it, "--prune") }
            findCandidates()
        }, onError = {
            fetchAction.isEnabled = true
            hint.text = it.userMessage()
        }, isAlive = { !isDisposed }) { (base, candidates) ->
            fetchAction.isEnabled = true
            list.clear()
            candidates.forEach { list.addItem(it, it.toString(), true) }
            hint.text = if (candidates.isEmpty()) "Every local branch is still in use." else "Compared with $base. The current and protected branches are never listed."
            isOKActionEnabled = candidates.isNotEmpty()
        }
    }

    private fun findCandidates(): Pair<String, List<Candidate>> {
        val current = repo.currentBranchName
        val remote = repo.remotes.firstOrNull { it.name == GitRemote.ORIGIN } ?: repo.remotes.firstOrNull()
        val base = remote?.let { r -> SyncDefaultBranchAction.defaultBranch(repo, r)?.let { "${r.name}/$it" } } ?: current ?: "HEAD"
        val merged = GitSupport.run(project, repo, GitCommand.BRANCH, "--merged", base, "--format=%(refname:short)")
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val gone = GitSupport.run(project, repo, GitCommand.BRANCH, "--format=%(refname:short)|%(upstream:track)")
            .mapNotNull { line -> line.split('|').takeIf { it.size == 2 && "gone" in it[1] }?.get(0)?.trim() }.toSet()
        val candidates = repo.branches.localBranches.map { it.name }
            .filter { it != current && !BranchRules.isProtected(it) }
            .mapNotNull { name ->
                when (name) {
                    in gone -> Candidate(name, "remote branch deleted")
                    in merged -> Candidate(name, "merged into $base")
                    else -> null
                }
            }
            .sortedBy { it.name.lowercase() }
        return base to candidates
    }

    override fun doOKAction() {
        val chosen = (0 until list.itemsCount).mapNotNull { list.getItemAt(it) }.filter { list.isItemSelected(it) }
        if (chosen.isEmpty()) return super.doOKAction()
        super.doOKAction()
        project.runWithProgress("Deleting ${chosen.size} branches", { _ ->
            val failed = chosen.filter { !Git.getInstance().branchDelete(repo, it.name, true).success() }
            repo.update()
            failed.map { it.name }
        }) { failed ->
            val deleted = chosen.size - failed.size
            if (failed.isEmpty()) {
                Notify.info(project, "Deleted $deleted branch" + if (deleted == 1) "" else "es")
            } else {
                Notify.warn(project, "Deleted $deleted branches", "Could not delete: ${failed.joinToString(", ")}")
            }
        }
    }
}

