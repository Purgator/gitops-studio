package io.github.purgator.gitops.ui.pipelines

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.model.PipelineDefinition
import io.github.purgator.gitops.core.model.PipelineJob
import io.github.purgator.gitops.core.model.PipelineRun
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.runWithProgress
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.git.GitSupport
import io.github.purgator.gitops.ui.Format
import io.github.purgator.gitops.ui.GitOpsIcons
import io.github.purgator.gitops.ui.GitOpsListener
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.simpleAction
import io.github.purgator.gitops.ui.toolwindow.TargetPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** "Pipelines" tab: recent runs on the left, the jobs of the selected run (and their logs) on the right. */
class PipelinesPanel(project: Project) : TargetPanel(project) {
    private val runs = CollectionListModel<PipelineRun>()
    private val runList = JBList(runs)
    private val jobs = CollectionListModel<PipelineJob>()
    private val jobList = JBList(jobs)
    private val scope = ComboBox(arrayOf(CURRENT_BRANCH, ALL_BRANCHES))
    private val jobsTitle = JBLabel("Jobs").apply { border = JBUI.Borders.empty(6, 8) }
    private var runRequest = 0
    private var jobRequest = 0

    init {
        runList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        runList.cellRenderer = RunRenderer()
        runList.addListSelectionListener { if (!it.valueIsAdjusting) loadJobs() }
        jobList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        jobList.cellRenderer = JobRenderer()
        jobList.emptyText.text = "Select a run to see its jobs"
        scope.addActionListener { reload() }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                runList.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
                return true
            }
        }.installOn(runList)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openLog()
                return true
            }
        }.installOn(jobList)

        val selected = { runList.selectedValue }
        val actions = DefaultActionGroup(
            simpleAction("Run Pipeline…", AllIcons.Actions.Execute, enabled = { can(Capability.RUN_PIPELINE) }) { runPipeline() },
            simpleAction("Refresh", AllIcons.Actions.Refresh) { reload() },
            Separator.getInstance(),
            simpleAction("Rerun", AllIcons.Actions.Restart, enabled = { selected() != null && can(Capability.RERUN_PIPELINE) }) {
                selected()?.let { run -> mutate("Run restarted") { repo, client -> client.rerunPipeline(repo.ref, run) } }
            },
            simpleAction("Cancel Run", AllIcons.Actions.Suspend, enabled = { selected()?.state?.finished == false && can(Capability.CANCEL_PIPELINE) }) {
                selected()?.let { run ->
                    if (Messages.showYesNoDialog(project, "Cancel ${run.name}?", "Cancel Run", Messages.getQuestionIcon()) == Messages.YES) {
                        mutate("Cancellation requested") { repo, client -> client.cancelPipeline(repo.ref, run) }
                    }
                }
            },
            simpleAction("Open in Browser", AllIcons.General.Web, enabled = { selected() != null }) { selected()?.let { BrowserUtil.browse(it.webUrl) } },
            simpleAction("Copy Link", AllIcons.Actions.Copy, enabled = { selected() != null }) { selected()?.let { copyToClipboard(it.webUrl) } },
        )
        PopupHandler.installPopupMenu(runList, actions, "GitOpsStudio.Pipelines.Popup")
        val jobActions = DefaultActionGroup(
            simpleAction("Open Log", AllIcons.Debugger.Console, enabled = { jobList.selectedValue != null && can(Capability.JOB_LOGS) }) { openLog() },
            simpleAction("Open in Browser", AllIcons.General.Web, enabled = { jobList.selectedValue?.webUrl != null }) {
                jobList.selectedValue?.webUrl?.let { BrowserUtil.browse(it) }
            },
        )
        PopupHandler.installPopupMenu(jobList, jobActions, "GitOpsStudio.Jobs.Popup")

        val north = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(toolbar("GitOpsStudio.Pipelines", actions).component, BorderLayout.WEST)
            add(scope, BorderLayout.EAST)
        }
        val left = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(runList, true), BorderLayout.CENTER)
        }
        val jobsHeader = JPanel(BorderLayout()).apply {
            add(jobsTitle, BorderLayout.CENTER)
            add(toolbar("GitOpsStudio.Jobs", jobActions).component, BorderLayout.EAST)
        }
        val right = JPanel(BorderLayout()).apply {
            add(jobsHeader, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(jobList, true), BorderLayout.CENTER)
        }
        add(OnePixelSplitter(false, "GitOpsStudio.Pipelines.Splitter", 0.55f).apply {
            firstComponent = left
            secondComponent = right
        }, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(GitOpsListener.TOPIC, object : GitOpsListener {
            override fun refreshRequested() = reload(silent = true)
        })
        reload()
    }

    private fun can(capability: Capability) = target?.provider?.supports(capability) == true

    override fun reload(silent: Boolean) {
        val repo = target
        val request = ++runRequest
        if (repo == null) {
            runs.removeAll()
            return
        }
        val branch = if (scope.selectedItem == CURRENT_BRANCH) repo.currentBranch else null
        val keep = runList.selectedValue?.id
        runList.setPaintBusy(true)
        if (!silent) runList.emptyText.text = "Loading…"
        remote(
            work = { r, client -> client.listPipelineRuns(r.ref, branch) },
            onError = { error ->
                if (request != runRequest) return@remote
                runList.setPaintBusy(false)
                if (!silent || runs.isEmpty) {
                    runs.removeAll()
                    runList.emptyText.text = error.userMessage()
                }
            },
        ) { result ->
            if (request != runRequest) return@remote
            runList.setPaintBusy(false)
            runList.emptyText.text = if (branch != null) "No run for branch $branch" else "No run yet"
            runs.replaceAll(result)
            result.firstOrNull { it.id == keep }?.let { runList.setSelectedValue(it, false) }
        }
    }

    private fun loadJobs() {
        val run = runList.selectedValue
        val request = ++jobRequest
        jobs.removeAll()
        if (run == null) {
            jobsTitle.text = "Jobs"
            jobList.emptyText.text = "Select a run to see its jobs"
            return
        }
        jobsTitle.text = "Jobs of ${run.name}"
        jobList.emptyText.text = "Loading…"
        jobList.setPaintBusy(true)
        remote(
            work = { repo, client -> client.listJobs(repo.ref, run) },
            onError = { error ->
                if (request == jobRequest) { jobList.setPaintBusy(false); jobList.emptyText.text = error.userMessage() }
            },
        ) { result ->
            if (request != jobRequest) return@remote
            jobList.setPaintBusy(false)
            jobList.emptyText.text = "No job"
            jobs.replaceAll(result)
        }
    }

    /** Downloads the job log and opens it as a read-only editor tab, where search and folding work. */
    private fun openLog() {
        val run = runList.selectedValue ?: return
        val job = jobList.selectedValue ?: return
        val repo = target ?: return
        if (!can(Capability.JOB_LOGS)) {
            job.webUrl?.let { BrowserUtil.browse(it) }
            return
        }
        project.runWithProgress("Downloading log of ${job.name}", { _ -> repo.client().jobLog(repo.ref, run, job) }) { text ->
            val name = "${run.name} - ${job.name}.log".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = LightVirtualFile(name, PlainTextFileType.INSTANCE, text.replace("\r\n", "\n").ifBlank { "The log is empty." })
            file.isWritable = false
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    private fun runPipeline() {
        val repo = target ?: return
        val dialog = RunPipelineDialog(project, repo)
        if (!dialog.showAndGet()) return
        val definition = dialog.definition ?: return
        val branch = dialog.branch
        mutate("${definition.name} started on $branch") { r, client -> client.runPipeline(r.ref, definition, branch) }
    }

    private class RunRenderer : ColoredListCellRenderer<PipelineRun>() {
        override fun customizeCellRenderer(list: JList<out PipelineRun>, value: PipelineRun?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = GitOpsIcons.of(value.state)
            ipad = JBUI.insets(3, 6)
            append(value.name)
            if (value.title.isNotBlank()) append("  ${value.title}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val details = listOf(
                value.state.label,
                value.branch,
                value.triggeredBy,
                Format.date(value.startedAt),
                if (value.state.finished) Format.duration(value.startedAt, value.finishedAt) else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            append("   $details", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private class JobRenderer : ColoredListCellRenderer<PipelineJob>() {
        override fun customizeCellRenderer(list: JList<out PipelineJob>, value: PipelineJob?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = GitOpsIcons.of(value.state)
            ipad = JBUI.insets(3, 6)
            if (value.stage.isNotBlank()) append("${value.stage} / ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.name)
            val details = listOf(value.state.label, Format.duration(value.startedAt, value.finishedAt)).filter { it.isNotBlank() }.joinToString(" · ")
            append("   $details", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    companion object {
        private const val CURRENT_BRANCH = "Current branch"
        private const val ALL_BRANCHES = "All branches"
    }
}

/** Picks the pipeline (workflow) to start and the branch to run it on. */
class RunPipelineDialog(project: Project, private val repo: RepoTarget) : DialogWrapper(project) {
    private val definitions = ComboBox<PipelineDefinition>()
    private val branches = ComboBox(DefaultComboBoxModel(branchChoices().toTypedArray()))
    private val hint = JBLabel("Loading pipelines…")

    val definition: PipelineDefinition? get() = definitions.selectedItem as? PipelineDefinition
    val branch: String get() = (branches.selectedItem as? String).orEmpty()

    init {
        title = "Run ${repo.provider.terms.pipeline}"
        setOKButtonText("Run")
        repo.currentBranch?.let { branches.selectedItem = it }
        isOKActionEnabled = false
        init()
        runAsync(contentPanel, { repo.client().listPipelineDefinitions(repo.ref) }, onError = { hint.text = it.userMessage() }, isAlive = { !isDisposed }) { found ->
            definitions.model = DefaultComboBoxModel(found.toTypedArray())
            hint.text = if (found.isEmpty()) "No pipeline can be started manually here." else " "
            isOKActionEnabled = found.isNotEmpty()
        }
    }

    private fun branchChoices(): List<String> =
        (GitSupport.remoteBranches(repo.git, repo.remote) + listOfNotNull(repo.currentBranch)).distinct().sortedBy { it.lowercase() }

    override fun createCenterPanel(): JComponent = panel {
        row("Pipeline:") { cell(definitions).align(AlignX.FILL) }
        row("Branch:") { cell(branches).align(AlignX.FILL) }
        row { cell(hint) }
    }.apply { preferredSize = Dimension(JBUI.scale(420), preferredSize.height) }
}
