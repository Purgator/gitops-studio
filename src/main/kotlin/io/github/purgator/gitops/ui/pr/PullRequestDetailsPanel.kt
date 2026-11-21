package io.github.purgator.gitops.ui.pr

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.branch.GitBrancher
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.model.Check
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.CommentThread
import io.github.purgator.gitops.core.model.MergeStatus
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.PullRequestUpdate
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.runWithProgress
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.git.GitSupport
import io.github.purgator.gitops.ui.Format
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.Html
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.htmlPane
import io.github.purgator.gitops.ui.showHtml
import io.github.purgator.gitops.ui.simpleAction
import io.github.purgator.gitops.ui.toolwindow.TargetPanel
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Everything about one pull request: overview, conversation, changed files and the actions on it. */
class PullRequestDetailsPanel(project: Project) : TargetPanel(project) {
    private var pr: PullRequest? = null
    private var checks: List<Check> = emptyList()
    private var threads: List<CommentThread> = emptyList()
    private var detailsLoaded = false
    private var sequence = 0
    private var changesShownFor: String? = null

    private val title = JBLabel().apply {
        font = JBUI.Fonts.label().asBold().biggerOn(2f)
        border = JBUI.Borders.empty(8, 12, 2, 12)
    }
    private val overview = htmlPane()
    private val conversation = htmlPane { link -> if (link.startsWith("reply:")) comment(link.removePrefix("reply:")) }
    private val changes = JPanel(BorderLayout())
    private val changesHint = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private val tabs = JBTabbedPane()
    private val empty = JBLabel("Select a pull request to see its details", SwingConstants.CENTER).apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val content = JPanel(BorderLayout())

