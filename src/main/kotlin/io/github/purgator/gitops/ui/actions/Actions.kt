package io.github.purgator.gitops.ui.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.git.GitSupport
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.pr.CreatePullRequestPanel
import io.github.purgator.gitops.ui.settings.GitOpsConfigurable
import io.github.purgator.gitops.ui.setup.SetupWizard

/** The "GitOps Studio" submenu of the editor, project view and editor tab context menus. */
class ContextMenuGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isHideGroupIfEmpty = true
        // The menu is about a file of a git repository; elsewhere it would only hold generic entries.
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isVisible = project != null && file != null &&
            GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) != null
    }
}

/** Actions are registered statically but stay invisible until a cloud module is switched on. */
abstract class CloudAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ModuleRegistry.get().providers().isNotEmpty()
    }
}

class CreatePullRequestAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Always reachable: when nothing is set up yet, invoking it starts the guided setup.
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        CreatePullRequestPanel.open(project, file?.let { targetOf(project, it) })
    }
}

class ShowToolWindowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { GitOps.showToolWindow(it) }
    }
}

class SetupWizardAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = SetupWizard.show(e.project)
}

class OpenSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GitOpsConfigurable::class.java)
    }
}

class CloneFromCloudAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ModuleRegistry.get().providers().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) = CloneFromCloudDialog.open(e.project)
}

class RefreshAction : CloudAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { GitOps.refresh(it) }
    }
}

/** Opens the pull request of the current branch, or offers to create one. */
class OpenBranchPullRequestAction : CloudAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = RepoContext.get(project).current() ?: return CreatePullRequestPanel.open(project)
        val branch = repo.currentBranch ?: return
        io.github.purgator.gitops.core.util.runAsync(null, { repo.client().findOpenPullRequest(repo.ref, branch) }, onError = {
            Notify.error(project, "GitOps Studio", it.message.orEmpty())
        }) { pr ->
            if (pr != null) BrowserUtil.browse(pr.webUrl) else CreatePullRequestPanel.open(project, repo)
        }
    }
}

/** "Open on <host>" / "Copy link" for the file (and selected lines) under the caret. */
abstract class FileLinkAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val target = if (project != null && file != null) targetOf(project, file) else null
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = text(target.provider.displayName)
    }

    protected abstract fun text(host: String): String
    protected abstract fun handle(project: Project, url: String)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val target = targetOf(project, file) ?: return
        val path = VfsUtilCore.getRelativePath(file, target.git.root) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val lines = editor?.takeIf { !file.isDirectory }?.let {
            val selection = it.selectionModel
            val start = it.document.getLineNumber(selection.selectionStart)
            var end = it.document.getLineNumber(selection.selectionEnd)
            // A selection ending at column 0 does not include that last line.
            if (end > start && selection.selectionEnd == it.document.getLineStartOffset(end)) end--
            start..end
        }
        // Link to the branch when the server has it, otherwise to the exact commit.
        val branch = target.currentBranch?.takeIf { GitSupport.refFor(target.git, target.remote, it) == "${target.remote.name}/$it" }
        val ref = branch ?: target.git.currentRevision ?: return
        handle(project, target.provider.fileUrl(target.ref, ref, path, lines))
    }
}

class OpenFileInCloudAction : FileLinkAction() {
    override fun text(host: String) = "Open on $host"
    override fun handle(project: Project, url: String) = BrowserUtil.browse(url)
}

/** Same as [OpenFileInCloudAction], labelled with the host alone for the IDE's "Open In" submenu. */
class OpenFileInCloudMenuAction : FileLinkAction() {
    override fun text(host: String) = host
    override fun handle(project: Project, url: String) = BrowserUtil.browse(url)
}

class CopyFileCloudLinkAction: FileLinkAction() {
    override fun text(host: String) = "Copy $host Link"
    override fun handle(project: Project, url: String) {
        copyToClipboard(url)
        Notify.info(project, "Link copied", url)
    }
}

internal fun targetOf(project: Project, file: VirtualFile): RepoTarget? {
    val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
    return RepoContext.get(project).targets().firstOrNull { it.git == repo }
}
