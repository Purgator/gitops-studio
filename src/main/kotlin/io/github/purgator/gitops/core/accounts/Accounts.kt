package io.github.purgator.gitops.core.accounts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.util.GitUrl
import java.util.UUID

enum class AuthKind(val label: String) {
    /** A personal access token kept in the IDE password safe. */
    TOKEN("Personal access token"),

    /** The token of the vendor's command line tool (`gh`, `az`, `glab`). */
    CLI("Command line login"),

    /** Whatever git itself uses for the host, through `git credential fill`. */
    GIT_CREDENTIAL("Git credential manager"),
}

/** A sign-in to one server of one hosting service. Holds no secret. */
class Account {
    var id: String = UUID.randomUUID().toString()
    var providerId: String = ""
    var name: String = ""

    /** Web base URL; includes the organisation for Azure DevOps (`https://dev.azure.com/acme`). */
    var serverUrl: String = ""
    var username: String = ""
    var auth: String = AuthKind.TOKEN.name

    val authKind: AuthKind
        get() = AuthKind.entries.firstOrNull { it.name == auth } ?: AuthKind.TOKEN

    val displayName: String
        get() = name.ifBlank { serverUrl.substringAfter("://") }

    fun copy(): Account = Account().also { XmlSerializerUtil.copyBean(this, it) }

    override fun toString(): String = displayName
}

fun interface AccountListener {
    fun accountsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<AccountListener> = Topic.create("GitOps Studio accounts", AccountListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "GitOpsStudioAccounts", storages = [Storage("gitops-studio.xml", roamingType = RoamingType.DISABLED)])
class AccountStore : PersistentStateComponent<AccountStore.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var accounts: MutableList<Account> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    fun all(): List<Account> = state.accounts.toList()

    fun forProvider(providerId: String): List<Account> = state.accounts.filter { it.providerId == providerId }

    fun byId(id: String): Account? = state.accounts.firstOrNull { it.id == id }

    /** The account able to serve [repo], if any. */
    fun find(provider: HostingProvider, repo: RepoRef): Account? =
        forProvider(provider.id).firstOrNull { provider.accountMatches(it.serverUrl, repo) }

    /** Adds or replaces an account. [token] is stored in the password safe for token accounts. */
    fun save(account: Account, token: String?) {
        account.serverUrl = GitUrl.normalizeServerUrl(account.serverUrl)
        val index = state.accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) state.accounts[index] = account else state.accounts.add(account)
        val credentials = service<CredentialService>()
        if (account.authKind == AuthKind.TOKEN) {
            if (token != null) credentials.storeToken(account, token)
        } else {
            credentials.forgetToken(account)
        }
        credentials.invalidate(account.id)
        fireChanged()
    }

    fun remove(account: Account) {
        if (!state.accounts.removeIf { it.id == account.id }) return
        service<CredentialService>().forgetToken(account)
        fireChanged()
    }

    private fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(AccountListener.TOPIC).accountsChanged()
    }

    companion object {
        fun get(): AccountStore = service()
    }
}
