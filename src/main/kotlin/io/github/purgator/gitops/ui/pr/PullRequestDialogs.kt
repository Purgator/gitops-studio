package io.github.purgator.gitops.ui.pr

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.PullRequestUpdate
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Merge / complete options. Also used to configure auto-complete when creating a pull request. */
class MergeDialog(project: Project, private val repo: RepoTarget, pr: PullRequest) : DialogWrapper(project) {
    private val settings = GitOpsSettings.get().provider(repo.provider.id)
    private val strategy = ComboBox(DefaultComboBoxModel(MergeStrategy.entries.toTypedArray()))
    private val deleteBranch = JBCheckBox("Delete source branch after merging", settings.deleteSourceBranch && !pr.fromFork)
    private val autoComplete = JBCheckBox("Complete automatically when all checks and policies pass", pr.mergeStatus == io.github.purgator.gitops.core.model.MergeStatus.BLOCKED)
    private val completeWorkItems = JBCheckBox("Complete linked work items", settings.completeWorkItems)
    private val message = JBTextField()

    init {
        title = "${repo.provider.terms.mergeVerb} #${pr.id}"
        setOKButtonText(repo.provider.terms.mergeVerb)
        strategy.selectedItem = settings.strategy
        deleteBranch.isEnabled = !pr.fromFork
        message.emptyText.text = "Default message"
        init()
        // Only offer the strategies the repository allows; the default list stays if the lookup fails.
        runAsync(contentPanel, { repo.client().repoInfo(repo.ref).mergeStrategies }, isAlive = { !isDisposed }) { allowed ->
            if (allowed.isNotEmpty()) {
                val selected = strategy.selectedItem
                strategy.model = DefaultComboBoxModel(allowed.toTypedArray())
                strategy.selectedItem = if (selected in allowed) selected else allowed.first()
            }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Strategy:") { cell(strategy).align(AlignX.FILL) }
        row("Commit message:") { cell(message).align(AlignX.FILL) }
        row { cell(deleteBranch) }
        if (repo.provider.supports(Capability.TRANSITION_ISSUES)) row { cell(completeWorkItems) }
        if (repo.provider.supports(Capability.AUTO_COMPLETE)) row { cell(autoComplete) }
    }.apply { preferredSize = Dimension(JBUI.scale(460), preferredSize.height) }

    fun options() = MergeOptions(
        strategy = strategy.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE,
        deleteSourceBranch = deleteBranch.isEnabled && deleteBranch.isSelected,
        message = message.text.trim().ifEmpty { null },
        autoComplete = repo.provider.supports(Capability.AUTO_COMPLETE) && autoComplete.isSelected,
        completeWorkItems = repo.provider.supports(Capability.TRANSITION_ISSUES) && completeWorkItems.isSelected,
    )
}

class EditPullRequestDialog(project: Project, private val pr: PullRequest) : DialogWrapper(project) {
    private val titleField = JBTextField(pr.title)
    private val description = JBTextArea(pr.description, 12, 60).apply { lineWrap = true; wrapStyleWord = true }

    init {
        title = "Edit #${pr.id}"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Title:") { cell(titleField).align(AlignX.FILL).focused() }
        row { scrollCell(description).align(Align.FILL) }.resizableRow()
    }

    override fun doValidate(): ValidationInfo? =
        if (titleField.text.isBlank()) ValidationInfo("The title cannot be empty", titleField) else null

    fun update() = PullRequestUpdate(
        title = titleField.text.trim().takeIf { it != pr.title },
        description = description.text.takeIf { it != pr.description },
    )
}

/** Search-as-you-type picker for reviewers. */
class ReviewerChooserDialog(project: Project, private val repo: RepoTarget, initial: List<User>) : DialogWrapper(project) {
    private val search = SearchTextField(false)
    private val results = CollectionListModel<User>()
    private val resultList = JBList(results)
    private val chosen = CollectionListModel(initial)
    private val chosenList = JBList(chosen)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private var sequence = 0

    init {
        title = "Reviewers"
        resultList.emptyText.text = "Type a name to search"
        chosenList.emptyText.text = "No reviewer selected"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ runSearch() }, 300)
            }
        })
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) addSelected()
            }
        })
        resultList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) { addSelected(); e.consume() }
            }
        })
        chosenList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) removeSelected()
            }
        })
        init()
        runSearch()
    }

    private fun runSearch() {
        val query = search.text.trim()
        val request = ++sequence
        resultList.setPaintBusy(true)
        runAsync(contentPanel, { repo.client().searchReviewers(repo.ref, query) }, onError = {
            if (request == sequence) { resultList.setPaintBusy(false); resultList.emptyText.text = it.userMessage() }
        }, isAlive = { !isDisposed }) { users ->
            if (request != sequence) return@runAsync
            resultList.setPaintBusy(false)
            resultList.emptyText.text = if (query.isEmpty()) "Type a name to search" else "Nobody found"
            results.replaceAll(users)
        }
    }

    private fun addSelected() {
        resultList.selectedValuesList.filter { user -> chosen.items.none { it.id == user.id } }.forEach { chosen.add(it) }
    }

    private fun removeSelected() {
        chosenList.selectedValuesList.forEach { chosen.remove(it) }
    }

    override fun createCenterPanel(): JComponent {
        val add = JButton("Add ↓").apply { addActionListener { addSelected() } }
        val remove = JButton("Remove").apply { addActionListener { removeSelected() } }
        val buttons = JPanel().apply { add(add); add(remove) }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(search, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(resultList), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(200))
        }
        val bottom = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel("Selected reviewers:"), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(chosenList), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(120))
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(top, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    fun reviewers(): List<User> = chosen.items.toList()
}
