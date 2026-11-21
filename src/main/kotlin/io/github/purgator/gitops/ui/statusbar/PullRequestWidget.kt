package io.github.purgator.gitops.ui.statusbar

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.accounts.AccountListener
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.modules.ModuleListener
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.git.BranchRules
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.GitOpsListener
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.pr.CreatePullRequestDialog
import io.github.purgator.gitops.ui.simpleAction
import java.awt.Component
import java.awt.event.MouseEvent

class PullRequestWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = PullRequestWidget.ID
    override fun getDisplayName(): String = "GitOps Studio Pull Request"
    override fun isAvailable(project: Project): Boolean = GitOpsSettings.get().values.showStatusBarWidget
    override fun createWidget(project: Project): StatusBarWidget = PullRequestWidget(project)
    override fun isEnabledByDefault(): Boolean = true
}

/**
 * Status bar entry for the current branch: `PR #42` when it has an open pull request (click for quick
 * actions), `Create PR` on a feature branch without one, nothing on protected branches.
 */
class PullRequestWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var statusBar: StatusBar? = null
    private var text = ""
    private var tooltip = ""
    private var pullRequest: PullRequest? = null
    private var lookedUp: String? = null
    private var sequence = 0

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val app = ApplicationManager.getApplication().messageBus.connect(this)
        app.subscribe(ModuleListener.TOPIC, ModuleListener { schedule(force = true) })
        app.subscribe(AccountListener.TOPIC, AccountListener { schedule(force = true) })
        val bus = project.messageBus.connect(this)
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { schedule(force = false) })
        bus.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { schedule(force = false) })
        bus.subscribe(GitOpsListener.TOPIC, object : GitOpsListener {
            override fun refreshRequested() = schedule(force = true)
        })
        schedule(force = true)
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = text
    override fun getTooltipText(): String = tooltip
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    private fun schedule(force: Boolean) {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ update(force) }, 600)
    }

    private fun show(newText: String, newTooltip: String, pr: PullRequest?) {
        text = newText
        tooltip = newTooltip
        pullRequest = pr
        statusBar?.updateWidget(ID)
    }

    private fun update(force: Boolean) {
        if (project.isDisposed) return
        val repo = RepoContext.get(project).current()?.takeIf { it.account != null }
        val branch = repo?.currentBranch
        if (repo == null || branch == null) {
            lookedUp = null
            show("", "", null)
            return
        }
        // Git fires many change events; only ask the server when the branch really changed.
        val key = repo.ref.fullName + "@" + branch
        if (!force && key == lookedUp) return
        lookedUp = key
        val request = ++sequence
        val short = repo.provider.terms.pullRequestShort
        runAsync(null, { repo.client().findOpenPullRequest(repo.ref, branch) }, onError = {
            if (request == sequence) show("", "", null)
        }, isAlive = { !project.isDisposed }) { pr ->
            if (request != sequence) return@runAsync
            when {
                pr != null -> show(
                    "$short #${pr.id}" + if (pr.state == PrState.DRAFT) " (draft)" else "",
                    "${pr.title} — ${pr.sourceBranch} → ${pr.targetBranch}",
                    pr,
                )
                BranchRules.isProtected(branch) -> show("", "", null)
                else -> show("Create $short", "Create a ${repo.provider.terms.pullRequest.lowercase()} for $branch", null)
            }
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val pr = pullRequest
        if (pr == null) {
            if (text.isNotEmpty()) CreatePullRequestDialog.open(project)
            return@Consumer
        }
        val group = DefaultActionGroup(
            simpleAction("Show in GitOps Studio") { GitOps.showPullRequest(project, pr.id) },
            simpleAction("Open in Browser") { BrowserUtil.browse(pr.webUrl) },
            simpleAction("Copy Link") { copyToClipboard(pr.webUrl) },
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup("#${pr.id} ${pr.title}", group, SimpleDataContext.getProjectContext(project), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .show(RelativePoint(event))
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "GitOpsStudio.PullRequest"
    }
}
