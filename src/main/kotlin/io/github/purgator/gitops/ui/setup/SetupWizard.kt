package io.github.purgator.gitops.ui.setup

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.ModuleGuess
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.modules.ModuleBean
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.ui.GitOps
import io.github.purgator.gitops.ui.GitOpsIcons
import io.github.purgator.gitops.ui.settings.AccountForm
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Guided first-run setup: pick the modules (the ones matching the project's remotes are pre-selected),
 * then connect one account per cloud module, each verified before moving on.
 */
class SetupWizard internal constructor(private val project: Project?) : DialogWrapper(project) {
    private val registry = ModuleRegistry.get()
    private val remotes: List<GitUrl> = project?.takeUnless { it.isDisposed }?.let { p ->
        GitRepositoryManager.getInstance(p).repositories.flatMap { it.remotes }.mapNotNull { it.firstUrl }.mapNotNull(GitUrl::parse)
    }.orEmpty()
    private val detected: Set<String> = remotes.mapNotNull(ModuleGuess::forRemote).toSet()

    private val boxes: Map<ModuleBean, JBCheckBox> = registry.beans.associateWith { bean ->
        JBCheckBox(bean.name, registry.isEnabled(bean.id) || bean.id in detected)
    }

    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val heading = JBLabel().apply {
        font = JBUI.Fonts.label().asBold().biggerOn(3f)
        border = JBUI.Borders.emptyBottom(10)
    }

    /** One step per cloud module that still needs an account; built after the module choice. */
    private var steps: List<Pair<HostingProvider, AccountForm>> = emptyList()
    private var index = -1 // -1 = module page, steps.size = final page
    private var busy = false

    private val backAction = object : AbstractAction("Back") {
        override fun actionPerformed(e: ActionEvent) = back()
    }
    private val skipAction = object : AbstractAction("Skip") {
        override fun actionPerformed(e: ActionEvent) = advance()
    }

    init {
        title = "Set Up GitOps Studio"
        content.add(modulesPage(), MODULES)
        init()
        showPage()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(heading, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(620), JBUI.scale(430))
    }

    override fun createActions(): Array<Action> = arrayOf(backAction, skipAction, okAction, cancelAction)

    private fun modulesPage(): JComponent = panel {
        row { comment("Only the modules you switch on are loaded. You can change this at any time in Settings | Tools | GitOps Studio.") }
        for ((bean, box) in boxes) {
            row {
                bean.loadIcon()?.let { icon(it) }
                cell(box).comment(bean.description + if (bean.id in detected) " <b>Detected in this project.</b>" else "")
            }
        }
    }

    private fun donePage(): JComponent {
        val connected = AccountStore.get().all().map { it.providerId }.toSet()
        val enabled = registry.beans.filter { registry.isEnabled(it.id) }
        return panel {
            row { label("GitOps Studio is ready.").bold() }
            for (bean in enabled) {
                row {
                    bean.loadIcon()?.let { icon(it) }
                    val state = when {
                        !bean.isCloud -> "enabled"
                        bean.id in connected -> "connected"
                        else -> "enabled, no account yet (add one in the settings)"
                    }
                    label("${bean.name}: $state")
                }
            }
            separator()
            row { comment("Create a pull request with <b>Ctrl+Alt+Shift+P</b>, from the Git menu or from the GitOps Studio tool window.") }
            row { comment("The status bar shows the pull request of the current branch; click it for quick actions.") }
        }
    }

    private fun showPage() {
        val last = index >= steps.size && index >= 0
        when {
            index < 0 -> {
                heading.text = "Choose your modules"
                cards.show(content, MODULES)
            }
            last -> {
                heading.text = "All set"
                content.add(donePage(), DONE)
                cards.show(content, DONE)
            }
            else -> {
                val (provider, _) = steps[index]
                heading.icon = GitOpsIcons.provider(provider.id)
                heading.text = "Connect ${provider.displayName}"
                cards.show(content, "step$index")
            }
        }
        if (index < 0 || last) heading.icon = GitOpsIcons.Logo
        backAction.isEnabled = index >= 0 && !last
        skipAction.isEnabled = index in steps.indices
        setOKButtonText(if (last) "Finish" else "Next")
        cancelAction.isEnabled = !last
        setErrorText(null)
    }

    private fun back() {
        if (busy) return
        index--
        showPage()
    }

    private fun advance() {
        if (busy) return
        index++
        showPage()
    }

    override fun doOKAction() {
        if (busy) return
        when {
            index < 0 -> {
                applyModules()
                advance()
            }
            index >= steps.size -> {
                GitOpsSettings.get().values.setupCompleted = true
                super.doOKAction()
                project?.let { GitOps.showToolWindow(it) }
            }
            else -> {
                val form = steps[index].second
                form.validate()?.let {
                    setErrorText(it.message, it.component)
                    return
                }
                busy = true
                isOKActionEnabled = false
                form.test { outcome ->
                    busy = false
                    isOKActionEnabled = true
                    if (outcome.isSuccess) {
                        form.save()
                        advance()
                    } else {
                        setErrorText("Could not sign in. Fix the form and press Next again, or Skip to do it later.")
                    }
                }
            }
        }
    }

    override fun doCancelAction() {
        // Leaving midway keeps what was already saved and does not nag again.
        if (index >= 0) GitOpsSettings.get().values.setupCompleted = true
        super.doCancelAction()
    }

    /** Enables the chosen modules and prepares an account step for each cloud module without an account. */
    private fun applyModules() {
        registry.setEnabled(boxes.filterValues { it.isSelected }.keys.map { it.id }.toSet())
        val accounts = AccountStore.get()
        // Repositories of this project that an enabled module recognises but no account can serve yet.
        val pending = project?.takeUnless { it.isDisposed }?.let { p -> RepoContext.get(p).targets().filter { it.account == null } }.orEmpty()
        steps = boxes.filter { (bean, box) -> box.isSelected && bean.isCloud }.keys.mapNotNull { bean ->
            val provider = registry.providerOf(bean.id) ?: return@mapNotNull null
            val missing = pending.firstOrNull { it.provider.id == provider.id }
            if (accounts.forProvider(provider.id).isNotEmpty() && missing == null) return@mapNotNull null
            provider to AccountForm(provider, null, missing?.ref?.serverUrl ?: suggestServer(provider))
        }
        steps.forEachIndexed { i, (_, form) -> content.add(form.component, "step$i") }
    }

    /** Server URL taken from the project's remotes, so most users only have to sign in. */
    private fun suggestServer(provider: HostingProvider): String? =
        remotes.firstNotNullOfOrNull { provider.parseRemote(it, emptyList())?.serverUrl }

    companion object {
        private const val MODULES = "modules"
        private const val DONE = "done"

        fun show(project: Project?) {
            SetupWizard(project).show()
        }
    }
}
