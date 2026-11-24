package io.github.purgator.gitops.ui.pr

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import git4idea.commands.GitCommand
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.git.GitSupport
import io.github.purgator.gitops.ui.Format
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.GitOpsListener
import io.github.purgator.gitops.ui.Html
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.htmlPane
import io.github.purgator.gitops.ui.settings.GitOpsConfigurable
import io.github.purgator.gitops.ui.setup.SetupWizard
import io.github.purgator.gitops.ui.showHtml
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The flagship flow, as a form inside the tool window: check the pre-filled form, press Create. Title and
 * description come from the branch's commits (or the repository's template), the branch is pushed if
 * needed, and reviewers, labels, linked work items and auto-complete are set in the same go.
 *
 * Being a panel and not a dialog, it can be hidden half-filled and found again as it was left.
 * [onDone] is called with the created pull request, or with null when the draft is discarded.
 */
class CreatePullRequestPanel(
    private val project: Project,
    val repo: RepoTarget,
    private val onHide: () -> Unit,
    private val onDone: (PullRequest?) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val provider = repo.provider
    private val terms = provider.terms
    private val general = GitOpsSettings.get().values
    private val settings = GitOpsSettings.get().provider(provider.id)

    private val source = ComboBox(DefaultComboBoxModel(GitSupport.localBranches(repo.git).toTypedArray()))
    private val target = ComboBox<String>()
    private val titleField = JBTextField()
    private val description = JBTextArea(10, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val draft = JBCheckBox("Create as draft", settings.draftByDefault)
    private val reviewersLabel = JBLabel()
    private val labels = JBTextField()
    private val issues = JBTextField()
    private val autoComplete = JBCheckBox("Auto-complete when all checks and policies pass", settings.autoCompleteByDefault)
    private val deleteBranch = JBCheckBox("Delete source branch after merging", settings.deleteSourceBranch)
    private val strategy = ComboBox(DefaultComboBoxModel(MergeStrategy.entries.toTypedArray()))
    private val status = htmlPane().apply { border = JBUI.Borders.empty() }
    private val insertCommits = JButton("Insert Commits").apply {
        toolTipText = "Add the subject of each commit of the branch to the description"
        addActionListener { insertCommits() }
    }
    private val generate = JButton("Generate").apply {
        toolTipText = "Write the description with the generator command set in the settings"
        addActionListener { generate() }
    }
    private val aiMode = ComboBox(AiMode.entries.toTypedArray()).apply { selectedItem = AiMode.of(general.aiMode) }
    private val createButton = JButton("Create").apply { addActionListener { create() } }

    private var reviewers: List<User> = emptyList()
    private var commits: List<GitSupport.Commit> = emptyList()
    private var titleEdited = false
    private var descriptionEdited = false
    private var programmatic = false
    private var existing: PullRequest? = null
    private var needsPush = false
    private var sequence = 0
    private var busy = false

    @Volatile
    private var disposed = false

    /** True while the user typed nothing: such a draft can be thrown away without asking. */
    val isPristine: Boolean get() = !titleEdited && !descriptionEdited && !busy

    init {
        repo.currentBranch?.let { source.selectedItem = it }
        strategy.selectedItem = settings.strategy
        labels.emptyText.text = "Comma separated"
        issues.emptyText.text = "Ids, comma separated"
        titleField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!programmatic) titleEdited = true }
        })
        description.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!programmatic) descriptionEdited = true }
        })
        fillTargets(null)
        source.addActionListener { fillTargets(target.selectedItem as? String); sourceChanged() }
        target.addActionListener { if (!programmatic) refreshCommits() }
        autoComplete.addActionListener { syncAutoComplete() }
        syncAutoComplete()
        renderReviewers()

        val header = JBLabel("New ${terms.pullRequest} — ${repo.ref.name}").apply {
            font = JBUI.Fonts.label().asBold()
            border = JBUI.Borders.empty(8, 12, 4, 12)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
            add(createButton)
            add(JButton("Hide").apply {
                toolTipText = "Keep this draft and go back to the list; the + button brings it back"
                addActionListener { onHide() }
            })
            add(JButton("Discard").apply { addActionListener { onDone(null) } })
        }
        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(form(), true), BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)

        sourceChanged()
        loadDefaults()
    }

    private val sourceBranch: String get() = source.selectedItem as? String ?: ""
    private val targetBranch: String get() = (target.selectedItem as? String)?.trim().orEmpty()

    // ---------------------------------------------------------------- form

    private fun form() = panel {
        row("From:") {
            cell(source).align(AlignX.FILL).resizableColumn()
            label("into")
            cell(target).align(AlignX.FILL).resizableColumn()
        }
        row("Title:") { cell(titleField).align(AlignX.FILL) }
        row { scrollCell(description).align(Align.FILL) }.resizableRow()
        row {
            cell(insertCommits)
            cell(generate)
            cell(aiMode)
        }
        if (provider.supports(Capability.REVIEWERS)) {
            row("Reviewers:") {
                cell(reviewersLabel).resizableColumn()
                cell(JButton("Choose…").apply { addActionListener { chooseReviewers() } })
            }
        }
        if (provider.supports(Capability.LINK_ISSUES)) {
            row("Linked ${terms.issues.lowercase()}:") { cell(issues).align(AlignX.FILL) }
        }
        if (provider.supports(Capability.LABELS)) {
            row("Labels:") { cell(labels).align(AlignX.FILL) }
        }
        if (provider.supports(Capability.DRAFT_PR)) row { cell(draft) }
        if (provider.supports(Capability.AUTO_COMPLETE)) {
            row { cell(autoComplete) }
            indent {
                row("Strategy:") {
                    cell(strategy)
                    cell(deleteBranch)
                }
            }
        }
        row { cell(status).align(AlignX.FILL) }
    }.apply { border = JBUI.Borders.empty(4, 12) }

    fun focusTitle() {
        titleField.requestFocusInWindow()
    }

    private fun syncAutoComplete() {
        strategy.isEnabled = autoComplete.isSelected
        deleteBranch.isEnabled = autoComplete.isSelected
    }

    private fun renderReviewers() {
        val defaults = settings.defaultReviewers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val names = reviewers.map { it.label } + defaults.map { "$it (default)" }
        reviewersLabel.text = if (names.isEmpty()) "None" else names.joinToString(", ")
    }

    private fun chooseReviewers() {
        val dialog = ReviewerChooserDialog(project, repo, reviewers)
        if (dialog.showAndGet()) {
            reviewers = dialog.reviewers()
            renderReviewers()
        }
    }

    /** Target candidates: every branch of the remote except the source. */
    private fun fillTargets(preferred: String?) {
        val branches = GitSupport.remoteBranches(repo.git, repo.remote).filter { it != sourceBranch }
        val remembered = PropertiesComponent.getInstance(project).getValue(targetKey())
        val choice = listOfNotNull(preferred, remembered, "main", "master", "develop", "dev").firstOrNull { it in branches }
            ?: branches.firstOrNull()
        programmatic = true
        target.model = DefaultComboBoxModel(branches.toTypedArray())
        target.isEditable = branches.isEmpty()
        if (choice != null) target.selectedItem = choice
        programmatic = false
    }

    private fun targetKey() = "gitops.studio.target." + repo.git.root.path

    /** The server knows the real default branch; use it unless the user already has a remembered choice. */
    private fun loadDefaults() {
        runAsync(this, { repo.client().repoInfo(repo.ref).defaultBranch }, isAlive = { !disposed }) { defaultBranch ->
            val remembered = PropertiesComponent.getInstance(project).getValue(targetKey())
            val model = target.model
            val known = (0 until model.size).any { model.getElementAt(it) == defaultBranch }
            if (remembered == null && known && defaultBranch != sourceBranch && targetBranch != defaultBranch) {
                target.selectedItem = defaultBranch
            }
        }
    }

    private fun sourceChanged() {
        if (provider.supports(Capability.LINK_ISSUES) && settings.linkWorkItemsFromBranch) {
            issues.text = Format.issueIdsFromBranch(sourceBranch).joinToString(", ")
        }
        needsPush = !GitSupport.isPushed(repo.git, repo.remote, sourceBranch)
        existing = null
        refreshCommits()
        val branch = sourceBranch
        runAsync(this, { repo.client().findOpenPullRequest(repo.ref, branch) }, isAlive = { !disposed }) { found ->
            if (branch == sourceBranch) {
                existing = found
                renderStatus()
            }
        }
    }

    /** Reads the commits the request would contain and derives title and description from them. */
    private fun refreshCommits() {
        val request = ++sequence
        val head = sourceBranch
        val base = GitSupport.refFor(repo.git, repo.remote, targetBranch)
        commits = emptyList()
        renderStatus()
        if (head.isEmpty() || base == null) return
        runAsync(this, {
            val found = GitSupport.commitsBetween(project, repo.git, base, head)
            val template = if (general.usePullRequestTemplate) readTemplate() else null
            found to template
        }, isAlive = { !disposed }) { (found, template) ->
            if (request != sequence) return@runAsync
            commits = found
            programmatic = true
            if (!titleEdited) {
                titleField.text = found.singleOrNull()?.subject ?: Format.titleFromBranch(head)
            }
            if (!descriptionEdited) {
                description.text = when {
                    template != null -> template
                    !general.useCommitsForDescription -> ""
                    found.size == 1 -> found[0].body
                    else -> PullRequestText.commitList(found)
                }
                description.caretPosition = 0
            }
            programmatic = false
            renderStatus()
        }
    }

    private fun readTemplate(): String? {
        val root = File(repo.git.root.path)
        return TEMPLATES.asSequence().map { File(root, it) }.firstOrNull { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    }

    private fun renderStatus(message: String? = null, error: Boolean = false) {
        val parts = ArrayList<String>()
        if (message != null) parts += if (error) Html.colored(Html.esc(message), Html.red) else Html.esc(message)
        existing?.let {
            parts += Html.colored("A ${terms.pullRequest.lowercase()} already exists for this branch: ", Html.orange) +
                Html.link("#${it.id} ${it.title}", it.webUrl)
        }
        if (message == null && existing == null) {
            parts += Html.muted(
                when (commits.size) {
                    0 -> "No new commit found between the two branches"
                    1 -> "1 commit"
                    else -> "${commits.size} commits"
                },
            )
            if (needsPush) {
                parts += if (general.pushBeforeCreate) Html.muted("the branch will be pushed first") else Html.colored("the branch is not pushed", Html.orange)
            }
        }
        status.showHtml(parts.joinToString(" &nbsp;·&nbsp; "))
        createButton.isEnabled = !busy && existing == null
        generate.isEnabled = !busy
        insertCommits.isEnabled = commits.isNotEmpty()
    }

    // ---------------------------------------------------------------- description helpers

    private fun setDescription(text: String) {
        description.text = text
        description.caretPosition = 0
    }

    private fun insertCommits() {
        setDescription(PullRequestText.append(description.text, PullRequestText.commitList(commits)))
    }

    /** Sends the prompt of the settings to the generator command and puts its answer in the description. */
    private fun generate() {
        val command = general.aiCommand.trim()
        if (command.isEmpty()) {
            Notify.warn(
                project,
                "No description generator",
                "Set the command to run (for example <code>claude -p</code>) in Settings | Tools | GitOps Studio.",
                "Open Settings…" to { ShowSettingsUtil.getInstance().showSettingsDialog(project, GitOpsConfigurable::class.java) },
            )
            return
        }
        val mode = aiMode.selectedItem as? AiMode ?: AiMode.REPLACE
        val head = sourceBranch
        val base = GitSupport.refFor(repo.git, repo.remote, targetBranch)
        val before = description.text
        val values = mapOf(
            "title" to titleField.text.trim(),
            "source" to head,
            "target" to targetBranch,
            "description" to before,
            "commits" to commits.reversed().joinToString("\n") { commit ->
                "- ${commit.subject}" + commit.body.lines().filter { it.isNotBlank() }.joinToString("") { "\n  $it" }
            },
        )
        busy = true
        renderStatus("Generating the description…")
        runAsync(this, {
            val files = if (base == null) "" else runCatching {
                GitSupport.run(project, repo.git, GitCommand.DIFF, "--stat", "$base...$head").joinToString("\n")
            }.getOrDefault("")
            val prompt = PullRequestText.prompt(general.aiPrompt, mode, values + ("files" to files))
            DescriptionGenerator.run(command, prompt, File(repo.git.root.path))
        }, onError = { error ->
            busy = false
            renderStatus(error.userMessage(), error = true)
        }, isAlive = { !disposed }) { result ->
            busy = false
            // Whatever was typed while the generator worked is kept in APPEND mode and replaced otherwise, as asked.
            setDescription(PullRequestText.applyResult(mode, if (mode == AiMode.APPEND) description.text else before, result))
            renderStatus()
        }
    }

    // ---------------------------------------------------------------- create

    private fun validationError(): String? = when {
        titleField.text.isBlank() -> "Give the ${terms.pullRequest.lowercase()} a title"
        sourceBranch.isEmpty() -> "Pick a source branch"
        targetBranch.isEmpty() -> "Pick a target branch"
        sourceBranch == targetBranch -> "Source and target are the same branch"
        else -> null
    }

    private fun create() {
        if (busy) return
        validationError()?.let { return renderStatus(it, error = true) }
        val head = sourceBranch
        val base = targetBranch
        val chosenReviewers = reviewers
        val defaultReviewers = settings.defaultReviewers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val auto = if (provider.supports(Capability.AUTO_COMPLETE) && autoComplete.isSelected) {
            MergeOptions(
                strategy = strategy.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE,
                deleteSourceBranch = deleteBranch.isSelected,
                autoComplete = true,
                completeWorkItems = settings.completeWorkItems,
            )
        } else {
            null
        }
        val request = NewPullRequest(
            title = titleField.text.trim(),
            description = description.text.trim(),
            sourceBranch = head,
            targetBranch = base,
            draft = provider.supports(Capability.DRAFT_PR) && draft.isSelected,
            labels = splitList(labels.text),
            linkedIssues = splitList(issues.text).map { it.trimStart('#') },
            autoComplete = auto,
        )
        busy = true
        renderStatus(if (needsPush) "Pushing $head…" else "Creating…")
        runAsync(this, {
            if (!GitSupport.isPushed(repo.git, repo.remote, head)) {
                if (!general.pushBeforeCreate) throw IllegalStateException("Branch $head is not pushed. Push it, or enable pushing in the settings.")
                GitSupport.push(repo.git, repo.remote, head)
            }
            val client = repo.client()
            val me = runCatching { client.currentUser().id }.getOrNull()
            // Default reviewers are stored as names; resolve each to the best match. Unknown names are skipped.
            val resolved = defaultReviewers.mapNotNull { name ->
                runCatching { client.searchReviewers(repo.ref, name).firstOrNull() }.getOrNull()
            }
            val all = (chosenReviewers + resolved).distinctBy { it.id }.filter { it.id != me }
            val created = client.createPullRequest(repo.ref, request.copy(reviewers = all))
            // The merge message of the settings mentions the request's number, known only now.
            val message = PullRequestText.mergeMessage(general.mergeMessageTemplate, created)
            if (auto != null && message != null) runCatching { client.mergePullRequest(repo.ref, created, auto.copy(message = message)) }
            created
        }, onError = { error ->
            busy = false
            needsPush = !GitSupport.isPushed(repo.git, repo.remote, head)
            renderStatus(error.userMessage(), error = true)
        }, isAlive = { !disposed }) { created ->
            busy = false
            PropertiesComponent.getInstance(project).setValue(targetKey(), base)
            created(created)
            onDone(created)
        }
    }

    private fun created(pr: PullRequest) {
        if (general.copyLinkAfterCreate) copyToClipboard(pr.webUrl)
        if (general.openInBrowserAfterCreate) BrowserUtil.browse(pr.webUrl)
        Notify.info(
            project,
            "${terms.pullRequest} #${pr.id} created",
            Html.esc(pr.title) + if (general.copyLinkAfterCreate) "<br/>Link copied to the clipboard." else "",
            "Open in Browser" to { BrowserUtil.browse(pr.webUrl) },
            "Copy Link" to { copyToClipboard(pr.webUrl) },
            "Show" to { GitOps.showPullRequest(project, pr.id) },
        )
    }

    private fun splitList(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private val TEMPLATES = listOf(
            ".azuredevops/pull_request_template.md",
            ".github/pull_request_template.md",
            ".github/PULL_REQUEST_TEMPLATE.md",
            ".gitlab/merge_request_templates/Default.md",
            ".gitea/pull_request_template.md",
            ".forgejo/pull_request_template.md",
            "docs/pull_request_template.md",
            "pull_request_template.md",
            "PULL_REQUEST_TEMPLATE.md",
        )

        /**
         * Shows the creation form in the tool window for [preferred] (or the current repository),
         * guiding the user when something is missing.
         */
        fun open(project: Project, preferred: RepoTarget? = null) {
            val context = RepoContext.get(project)
            val repo = preferred ?: context.current()
            when {
                repo == null -> {
                    val reason = when {
                        !context.hasGitRepository() -> "This project has no git repository."
                        else -> "No enabled module recognises this project's remote."
                    }
                    Notify.warn(project, "Cannot create a pull request", reason, "Set Up GitOps Studio…" to { SetupWizard.show(project) })
                }
                repo.account == null -> Notify.warn(
                    project,
                    "No ${repo.provider.displayName} account",
                    "Connect an account for ${repo.ref.serverUrl.substringAfter("://")} to create pull requests.",
                    "Connect…" to { SetupWizard.show(project) },
                )
                repo.currentBranch == null && repo.git.branches.localBranches.isEmpty() ->
                    Notify.warn(project, "Cannot create a pull request", "The repository has no local branch.")
                else -> {
                    context.select(repo)
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(GitOps.TOOL_WINDOW_ID) ?: return
                    toolWindow.activate {
                        val events = project.messageBus.syncPublisher(GitOpsListener.TOPIC)
                        // The tabs may still show another repository of the project.
                        events.targetChanged()
                        toolWindow.contentManager.contents.firstOrNull()?.let { toolWindow.contentManager.setSelectedContent(it) }
                        events.createPullRequest()
                    }
                }
            }
        }
    }
}
