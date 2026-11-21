package io.github.purgator.gitops.ui.issues

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import git4idea.branch.GitBrancher
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.model.Issue
import io.github.purgator.gitops.core.model.IssueFilter
import io.github.purgator.gitops.core.model.NewIssue
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.ui.Format
import io.github.purgator.gitops.ui.GitOpsListener
import io.github.purgator.gitops.ui.Html
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.htmlPane
import io.github.purgator.gitops.ui.showHtml
import io.github.purgator.gitops.ui.simpleAction
import io.github.purgator.gitops.ui.toolwindow.TargetPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** "Issues" / "Work Items" tab: search and filter on the left, the selected item on the right. */
class IssuesPanel(project: Project) : TargetPanel(project) {
    private val model = CollectionListModel<Issue>()
    private val list = JBList(model)
    private val filter = ComboBox(IssueFilter.entries.toTypedArray())
    private val search = SearchTextField(false)
    private val details = htmlPane()
    private var requestId = 0

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.addListSelectionListener { if (!it.valueIsAdjusting) render() }
        filter.addActionListener { reload() }
        search.textEditor.emptyText.text = "Search on the server, then press Enter"
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) reload()
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
                return true
            }
        }.installOn(list)

        val selected = { list.selectedValue }
        val actions = DefaultActionGroup(
            simpleAction("New ${terms().issue}…", AllIcons.General.Add, enabled = { can(Capability.CREATE_ISSUE) }) { create() },
            simpleAction("Refresh", AllIcons.Actions.Refresh) { reload() },
            Separator.getInstance(),
            simpleAction("Start Working: Create Branch…", AllIcons.Vcs.Branch, "Create a branch named after this item", enabled = { selected() != null }) { createBranch() },
            simpleAction("Change State…", AllIcons.Actions.Forward, enabled = { selected() != null }) { changeState() },
            simpleAction("Comment…", AllIcons.Actions.Edit, enabled = { selected() != null && can(Capability.ISSUE_COMMENTS) }) { comment() },
            simpleAction("Open in Browser", AllIcons.General.Web, enabled = { selected() != null }) { selected()?.let { BrowserUtil.browse(it.webUrl) } },
            simpleAction("Copy Link", AllIcons.Actions.Copy, enabled = { selected() != null }) { selected()?.let { copyToClipboard(it.webUrl) } },
            simpleAction("Copy Id", enabled = { selected() != null }) { selected()?.let { copyToClipboard(it.id) } },
        )
        PopupHandler.installPopupMenu(list, actions, "GitOpsStudio.Issues.Popup")

        val north = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(toolbar("GitOpsStudio.Issues", actions).component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(filter, BorderLayout.EAST)
        }
        val left = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        }
        add(OnePixelSplitter(false, "GitOpsStudio.Issues.Splitter", 0.45f).apply {
            firstComponent = left
            secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        }, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(GitOpsListener.TOPIC, object : GitOpsListener {
            override fun refreshRequested() = reload(silent = true)
        })
        render()
        reload()
    }

    private fun terms() = target?.provider?.terms ?: io.github.purgator.gitops.core.provider.Terminology()
    private fun can(capability: Capability) = target?.provider?.supports(capability) == true

    override fun reload(silent: Boolean) {
        val request = ++requestId
        if (target == null) {
            model.removeAll()
            return
        }
        val chosen = filter.selectedItem as? IssueFilter ?: IssueFilter.ASSIGNED_TO_ME
        val query = search.text.trim()
        val keep = list.selectedValue?.id
        list.setPaintBusy(true)
        if (!silent) list.emptyText.text = "Loading…"
        remote(
            work = { repo, client -> client.listIssues(repo.ref, chosen, query) },
            onError = { error ->
                if (request != requestId) return@remote
                list.setPaintBusy(false)
                if (!silent || model.isEmpty) {
                    model.removeAll()
                    list.emptyText.text = error.userMessage()
                }
            },
        ) { result ->
            if (request != requestId) return@remote
            list.setPaintBusy(false)
            list.emptyText.text = "No ${terms().issues.lowercase()} found"
            model.replaceAll(result)
            result.firstOrNull { it.id == keep }?.let { list.setSelectedValue(it, false) } ?: render()
        }
    }

    private fun render() {
        val issue = list.selectedValue
        if (issue == null) {
            details.showHtml(Html.muted("Select an item to see its details"))
            return
        }
        details.showHtml(buildString {
            append("<h2>").append(Html.esc(issue.title)).append(" ").append(Html.muted("#" + issue.id)).append("</h2><p>")
            append("<b>").append(Html.colored(Html.esc(issue.state), if (issue.open) Html.green else Html.purple)).append("</b>")
            if (issue.type.isNotBlank()) append(" &nbsp;·&nbsp; ").append(Html.esc(issue.type))
            issue.assignee?.let { append(" &nbsp;·&nbsp; assigned to ").append(Html.esc(it.label)) }
            issue.author?.let { append(" &nbsp;·&nbsp; by ").append(Html.esc(it.label)) }
            Format.date(issue.updatedAt).takeIf { it.isNotEmpty() }?.let { append(" &nbsp;").append(Html.muted("· updated $it")) }
            append("</p>")
            if (issue.labels.isNotEmpty()) {
                append("<p>").append(issue.labels.joinToString(" &nbsp;") { "<code>" + Html.esc(it) + "</code>" }).append("</p>")
            }
            append("<hr/>")
            append(
                when {
                    issue.description.isBlank() -> Html.muted("No description.")
                    issue.htmlDescription -> issue.description
                    else -> Html.markdown(issue.description)
                },
            )
            append("<p>").append(Html.link("Open in browser", issue.webUrl)).append("</p>")
        })
    }

    private fun create() {
        val repo = target ?: return
        val dialog = NewIssueDialog(project, repo)
        if (!dialog.showAndGet()) return
        val issue = dialog.issue()
        mutate("${terms().issue} created") { r, client -> client.createIssue(r.ref, issue) }
    }

    private fun changeState() {
        val issue = list.selectedValue ?: return
        remote(work = { repo, client -> client.issueStates(repo.ref, issue) }) { states ->
            if (states.isEmpty()) {
                Messages.showInfoMessage(project, "No other state is available for this item.", "Change State")
                return@remote
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(states)
                .setTitle("Move #${issue.id} To")
                .setItemChosenCallback { state -> mutate("#${issue.id} is now $state") { repo, client -> client.setIssueState(repo.ref, issue, state) } }
                .createPopup()
                .showInCenterOf(this)
        }
    }

    private fun comment() {
        val issue = list.selectedValue ?: return
        val text = Messages.showMultilineInputDialog(project, "Comment on #${issue.id}", "Add Comment", "", null, null)?.trim()
        if (text.isNullOrEmpty()) return
        mutate("Comment added") { repo, client -> client.addIssueComment(repo.ref, issue, text) }
    }

    /** Creates and checks out a branch named from the provider's branch template. */
    private fun createBranch() {
        val issue = list.selectedValue ?: return
        val repo = target ?: return
        val template = GitOpsSettings.get().provider(repo.provider.id).branchTemplate.ifBlank { "feature/{id}-{title}" }
        val suggestion = template
            .replace("{id}", issue.id)
            .replace("{type}", Format.slug(issue.type.ifBlank { "feature" }))
            .replace("{title}", Format.slug(issue.title, 40))
            .trim('-', '/')
        val name = Messages.showInputDialog(project, "Branch name:", "Create Branch for #${issue.id}", null, suggestion, null)?.trim()
        if (name.isNullOrEmpty()) return
        val brancher = GitBrancher.getInstance(project)
        if (repo.git.branches.findLocalBranch(name) != null) {
            brancher.checkout(name, false, listOf(repo.git), null)
        } else {
            brancher.checkoutNewBranch(name, listOf(repo.git))
        }
    }

    private class Renderer : ColoredListCellRenderer<Issue>() {
        override fun customizeCellRenderer(list: JList<out Issue>, value: Issue?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = if (value.open) AllIcons.General.TodoDefault else AllIcons.Actions.Checked
            ipad = JBUI.insets(3, 6)
            append(value.title)
            append("  #${value.id}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val details = listOf(value.type, value.state, value.assignee?.label.orEmpty(), Format.date(value.updatedAt))
                .filter { it.isNotBlank() }.joinToString(" · ")
            append("   $details", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}

class NewIssueDialog(project: Project, private val repo: RepoTarget) : DialogWrapper(project) {
    private val titleField = JBTextField()
    private val description = JBTextArea(10, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val type = ComboBox<String>()
    private val assignToMe = JBCheckBox("Assign to me", true)
    private val labels = JBTextField()
    private val withTypes = repo.provider.supports(Capability.ISSUE_TYPES)

    init {
        title = "New ${repo.provider.terms.issue}"
        setOKButtonText("Create")
        labels.emptyText.text = "Comma separated"
        init()
        if (withTypes) {
            runAsync(contentPanel, { repo.client().issueTypes(repo.ref) }, isAlive = { !isDisposed }) { types ->
                type.model = DefaultComboBoxModel(types.toTypedArray())
                type.selectedItem = types.firstOrNull { it == "Task" } ?: types.firstOrNull { it.equals("User Story", true) } ?: types.firstOrNull()
            }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        if (withTypes) row("Type:") { cell(type).align(AlignX.FILL) }
        row("Title:") { cell(titleField).align(AlignX.FILL).focused() }
        row { scrollCell(description).align(Align.FILL) }.resizableRow()
        row("Labels:") { cell(labels).align(AlignX.FILL) }
        row { cell(assignToMe) }
    }.apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(380)) }

    override fun doValidate(): ValidationInfo? =
        if (titleField.text.isBlank()) ValidationInfo("The title cannot be empty", titleField) else null

    fun issue() = NewIssue(
        title = titleField.text.trim(),
        description = description.text.trim(),
        type = (type.selectedItem as? String)?.takeIf { withTypes },
        assignToMe = assignToMe.isSelected,
        labels = labels.text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
    )
}
