package io.github.purgator.gitops.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import git4idea.commands.GitCommand
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.modules.ModuleListener
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.runWithProgress
import java.awt.Component
import java.awt.event.MouseEvent

/** How far the current branch is from the branch it tracks. */
data class AheadBehind(val branch: String, val upstream: String, val ahead: Int, val behind: Int) {
    /** `↑2 ↓1`, empty when there is nothing to push or pull. */
    val label: String
        get() = listOfNotNull("↑$ahead".takeIf { ahead > 0 }, "↓$behind".takeIf { behind > 0 }).joinToString(" ")

    val description: String
        get() {
            fun commits(n: Int) = "$n commit" + if (n == 1) "" else "s"
            val parts = listOfNotNull("${commits(ahead)} to push".takeIf { ahead > 0 }, "${commits(behind)} to pull".takeIf { behind > 0 })
            return "$branch: ${parts.joinToString(", ").ifEmpty { "up to date" }} ($upstream as of the last fetch). Click to fetch."
        }

    companion object {
        /** Reads `git rev-list --left-right --count branch...upstream`: `2<tab>1`. */
        fun parse(branch: String, upstream: String, output: String): AheadBehind? {
            val counts = output.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            return if (counts.size == 2) AheadBehind(branch, upstream, counts[0], counts[1]) else null
        }

        /** Null when [repo] is on no branch or its branch tracks nothing. Blocking. */
        fun of(project: Project, repo: GitRepository): AheadBehind? {
            val branch = repo.currentBranch ?: return null
            val upstream = branch.findTrackedBranch(repo) ?: return null
            val output = GitSupport.run(project, repo, GitCommand.REV_LIST, "--left-right", "--count", "${branch.name}...${upstream.name}")
            return parse(branch.name, upstream.name, output.joinToString(" "))
        }
    }
}

class AheadBehindWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AheadBehindWidget.ID
    override fun getDisplayName(): String = "GitOps Studio Commits to Push and Pull"
    override fun isAvailable(project: Project): Boolean = GitOpsSettings.get().values.showAheadBehind
    override fun createWidget(project: Project): StatusBarWidget = AheadBehindWidget(project)
    override fun isEnabledByDefault(): Boolean = true
}

/** Status bar entry: `↑2 ↓1` when the current branch has commits to push or to pull. Click to fetch. */
class AheadBehindWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var statusBar: StatusBar? = null
    private var status: AheadBehind? = null
    private var repository: GitRepository? = null
    private var sequence = 0

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ModuleListener.TOPIC, ModuleListener { schedule() })
        val bus = project.messageBus.connect(this)
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { schedule() })
        bus.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { schedule() })
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
        })
        schedule()
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = status?.label.orEmpty()
    override fun getTooltipText(): String = status?.description.orEmpty()
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    private fun schedule() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ update() }, 500)
    }

    private fun show(value: AheadBehind?) {
        status = value
        statusBar?.updateWidget(ID)
    }

    /** The repository of the file being edited, else the project's first one. */
    private fun currentRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        return FileEditorManager.getInstance(project).selectedFiles.firstNotNullOfOrNull { manager.getRepositoryForFileQuick(it) }
            ?: manager.repositories.firstOrNull()
    }

    private fun update() {
        if (project.isDisposed) return
        val repo = currentRepository().takeIf { ModuleRegistry.get().isEnabled(ModuleRegistry.GIT) }
        repository = repo
        val request = ++sequence
        if (repo == null) return show(null)
        runAsync(null, { AheadBehind.of(project, repo) }, onError = {
            if (request == sequence) show(null)
        }, isAlive = { !project.isDisposed }) { value ->
            if (request == sequence) show(value)
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val repo = repository ?: return@Consumer
        project.runWithProgress("Fetching ${repo.root.name}", { _ ->
            GitFetchSupport.fetchSupport(project).fetchAllRemotes(listOf(repo)).showNotificationIfFailed()
        })
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "GitOpsStudio.AheadBehind"
    }
}

/** Fetches every remote of the project at the interval chosen in the settings. Off by default. */
@Service(Service.Level.PROJECT)
class AutoFetchService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ModuleListener.TOPIC, ModuleListener { reschedule() })
    }

    /** Call when the project opens and whenever the interval may have changed. */
    fun reschedule() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        val minutes = if (ModuleRegistry.get().isEnabled(ModuleRegistry.GIT)) GitOpsSettings.get().values.autoFetchMinutes else 0
        if (minutes > 0) alarm.addRequest({ fetch() }, minutes * 60_000L)
    }

    private fun fetch() {
        try {
            if (project.isDisposed) return
            val repositories = GitRepositoryManager.getInstance(project).repositories.filter { it.remotes.isNotEmpty() }
            val support = GitFetchSupport.fetchSupport(project)
            // Quiet on purpose: a failed background fetch (offline, VPN down) is not worth a notification.
            if (repositories.isNotEmpty() && !support.isFetchRunning) support.fetchAllRemotes(repositories)
        } finally {
            reschedule()
        }
    }

    override fun dispose() = Unit

    companion object {
        /** Applies a new interval to every open project. */
        fun rescheduleAll() {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.forEach { it.service<AutoFetchService>().reschedule() }
        }
    }
}

class GitModuleStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<AutoFetchService>().reschedule()
    }
}
