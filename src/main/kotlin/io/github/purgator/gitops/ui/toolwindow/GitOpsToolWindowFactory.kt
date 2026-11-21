package io.github.purgator.gitops.ui.toolwindow

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.accounts.AccountListener
import io.github.purgator.gitops.core.modules.ModuleListener
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.issues.IssuesPanel
import io.github.purgator.gitops.ui.pipelines.PipelinesPanel
import io.github.purgator.gitops.ui.pr.PullRequestsPanel
import io.github.purgator.gitops.ui.settings.GitOpsConfigurable
import io.github.purgator.gitops.ui.setup.SetupWizard
import io.github.purgator.gitops.ui.simpleAction
import javax.swing.JComponent

class GitOpsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = ToolWindowController(project, toolWindow)
        Disposer.register(toolWindow.disposable, controller)
        controller.rebuild()
    }
}

/**
 * Owns the tool window's tabs. The tabs only exist for what is actually usable: with no connected
 * repository the window shows a guided welcome page instead of empty lists.
 */
private class ToolWindowController(private val project: Project, private val toolWindow: ToolWindow) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refresher = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var signature: String? = null
    private var lastBranch: String? = null

    init {
        val app = ApplicationManager.getApplication().messageBus.connect(this)
        app.subscribe(ModuleListener.TOPIC, ModuleListener { scheduleRebuild() })
        app.subscribe(AccountListener.TOPIC, AccountListener { scheduleRebuild() })
        val bus = project.messageBus.connect(this)
        bus.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { scheduleRebuild() })
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRebuild() })

        toolWindow.setTitleActions(
            listOf(
                RepositorySelector(),
                simpleAction("Refresh All", AllIcons.Actions.Refresh) { GitOps.refresh(project) },
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                simpleAction("GitOps Studio Settings…", AllIcons.General.Settings) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GitOpsConfigurable::class.java)
                },
                simpleAction("Run Setup Wizard…", AllIcons.Actions.Lightning) { SetupWizard.show(project) },
            ),
        )
        scheduleAutoRefresh()
    }

    /** Git events come in bursts; coalesce them and hop to the UI thread. */
    private fun scheduleRebuild() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuild() }, 300)
    }

    private fun scheduleAutoRefresh() {
        val minutes = GitOpsSettings.get().values.autoRefreshMinutes
        refresher.cancelAllRequests()
        // Re-read the setting on every tick so a change applies without restarting.
        refresher.addRequest({
            if (minutes > 0 && toolWindow.isVisible && !project.isDisposed) GitOps.refresh(project)
            scheduleAutoRefresh()
        }, (if (minutes > 0) minutes else 1) * 60_000)
    }

    fun rebuild() {
        if (project.isDisposed || toolWindow.isDisposed) return
        val context = RepoContext.get(project)
        val current = context.current()?.takeIf { it.account != null }
        val branch = current?.currentBranch
        val next = if (current == null) {
            "welcome|" + context.targets().joinToString { it.ref.fullName } + "|" + context.unknownRemotes().joinToString { it.url }
        } else {
            listOf(current.git.root.path, current.ref.providerId, current.ref.fullName, current.account?.id).joinToString("|")
        }
        if (next == signature) {
            // Same repository: a branch switch only needs fresh data, not new tabs.
            if (branch != lastBranch) {
                lastBranch = branch
                GitOps.refresh(project)
            }
            return
        }
        signature = next
        lastBranch = branch

        val manager = toolWindow.contentManager
        val selectedName = manager.selectedContent?.displayName
        manager.removeAllContents(true)
        val factory = ContentFactory.getInstance()
        fun add(title: String, panel: JComponent) {
            val content = factory.createContent(panel, title, false)
            content.isCloseable = false
            if (panel is Disposable) content.setDisposer(panel)
            manager.addContent(content)
            if (title == selectedName) manager.setSelectedContent(content)
        }
        if (current == null) {
            add("Welcome", WelcomePanel(project))
            return
        }
        val provider = current.provider
        add(provider.terms.pullRequests, PullRequestsPanel(project))
        if (provider.supports(Capability.PIPELINES)) add(provider.terms.pipelines, PipelinesPanel(project))
        if (provider.supports(Capability.ISSUES)) add(provider.terms.issues, IssuesPanel(project))
    }

    override fun dispose() = Unit

    /** Title-bar combo listing the project's cloud repositories; hidden when there is only one. */
    private inner class RepositorySelector : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val context = RepoContext.get(project)
            val targets = context.targets()
            e.presentation.isVisible = targets.size > 1
            e.presentation.text = context.current()?.ref?.name ?: "Repository"
            e.presentation.description = "Repository shown in GitOps Studio"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            RepoContext.get(project).targets().forEach { group.add(Select(it)) }
            return group
        }

        private inner class Select(private val target: RepoTarget) : DumbAwareAction(target.displayName) {
            override fun actionPerformed(e: AnActionEvent) {
                RepoContext.get(project).select(target)
                rebuild()
            }
        }
    }
}
