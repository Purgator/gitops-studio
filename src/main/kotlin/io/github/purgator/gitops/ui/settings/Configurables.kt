package io.github.purgator.gitops.ui.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.OnOffButton
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.modules.ModuleBean
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.git.AutoFetchService
import io.github.purgator.gitops.git.Blame
import io.github.purgator.gitops.ui.setup.SetupWizard
import java.awt.Dimension
import javax.swing.JList

/** Settings | Tools | GitOps Studio: what is shared by every module. Each module has its own child page. */
class GitOpsConfigurable : BoundConfigurable("GitOps Studio"), SearchableConfigurable {
    override fun getId(): String = ID

    override fun createPanel(): DialogPanel {
        val state = GitOpsSettings.get().values
        val registry = ModuleRegistry.get()
        return panel {
            row {
                comment(
                    "Each module has its own page below this one, with a switch to turn it on or off. " +
                        "Disabled modules are not loaded at all.",
                )
            }
            group("Modules") {
                for (bean in registry.beans) {
                    row {
                        bean.loadIcon()?.let { icon(it) }
                        label(bean.name).bold()
                        comment(moduleStatus(bean))
                    }
                }
                row {
                    button("Run Setup Wizard…") {
                        SetupWizard.show(ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed })
                    }.comment("Enable modules and connect accounts step by step. Close and reopen this page to see the result.")
                }
            }
            group("Pull Requests") {
                row { checkBox("Push the branch automatically before creating a pull request").bindSelected(state::pushBeforeCreate) }
                row { checkBox("Use the repository's pull request template when there is one").bindSelected(state::usePullRequestTemplate) }
                row { checkBox("Otherwise build the description from the branch's commits").bindSelected(state::useCommitsForDescription) }
                row { checkBox("Open the pull request in the browser once created").bindSelected(state::openInBrowserAfterCreate) }
                row { checkBox("Copy the pull request link once created").bindSelected(state::copyLinkAfterCreate) }
                row("Default list filter:") {
                    comboBox(PrFilter.entries).bindItem(
                        { GitOpsSettings.get().prFilter },
                        { state.defaultPrFilter = (it ?: PrFilter.OPEN).name },
                    )
                }
            }
            group("Interface") {
                row("Refresh the tool window every") {
                    spinner(0..120, 1).bindIntValue(state::autoRefreshMinutes)
                    label("minutes").comment("0 turns automatic refresh off")
                }
                row {
                    checkBox("Show the pull request of the current branch in the status bar").bindSelected(state::showStatusBarWidget)
                        .comment("Applies to newly opened projects")
                }
            }
        }
    }

    private fun moduleStatus(bean: ModuleBean): String {
        if (!ModuleRegistry.get().isEnabled(bean.id)) return "Off"
        if (!bean.isCloud) return "On"
        val accounts = AccountStore.get().forProvider(bean.id).size
        return when (accounts) {
            0 -> "On, no account connected"
            1 -> "On, 1 account"
            else -> "On, $accounts accounts"
        }
    }

    companion object {
        const val ID = "gitops.studio"
    }
}

/**
 * Base of the per-module pages. The first row is always the module's on/off switch; everything below
 * is greyed out while the module is off.
 */
abstract class ModuleConfigurable(protected val moduleId: String) :
    BoundConfigurable(ModuleRegistry.get().bean(moduleId)?.name ?: moduleId), SearchableConfigurable {

    protected val bean: ModuleBean? get() = ModuleRegistry.get().bean(moduleId)

    override fun getId(): String = "${GitOpsConfigurable.ID}.$moduleId"

    override fun createPanel(): DialogPanel {
        val registry = ModuleRegistry.get()
        val switch = OnOffButton()
        val on = object : ComponentPredicate() {
            override fun invoke(): Boolean = switch.isSelected
            override fun addListener(listener: (Boolean) -> Unit) = switch.addActionListener { listener(switch.isSelected) }
        }
        return panel {
            row {
                bean?.loadIcon()?.let { icon(it) }
                label("${bean?.name ?: moduleId} module").bold()
                cell(switch).bind(
                    { it.isSelected },
                    { component, value -> component.isSelected = value },
                    MutableProperty({ registry.isEnabled(moduleId) }, { registry.setEnabled(moduleId, it) }),
                )
            }
            row { comment(bean?.description.orEmpty()) }
            separator()
            panel { moduleSettings() }.enabledIf(on)
        }
    }

    protected abstract fun Panel.moduleSettings()
}

/** Settings of a cloud module: its accounts and its pull request / work item defaults. */
abstract class ProviderConfigurable(moduleId: String) : ModuleConfigurable(moduleId) {
    private val accounts = CollectionListModel<Account>()

