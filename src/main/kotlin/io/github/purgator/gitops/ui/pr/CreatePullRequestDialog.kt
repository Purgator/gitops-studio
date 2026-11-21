package io.github.purgator.gitops.ui.pr

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
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
import io.github.purgator.gitops.ui.Html
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.htmlPane
import io.github.purgator.gitops.ui.setup.SetupWizard
import io.github.purgator.gitops.ui.showHtml
import java.awt.Dimension
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * The flagship flow: open, check the pre-filled form, press Create. Title and description come from the
 * branch's commits (or the repository's template), the branch is pushed if needed, and reviewers,
 * labels, linked work items and auto-complete are set in the same go.
 */
class CreatePullRequestDialog internal constructor(
    private val project: Project,
    private val repo: RepoTarget,
) : DialogWrapper(project) {

    private val provider = repo.provider
    private val terms = provider.terms
    private val general = GitOpsSettings.get().values
    private val settings = GitOpsSettings.get().provider(provider.id)

    private val source = ComboBox(DefaultComboBoxModel(GitSupport.localBranches(repo.git).toTypedArray()))
    private val target = ComboBox<String>()
    private val titleField = JBTextField()
    private val description = JBTextArea(10, 70).apply { lineWrap = true; wrapStyleWord = true }
    private val draft = JBCheckBox("Create as draft", settings.draftByDefault)
    private val reviewersLabel = JBLabel()
    private val labels = JBTextField()
    private val issues = JBTextField()
    private val autoComplete = JBCheckBox("Auto-complete when all checks and policies pass", settings.autoCompleteByDefault)
    private val deleteBranch = JBCheckBox("Delete source branch after merging", settings.deleteSourceBranch)
    private val strategy = ComboBox(DefaultComboBoxModel(MergeStrategy.entries.toTypedArray()))
    private val status = htmlPane().apply { border = JBUI.Borders.empty() }

    private var reviewers: List<User> = emptyList()
    private var titleEdited = false
    private var descriptionEdited = false
    private var programmatic = false
    private var existing: PullRequest? = null
    private var needsPush = false
    private var commitCount = 0
    private var sequence = 0
    private var busy = false

    init {
        title = "New ${terms.pullRequest} — ${repo.ref.name}"
        setOKButtonText("Create")
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
        init()
        sourceChanged()
        loadDefaults()
    }

    private val sourceBranch: String get() = source.selectedItem as? String ?: ""
    private val targetBranch: String get() = (target.selectedItem as? String)?.trim().orEmpty()

    // ---------------------------------------------------------------- form

    override fun createCenterPanel(): JComponent = panel {
        row("From:") {
            cell(source).align(AlignX.FILL).resizableColumn()
            label("into")
            cell(target).align(AlignX.FILL).resizableColumn()
        }
        row("Title:") { cell(titleField).align(AlignX.FILL).focused() }
        row { scrollCell(description).align(Align.FILL) }.resizableRow()
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
    }.apply { preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520)) }

    override fun getPreferredFocusedComponent(): JComponent = titleField

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
        runAsync(contentPanel, { repo.client().repoInfo(repo.ref).defaultBranch }, isAlive = { !isDisposed }) { defaultBranch ->
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
        runAsync(contentPanel, { repo.client().findOpenPullRequest(repo.ref, branch) }, isAlive = { !isDisposed }) { found ->
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
        renderStatus()
        if (head.isEmpty() || base == null) return
        runAsync(contentPanel, {
            val commits = GitSupport.commitsBetween(project, repo.git, base, head)
            val template = if (general.usePullRequestTemplate) readTemplate() else null
            commits to template
        }, isAlive = { !isDisposed }) { (commits, template) ->
            if (request != sequence) return@runAsync
            commitCount = commits.size
            programmatic = true
            if (!titleEdited) {
                titleField.text = commits.singleOrNull()?.subject ?: Format.titleFromBranch(head)
            }
            if (!descriptionEdited) {
                description.text = when {
                    template != null -> template
                    !general.useCommitsForDescription -> ""
                    commits.size == 1 -> commits[0].body
                    else -> commits.reversed().joinToString("\n") { "- ${it.subject}" }
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

    private fun renderStatus(message: String? = null) {
        val parts = ArrayList<String>()
        if (message != null) parts += Html.esc(message)
        existing?.let {
            parts += Html.colored("A ${terms.pullRequest.lowercase()} already exists for this branch: ", Html.orange) +
                Html.link("#${it.id} ${it.title}", it.webUrl)
        }
        if (message == null && existing == null) {
            parts += Html.muted(
                when (commitCount) {
                    0 -> "No new commit found between the two branches"
                    1 -> "1 commit"
                    else -> "$commitCount commits"
                },
            )
            if (needsPush) {
                parts += if (general.pushBeforeCreate) Html.muted("the branch will be pushed first") else Html.colored("the branch is not pushed", Html.orange)
            }
        }
        status.showHtml(parts.joinToString(" &nbsp;·&nbsp; "))
        isOKActionEnabled = !busy && existing == null
    }

    // ---------------------------------------------------------------- create

    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("Give the ${terms.pullRequest.lowercase()} a title", titleField)
        sourceBranch.isEmpty() -> ValidationInfo("Pick a source branch", source)
        targetBranch.isEmpty() -> ValidationInfo("Pick a target branch", target)
        sourceBranch == targetBranch -> ValidationInfo("Source and target are the same branch", target)
        else -> null
    }

    override fun doOKAction() {
        if (busy) return
        val head = sourceBranch
        val base = targetBranch
        val chosenReviewers = reviewers
        val defaultReviewers = settings.defaultReviewers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val request = NewPullRequest(
            title = titleField.text.trim(),
            description = description.text.trim(),
            sourceBranch = head,
            targetBranch = base,
            draft = provider.supports(Capability.DRAFT_PR) && draft.isSelected,
            labels = splitList(labels.text),
            linkedIssues = splitList(issues.text).map { it.trimStart('#') },
            autoComplete = if (provider.supports(Capability.AUTO_COMPLETE) && autoComplete.isSelected) {
                MergeOptions(
                    strategy = strategy.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE,
                    deleteSourceBranch = deleteBranch.isSelected,
                    autoComplete = true,
                    completeWorkItems = settings.completeWorkItems,
                )
            } else {
                null
            },
        )
        busy = true
        setErrorText(null)
        renderStatus(if (needsPush) "Pushing $head…" else "Creating…")
        runAsync(contentPanel, {
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
            client.createPullRequest(repo.ref, request.copy(reviewers = all))
        }, onError = { error ->
            busy = false
            needsPush = !GitSupport.isPushed(repo.git, repo.remote, head)
            renderStatus()
            setErrorText(error.userMessage())
        }, isAlive = { !isDisposed }) { created ->
            busy = false
            PropertiesComponent.getInstance(project).setValue(targetKey(), base)
            close(OK_EXIT_CODE)
            created(created)
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
        GitOps.refresh(project)
    }

    private fun splitList(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

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

        /** Opens the dialog for [preferred] (or the current repository), guiding the user when something is missing. */
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
                else -> CreatePullRequestDialog(project, repo).show()
            }
        }
    }
}
