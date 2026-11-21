package io.github.purgator.gitops.ui.pr

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.ui.Format
import io.github.purgator.gitops.ui.GitOpsIcons
import io.github.purgator.gitops.ui.GitOpsListener
import io.github.purgator.gitops.ui.copyToClipboard
import io.github.purgator.gitops.ui.simpleAction
import io.github.purgator.gitops.ui.toolwindow.TargetPanel
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** "Pull Requests" tab: a filterable list on the left, the selected request on the right. */
class PullRequestsPanel(project: Project) : TargetPanel(project) {
    private val model = CollectionListModel<PullRequest>()
    private val list = JBList(model)
    private val filter = ComboBox(PrFilter.entries.toTypedArray())
    private val search = SearchTextField(false)
    private val details = PullRequestDetailsPanel(project)
    private var all: List<PullRequest> = emptyList()
    private var pendingSelection: String? = null
    private var requestId = 0

    init {
        Disposer.register(this, details)
        filter.selectedItem = GitOpsSettings.get().prFilter
        filter.addActionListener { reload() }
        search.textEditor.emptyText.text = "Filter by title, id, author or branch"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applySearch()
        })

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.addListSelectionListener { if (!it.valueIsAdjusting) details.show(list.selectedValue) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
                return true
            }
        }.installOn(list)

        val actions = DefaultActionGroup(
            simpleAction("New ${terms().pullRequest}…", AllIcons.General.Add, enabled = { target != null }) {
                CreatePullRequestDialog.open(project, target)
            },
            simpleAction("Refresh", AllIcons.Actions.Refresh) { reload() },
            simpleAction("Open in Browser", AllIcons.General.Web, enabled = { list.selectedValue != null }) {
                list.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
            },
            simpleAction("Copy Link", AllIcons.Actions.Copy, enabled = { list.selectedValue != null }) {
                list.selectedValue?.let { copyToClipboard(it.webUrl) }
            },
        )
        PopupHandler.installPopupMenu(list, actions, "GitOpsStudio.PullRequests.Popup")

        val north = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(toolbar("GitOpsStudio.PullRequests", actions).component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(filter, BorderLayout.EAST)
        }
        val left = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        }
        add(OnePixelSplitter(false, "GitOpsStudio.PullRequests.Splitter", 0.4f).apply {
            firstComponent = left
            secondComponent = details
        }, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(GitOpsListener.TOPIC, object : GitOpsListener {
            override fun refreshRequested() = reload(silent = true)

            override fun showPullRequest(id: String) {
                val known = all.firstOrNull { it.id == id }
                if (known != null) select(known) else { pendingSelection = id; reload(silent = true) }
            }
        })
        reload()
    }

    private fun terms() = target?.provider?.terms ?: io.github.purgator.gitops.core.provider.Terminology()

    override fun reload(silent: Boolean) {
        val keep = pendingSelection ?: list.selectedValue?.id
        val chosen = filter.selectedItem as? PrFilter ?: PrFilter.OPEN
        val request = ++requestId
        if (target == null) {
            show(emptyList(), null)
            list.emptyText.text = "No repository"
            return
        }
        list.setPaintBusy(true)
        if (!silent) list.emptyText.text = "Loading…"
        remote(
            work = { repo, client -> client.listPullRequests(repo.ref, chosen) },
            onError = { error ->
                if (request != requestId) return@remote
                list.setPaintBusy(false)
                if (!silent || all.isEmpty()) {
                    show(emptyList(), null)
                    list.emptyText.text = error.userMessage()
                }
            },
        ) { result ->
            if (request != requestId) return@remote
            list.setPaintBusy(false)
            pendingSelection = null
            list.emptyText.text = "No ${terms().pullRequests.lowercase()} for \"${chosen.label.lowercase()}\""
            show(result, keep)
        }
    }

    private fun show(items: List<PullRequest>, keepId: String?) {
        all = items
        applySearch(keepId ?: list.selectedValue?.id)
    }

    private fun applySearch(keepId: String? = list.selectedValue?.id) {
        val query = search.text.trim()
        val visible = if (query.isEmpty()) all else all.filter { pr ->
            listOf(pr.title, pr.id, pr.author.label, pr.sourceBranch, pr.targetBranch).any { it.contains(query, ignoreCase = true) }
        }
        model.replaceAll(visible)
        val again = visible.firstOrNull { it.id == keepId }
        if (again != null) list.setSelectedValue(again, true) else details.show(null)
    }

    private fun select(pr: PullRequest) {
        search.text = ""
        list.setSelectedValue(pr, true)
    }

    private class Renderer : ColoredListCellRenderer<PullRequest>() {
        override fun customizeCellRenderer(list: JList<out PullRequest>, value: PullRequest?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = GitOpsIcons.of(value.state)
            ipad = JBUI.insets(3, 6)
            append(value.title)
            append("  #${value.id}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val check = value.checkState?.let { " · ${it.label.lowercase()}" }.orEmpty()
            append(
                "   ${value.author.label} · ${value.sourceBranch} → ${value.targetBranch}$check · ${Format.date(value.updatedAt ?: value.createdAt)}",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
        }
    }
}
