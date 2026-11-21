package io.github.purgator.gitops.core.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.PrFilter

/** Per hosting module preferences. One instance per provider id. */
class ProviderSettings {
    var providerId: String = ""
    var draftByDefault: Boolean = false
    var deleteSourceBranch: Boolean = true
    var mergeStrategy: String = MergeStrategy.MERGE.name
    var autoCompleteByDefault: Boolean = false
    var completeWorkItems: Boolean = true
    var linkWorkItemsFromBranch: Boolean = true

    /** Reviewer names or logins added to every new pull request, comma separated. */
    var defaultReviewers: String = ""

    /** `{type}`, `{id}` and `{title}` are replaced when creating a branch from an issue / work item. */
    var branchTemplate: String = "feature/{id}-{title}"

    val strategy: MergeStrategy
        get() = MergeStrategy.entries.firstOrNull { it.name == mergeStrategy } ?: MergeStrategy.MERGE
}

/** Application-wide, non-secret settings. Tokens live in the IDE password safe, never here. */
@Service(Service.Level.APP)
@State(name = "GitOpsStudioSettings", storages = [Storage("gitops-studio.xml", roamingType = RoamingType.DISABLED)])
class GitOpsSettings : PersistentStateComponent<GitOpsSettings.State> {

    class State {
        /** Ids of the modules the user switched on. Disabled modules are never class-loaded. */
        @XCollection(style = XCollection.Style.v2)
        var enabledModules: MutableList<String> = mutableListOf("git")
        var setupCompleted: Boolean = false

        // General
        var autoRefreshMinutes: Int = 5
        var showStatusBarWidget: Boolean = true
        var openInBrowserAfterCreate: Boolean = false
        var copyLinkAfterCreate: Boolean = false
        var defaultPrFilter: String = PrFilter.OPEN.name
        var useCommitsForDescription: Boolean = true
        var usePullRequestTemplate: Boolean = true
        var pushBeforeCreate: Boolean = true
        var pageSize: Int = 50

        // Git module
        var gitmojiEnabled: Boolean = true
        var gitmojiAsCode: Boolean = false
        var protectedBranches: String = "main, master, develop, release/*"
        var pruneOnSync: Boolean = true

        @XCollection(style = XCollection.Style.v2)
        var providers: MutableList<ProviderSettings> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    val values: State get() = state

    fun provider(providerId: String): ProviderSettings =
        state.providers.firstOrNull { it.providerId == providerId }
            ?: ProviderSettings().also { it.providerId = providerId; state.providers.add(it) }

    val prFilter: PrFilter
        get() = PrFilter.entries.firstOrNull { it.name == state.defaultPrFilter } ?: PrFilter.OPEN

    companion object {
        fun get(): GitOpsSettings = service()
    }
}
