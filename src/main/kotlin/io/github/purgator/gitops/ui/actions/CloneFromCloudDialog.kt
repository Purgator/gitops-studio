package io.github.purgator.gitops.ui.actions

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.Git
import io.github.purgator.gitops.core.ClientCache
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.model.RemoteRepository
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.ui.setup.SetupWizard
import com.intellij.openapi.components.service
import java.awt.Dimension
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Browse the repositories of a connected account and clone one. */
class CloneFromCloudDialog internal constructor(private val project: Project, accounts: List<Account>) : DialogWrapper(project) {
    private val account = ComboBox(DefaultComboBoxModel(accounts.toTypedArray()))
    private val search = SearchTextField(false)
    private val model = CollectionListModel<RemoteRepository>()
    private val list = JBList(model)
    private val directory = TextFieldWithBrowseButton()
    private val ssh = JBCheckBox("Clone with SSH")
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private var sequence = 0

    init {
        title = "Clone from Cloud"
        setOKButtonText("Clone")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<RemoteRepository>() {
            override fun customizeCellRenderer(l: JList<out RemoteRepository>, value: RemoteRepository?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                ipad = JBUI.insets(2, 6)
                append(value.fullName)
                if (value.private) append("  private", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (value.description.isNotBlank()) append("   ${value.description.lineSequence().first().take(90)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        directory.text = defaultDirectory()
        directory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Parent Directory"))
        account.addActionListener { load() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ load() }, 400)
            }
        })
        init()
        load()
    }

    private fun defaultDirectory(): String {
        val base = project.takeUnless { it.isDefault }?.basePath?.let { File(it).parent }
        return base ?: File(System.getProperty("user.home"), "source").path
    }

    private fun load() {
        val chosen = account.selectedItem as? Account ?: return
        val provider = ModuleRegistry.get().provider(chosen.providerId) ?: return
        val query = search.text.trim()
        val request = ++sequence
        list.setPaintBusy(true)
        list.emptyText.text = "Loading…"
        runAsync(contentPanel, { service<ClientCache>().client(chosen, provider).listRepositories(query) }, onError = {
            if (request == sequence) { list.setPaintBusy(false); model.removeAll(); list.emptyText.text = it.userMessage() }
        }, isAlive = { !isDisposed }) { repos ->
            if (request != sequence) return@runAsync
            list.setPaintBusy(false)
            list.emptyText.text = "No repository found"
            model.replaceAll(repos)
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Account:") { cell(account).align(AlignX.FILL) }
        row { cell(search).align(AlignX.FILL) }
        row { cell(ScrollPaneFactory.createScrollPane(list)).align(Align.FILL) }.resizableRow()
        row("Directory:") { cell(directory).align(AlignX.FILL) }
        row { cell(ssh) }
    }.apply { preferredSize = Dimension(JBUI.scale(620), JBUI.scale(460)) }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun doValidate(): ValidationInfo? {
        val repo = list.selectedValue ?: return ValidationInfo("Select a repository", list)
        val parent = File(directory.text.trim())
        return when {
            directory.text.isBlank() -> ValidationInfo("Choose where to clone", directory)
            File(parent, folderName(repo)).exists() -> ValidationInfo("${folderName(repo)} already exists in this directory", directory)
            else -> null
        }
    }

    private fun folderName(repo: RemoteRepository) = repo.fullName.substringAfterLast('/')

    override fun doOKAction() {
        val repo = list.selectedValue ?: return
        val parent = File(directory.text.trim())
        if (!parent.isDirectory && !parent.mkdirs()) {
            setErrorText("Cannot create ${parent.path}", directory)
            return
        }
        val parentFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)
        if (parentFile == null) {
            setErrorText("Cannot access ${parent.path}", directory)
            return
        }
        val url = if (ssh.isSelected && repo.sshUrl.isNotBlank()) repo.sshUrl else repo.httpUrl
        super.doOKAction()
        GitCheckoutProvider.clone(
            project,
            Git.getInstance(),
            ProjectLevelVcsManager.getInstance(project).compositeCheckoutListener,
            parentFile,
            url,
            folderName(repo),
            parent.path,
        )
    }

    companion object {
        fun open(project: Project?) {
            val accounts = ModuleRegistry.get().providers()
                .filter { it.supports(Capability.REPOSITORIES) }
                .flatMap { AccountStore.get().forProvider(it.id) }
            if (accounts.isEmpty()) {
                Notify.warn(project, "No account connected", "Connect an account to browse its repositories.", "Set Up…" to { SetupWizard.show(project) })
                return
            }
            CloneFromCloudDialog(project ?: ProjectManager.getInstance().defaultProject, accounts).show()
        }
    }
}