    init {
        tabs.addTab("Overview", ScrollPaneFactory.createScrollPane(overview, true))
        tabs.addTab("Conversation", ScrollPaneFactory.createScrollPane(conversation, true))
        tabs.addTab("Changes", changes)
        tabs.addChangeListener { if (tabs.selectedIndex == 2) loadChanges() }

        val header = JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(toolbar("GitOpsStudio.PullRequest", actions(), owner = this@PullRequestDetailsPanel).component, BorderLayout.CENTER)
        }
        content.add(header, BorderLayout.NORTH)
        content.add(tabs, BorderLayout.CENTER)
        add(empty, BorderLayout.CENTER)
    }

    fun show(pullRequest: PullRequest?) {
        if (pullRequest?.id == pr?.id && pullRequest != null) {
            // Same request re-selected after a list refresh: keep what is on screen, refresh quietly.
            reload(silent = true)
            return
        }
        pr = pullRequest
        checks = emptyList()
        threads = emptyList()
        detailsLoaded = false
        changesShownFor = null
        showChangesHint("")
        render()
        if (pullRequest != null) reload()
    }

    override fun reload(silent: Boolean) {
        val current = pr ?: return
        val request = ++sequence
        remote(
            work = { repo, client ->
                val full = client.getPullRequest(repo.ref, current.id)
                val checks = runCatching { client.listChecks(repo.ref, full) }.getOrDefault(emptyList())
                val threads = runCatching { client.listComments(repo.ref, full) }.getOrDefault(emptyList())
                Triple(full, checks, threads)
            },
            onError = { error ->
                if (request == sequence && !silent) overview.showHtml(Html.colored(Html.esc(error.userMessage()), Html.red))
            },
        ) { (full, loadedChecks, loadedThreads) ->
            if (request != sequence) return@remote
            pr = full
            checks = loadedChecks
            threads = loadedThreads
            detailsLoaded = true
            render()
            if (tabs.selectedIndex == 2) loadChanges()
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val current = pr
        removeAll()
        if (current == null) {
            add(empty, BorderLayout.CENTER)
        } else {
            add(content, BorderLayout.CENTER)
            title.text = "<html>${Html.esc(current.title)} ${Html.muted("#" + current.id)}</html>"
            overview.showHtml(overviewHtml(current))
            conversation.showHtml(conversationHtml())
            tabs.setTitleAt(1, if (threads.isEmpty()) "Conversation" else "Conversation (${threads.size})")
        }
        revalidate()
        repaint()
    }

    private fun overviewHtml(pr: PullRequest): String = buildString {
        val terms = target?.provider?.terms
        append("<p>")
        append("<b>").append(Html.colored(pr.state.label, Html.color(pr.state))).append("</b> &nbsp; ")
        append(Html.esc(pr.author.label)).append(" wants to merge <code>").append(Html.esc(pr.sourceBranch))
        append("</code> into <code>").append(Html.esc(pr.targetBranch)).append("</code>")
        Format.date(pr.createdAt).takeIf { it.isNotEmpty() }?.let { append(" &nbsp;").append(Html.muted("· $it")) }
        append("</p>")

        val notes = ArrayList<String>()
        when (pr.mergeStatus) {
            MergeStatus.CONFLICTS -> notes += Html.colored("✖ Merge conflicts", Html.red)
            MergeStatus.BLOCKED -> notes += Html.colored("● Blocked by policy", Html.orange)
            MergeStatus.CLEAN -> if (pr.isOpen) notes += Html.colored("✔ No conflicts", Html.green)
            MergeStatus.UNKNOWN -> Unit
        }
        if (pr.autoComplete) notes += Html.colored("⏱ Auto-complete is set", Html.purple)
        if (pr.fromFork) notes += Html.muted("From a fork")
        if (notes.isNotEmpty()) append("<p>").append(notes.joinToString(" &nbsp;·&nbsp; ")).append("</p>")
        if (pr.labels.isNotEmpty()) {
            append("<p>").append(pr.labels.joinToString(" &nbsp;") { "<code>" + Html.esc(it) + "</code>" }).append("</p>")
        }

        if (pr.reviewers.isNotEmpty()) {
            append("<h3>Reviewers</h3><table cellpadding='2'>")
            for (reviewer in pr.reviewers) {
                append("<tr><td>").append(Html.vote(reviewer.vote)).append("</td><td>").append(Html.esc(reviewer.user.label))
                if (reviewer.required) append(" ").append(Html.muted("(required)"))
                append("</td><td>").append(Html.muted(reviewer.vote.label)).append("</td></tr>")
            }
            append("</table>")
        }

        if (checks.isNotEmpty()) {
            val summary = CheckState.summarize(checks.map { it.state })
            append("<h3>Checks")
            summary?.let { append(" &nbsp;").append(Html.colored(it.label, Html.color(it))) }
            append("</h3><table cellpadding='2'>")
            for (check in checks) {
                append("<tr><td>").append(Html.colored(Html.symbol(check.state), Html.color(check.state))).append("</td><td>")
                append(check.url?.let { Html.link(check.name, it) } ?: Html.esc(check.name))
                append("</td><td>").append(Html.muted(check.description.ifBlank { check.state.label })).append("</td></tr>")
            }
            append("</table>")
        } else if (detailsLoaded && pr.checkState != null) {
            append("<p>Checks: ").append(Html.colored(pr.checkState.label, Html.color(pr.checkState))).append("</p>")
        }

        append("<h3>Description</h3>")
        append(if (pr.description.isBlank()) Html.muted("No description.") else Html.markdown(pr.description))
        append("<p>").append(Html.link("Open ${terms?.pullRequestShort ?: "PR"} in browser", pr.webUrl)).append("</p>")
        if (!detailsLoaded) append("<p>").append(Html.muted("Loading reviewers, checks and comments…")).append("</p>")
    }

    private fun conversationHtml(): String = buildString {
        if (!detailsLoaded) {
            append(Html.muted("Loading…"))
            return@buildString
        }
        if (threads.isEmpty()) append("<p>").append(Html.muted("No comments yet.")).append("</p>")
        for (thread in threads) {
            append("<div style='margin-bottom:10px'>")
            val place = thread.filePath?.let { path -> path + thread.line?.let { ":$it" }.orEmpty() }
            val status = when (thread.resolved) {
                true -> Html.colored("resolved", Html.green)
                false -> Html.colored("active", Html.orange)
                null -> ""
            }
            if (place != null || status.isNotEmpty()) {
                append("<p><code>").append(Html.esc(place.orEmpty())).append("</code> ").append(status).append("</p>")
            }
            thread.comments.forEachIndexed { index, comment ->
                append("<div style='margin-left:").append(if (index == 0) 0 else 16).append("px'>")
                append("<b>").append(Html.esc(comment.author.label)).append("</b> ").append(Html.muted(Format.date(comment.createdAt)))
                append(Html.markdown(comment.body).ifBlank { "<p></p>" })
                append("</div>")
            }
            append("<p><a href='reply:").append(Html.esc(thread.id)).append("'>Reply</a></p><hr/></div>")
        }
        append("<p><a href='reply:'>Add a comment…</a></p>")
    }

    // ---------------------------------------------------------------- actions

    private fun can(capability: Capability) = target?.provider?.supports(capability) == true
    private fun isOpen() = pr?.isOpen == true

    private fun actions(): DefaultActionGroup {
        val terms = { target?.provider?.terms ?: io.github.purgator.gitops.core.provider.Terminology() }
        val votes = DefaultActionGroup("More Votes", true).apply {
            templatePresentation.icon = AllIcons.Actions.More
            add(simpleAction("Approve with Suggestions", visible = { can(Capability.VOTE_SUGGESTIONS) }) { vote(Vote.APPROVED_WITH_SUGGESTIONS) })
            add(simpleAction("Wait for Author", visible = { can(Capability.VOTE_WAIT) }) { vote(Vote.WAITING) })
            add(simpleAction("Request Changes", visible = { can(Capability.VOTE_REJECT) }) { vote(Vote.REJECTED) })
            add(simpleAction("Reset Vote", visible = { can(Capability.VOTE_RESET) }) { vote(Vote.NONE) })
        }
        return DefaultActionGroup(
            simpleAction("Approve", AllIcons.Actions.Checked, enabled = ::isOpen, showText = true) { vote(Vote.APPROVED) },
            votes,
            simpleAction("Merge…", AllIcons.Vcs.Merge, "Merge / complete this pull request", enabled = { isOpen() && pr?.isDraft == false }, showText = true) { merge() },
            Separator.getInstance(),
            simpleAction("Check Out Branch", AllIcons.Actions.CheckOut, enabled = { pr != null }) { checkout() },
            simpleAction("Comment…", AllIcons.Actions.Edit, "Add a comment", enabled = { pr != null }) { comment("") },
            simpleAction("Edit Title and Description…", AllIcons.Actions.EditSource, enabled = ::isOpen) { edit() },
            simpleAction("Publish", AllIcons.Actions.Upload, "Mark as ready for review", visible = { can(Capability.DRAFT_PR) && pr?.isDraft == true }) { setDraft(false) },
            simpleAction("Mark as Draft", AllIcons.Actions.Pause, visible = { can(Capability.DRAFT_PR) && pr?.state == PrState.OPEN }) { setDraft(true) },
            simpleAction("Cancel Auto-Complete", AllIcons.Actions.Suspend, visible = { can(Capability.AUTO_COMPLETE) && pr?.autoComplete == true && isOpen() }) {
                perform("Auto-complete cancelled") { repo, pr -> repo.client().cancelAutoComplete(repo.ref, pr) }
            },
            simpleAction("Close", AllIcons.Actions.Cancel, "Close / abandon this pull request", visible = ::isOpen) { close(terms().closeVerb) },
            simpleAction("Reopen", AllIcons.Actions.Rollback, visible = { can(Capability.REOPEN_PR) && pr?.state == PrState.CLOSED }) {
                perform("Pull request reopened") { repo, pr -> repo.client().reopenPullRequest(repo.ref, pr) }
            },
            Separator.getInstance(),
            simpleAction("Open in Browser", AllIcons.General.Web, enabled = { pr != null }) { pr?.let { BrowserUtil.browse(it.webUrl) } },
            simpleAction("Copy Link", AllIcons.Actions.Copy, enabled = { pr != null }) { pr?.let { copyToClipboard(it.webUrl) } },
            simpleAction("Refresh", AllIcons.Actions.Refresh, enabled = { pr != null }) { reload() },
        )
    }

    /** Runs a mutation on the shown request, then refreshes the views. */
    private fun perform(done: String, work: (RepoTarget, PullRequest) -> Unit) {
        val current = pr ?: return
        mutate(done) { repo, _ -> work(repo, current) }
    }

    private fun vote(vote: Vote) = perform("Vote saved: ${vote.label.lowercase()}") { repo, pr -> repo.client().vote(repo.ref, pr, vote) }

    private fun setDraft(draft: Boolean) = perform(if (draft) "Marked as draft" else "Published") { repo, pr ->
        repo.client().updatePullRequest(repo.ref, pr, PullRequestUpdate(draft = draft))
    }

    private fun close(verb: String) {
        val current = pr ?: return
        val answer = Messages.showYesNoDialog(project, "$verb \"${current.title}\"?", "$verb Pull Request", verb, "Cancel", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        perform("Pull request closed") { repo, pr -> repo.client().closePullRequest(repo.ref, pr) }
    }

    private fun merge() {
        val current = pr ?: return
        val repo = target ?: return
        val options = MergeDialog(project, repo, current).takeIf { it.showAndGet() }?.options() ?: return
        perform(if (options.autoComplete) "Auto-complete set" else "Pull request merged") { r, pr -> r.client().mergePullRequest(r.ref, pr, options) }
    }

    private fun edit() {
        val current = pr ?: return
        val dialog = EditPullRequestDialog(project, current)
        if (!dialog.showAndGet()) return
        perform("Pull request updated") { repo, pr -> repo.client().updatePullRequest(repo.ref, pr, dialog.update()) }
    }

    private fun comment(threadId: String) {
        val text = Messages.showMultilineInputDialog(
            project, "Markdown is supported.", if (threadId.isEmpty()) "Add Comment" else "Reply", "", null, null,
        )?.trim()
        if (text.isNullOrEmpty()) return
        perform("Comment added") { repo, pr -> repo.client().addComment(repo.ref, pr, text, threadId.ifEmpty { null }) }
    }

    private fun checkout() {
        val current = pr ?: return
        val repo = target ?: return
        val remote = repo.remote
        val source = current.sourceBranch
        project.runWithProgress("Fetching $source", { _ ->
            val headRef = repo.provider.pullRequestHeadRef(current)
            if (current.fromFork && headRef != null) {
                val local = "pr/${current.id}"
                GitSupport.fetch(repo.git, remote, "+$headRef:refs/heads/$local")
                local
            } else {
                GitSupport.fetch(repo.git, remote, "+refs/heads/$source:refs/remotes/${remote.name}/$source")
                null
            }
        }) { forkBranch ->
            val brancher = GitBrancher.getInstance(project)
            val repos = listOf(repo.git)
            when {
                forkBranch != null -> brancher.checkout(forkBranch, false, repos, null)
                repo.git.branches.findLocalBranch(source) != null -> brancher.checkout(source, false, repos, null)
                else -> brancher.checkoutNewBranchStartingFrom(source, "${remote.name}/$source", repos, null)
            }
        }
    }

    // ---------------------------------------------------------------- changes

    private fun showChangesHint(text: String) {
        changesHint.text = text
        changes.removeAll()
        changes.add(changesHint, BorderLayout.CENTER)
        changes.revalidate()
        changes.repaint()
    }

    /** Fetches both ends of the request and shows the three-dot diff in the IDE's own changes browser. */
    private fun loadChanges() {
        val current = pr ?: return
        val repo = target ?: return
        val key = current.id + "@" + current.headSha
        if (changesShownFor == key) return
        changesShownFor = key
        showChangesHint("Fetching changes…")
        val remote = repo.remote
        runAsync(this, {
            val base = "refs/remotes/${remote.name}/${current.targetBranch}"
            val headRef = repo.provider.pullRequestHeadRef(current)
            val head: String
            if (headRef != null) {
                head = "refs/gitops/pr/${current.id}"
                GitSupport.fetch(repo.git, remote, "+refs/heads/${current.targetBranch}:$base", "+$headRef:$head")
            } else {
                head = "refs/remotes/${remote.name}/${current.sourceBranch}"
                GitSupport.fetch(repo.git, remote, "+refs/heads/${current.targetBranch}:$base", "+refs/heads/${current.sourceBranch}:$head")
            }
            GitSupport.changesBetween(repo.git, base, head)
        }, onError = { error ->
            if (changesShownFor == key) {
                changesShownFor = null
                showChangesHint("<html><center>Could not load the changes.<br/>${Html.esc(error.userMessage())}</center></html>")
            }
        }, isAlive = { !disposed }) { result ->
            if (changesShownFor != key) return@runAsync
            if (result.isEmpty()) {
                showChangesHint("No file changes")
            } else {
                changes.removeAll()
                changes.add(SimpleAsyncChangesBrowser(project, false, false).apply { setChangesToDisplay(result) }, BorderLayout.CENTER)
                changes.revalidate()
                changes.repaint()
            }
        }
    }
}