    override fun Panel.moduleSettings() {
        // Describing the module's options needs its provider; loading it here is on demand, not at startup.
        val provider = ModuleRegistry.get().providerOf(moduleId) ?: return
        val settings = GitOpsSettings.get().provider(provider.id)
        val terms = provider.terms

        group("Accounts") {
            row { cell(accountsPanel(provider)).align(Align.FILL) }
            row { comment("Tokens are kept in the IDE password safe. Account changes are saved immediately.") }
        }
        group(terms.pullRequests) {
            if (provider.supports(Capability.DRAFT_PR)) {
                row { checkBox("Create as draft by default").bindSelected(settings::draftByDefault) }
            }
            if (provider.supports(Capability.REVIEWERS)) {
                row("Default reviewers:") {
                    textField().bindText(settings::defaultReviewers).align(AlignX.FILL)
                        .comment("Names or logins, comma separated. Added to every new ${terms.pullRequest.lowercase()}.")
                }
            }
            if (provider.supports(Capability.LINK_ISSUES)) {
                row {
                    checkBox("Link ${terms.issues.lowercase()} whose id appears in the branch name").bindSelected(settings::linkWorkItemsFromBranch)
                        .comment("feature/1234-login links #1234")
                }
            }
            row("Default merge strategy:") {
                comboBox(MergeStrategy.entries).bindItem({ settings.strategy }, { settings.mergeStrategy = (it ?: MergeStrategy.MERGE).name })
            }
            row { checkBox("Delete the source branch after merging").bindSelected(settings::deleteSourceBranch) }
            if (provider.supports(Capability.AUTO_COMPLETE)) {
                row { checkBox("Set auto-complete on new ${terms.pullRequests.lowercase()}").bindSelected(settings::autoCompleteByDefault) }
            }
            if (provider.supports(Capability.TRANSITION_ISSUES)) {
                row { checkBox("Complete linked ${terms.issues.lowercase()} when merging").bindSelected(settings::completeWorkItems) }
            }
        }
        if (provider.supports(Capability.ISSUES)) {
            group(terms.issues) {
                row("Branch name template:") {
                    textField().bindText(settings::branchTemplate).align(AlignX.FILL)
                        .comment("Placeholders: {id}, {title}, {type}. Used by \"Start Working: Create Branch\".")
                }
            }
        }
    }

    private fun accountsPanel(provider: HostingProvider) = JBList(accounts).let { list ->
        fun reloadAccounts() = accounts.replaceAll(AccountStore.get().forProvider(provider.id))
        reloadAccounts()
        list.emptyText.text = "No account. Press + to connect one."
        list.cellRenderer = object : ColoredListCellRenderer<Account>() {
            override fun customizeCellRenderer(l: JList<out Account>, value: Account?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.displayName)
                append("   ${value.serverUrl.substringAfter("://")} · ${value.authKind.label}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        ToolbarDecorator.createDecorator(list)
            .disableUpDownActions()
            .setAddAction { if (AccountDialog(null, provider, null).showAndGet()) reloadAccounts() }
            .setEditAction { list.selectedValue?.let { if (AccountDialog(null, provider, it).showAndGet()) reloadAccounts() } }
            .setRemoveAction {
                list.selectedValue?.let { account ->
                    val answer = Messages.showYesNoDialog("Remove the account \"${account.displayName}\"?", "Remove Account", Messages.getQuestionIcon())
                    if (answer == Messages.YES) {
                        AccountStore.get().remove(account)
                        reloadAccounts()
                    }
                }
            }
            .createPanel().apply { preferredSize = Dimension(JBUI.scale(480), JBUI.scale(120)) }
    }
}

class GitHubConfigurable : ProviderConfigurable("github")
class AzureDevOpsConfigurable : ProviderConfigurable("azure")
class GitLabConfigurable : ProviderConfigurable("gitlab")
class BitbucketConfigurable : ProviderConfigurable("bitbucket")
class GiteaConfigurable : ProviderConfigurable("gitea")

/** Settings of the local Git module. */
class GitModuleConfigurable : ModuleConfigurable(ModuleRegistry.GIT) {
    override fun Panel.moduleSettings() {
        val state = GitOpsSettings.get().values
        group("Editor") {
            row {
                checkBox("Show who last changed the current line, at the end of the line").bindSelected(state::inlineBlame)
                    .comment("Ann Lee, 3 days ago • Fix the login")
            }
            row { checkBox("Include the commit subject").bindSelected(state::inlineBlameSubject) }
        }
        group("Commit Messages") {
            row {
                checkBox("Show the gitmoji picker in the commit message toolbar").bindSelected(state::gitmojiEnabled)
            }
            row {
                checkBox("Suggest a gitmoji while typing the first word of the message").bindSelected(state::gitmojiCompletion)
                    .comment("Type feature, fix, docs, refactor… or the name of a gitmoji, such as sparkles")
            }
            row {
                checkBox("Insert the :code: instead of the emoji character").bindSelected(state::gitmojiAsCode)
                    .comment("✨ Add login   versus   :sparkles: Add login")
            }
        }
        group("Branches") {
            row("Protected branches:") {
                textField().bindText(state::protectedBranches).align(AlignX.FILL)
                    .comment("Comma separated, * allowed. Never offered for deletion by Clean Up Branches.")
            }
            row {
                checkBox("Show the commits to push and to pull in the status bar").bindSelected(state::showAheadBehind)
                    .comment("Applies to newly opened projects")
            }
        }
        group("Remotes") {
            row { checkBox("Prune deleted remote branches when syncing").bindSelected(state::pruneOnSync) }
            row("Fetch automatically every") {
                spinner(0..240, 1).bindIntValue(state::autoFetchMinutes)
                label("minutes").comment("0 turns automatic fetch off")
            }
        }
    }

    override fun apply() {
        super.apply()
        AutoFetchService.rescheduleAll()
        Blame.repaintEditors()
    }
}
