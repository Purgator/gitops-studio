package io.github.purgator.gitops.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.accounts.AccountListener
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.accounts.CredentialException
import io.github.purgator.gitops.core.accounts.CredentialService
import io.github.purgator.gitops.core.http.JdkHttpTransport
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.util.GitUrl
import java.util.concurrent.ConcurrentHashMap

/** A local git repository bound to the cloud repository behind one of its remotes. */
class RepoTarget(
    val git: GitRepository,
    val remote: GitRemote,
    val remoteUrl: String,
    val ref: RepoRef,
    val provider: HostingProvider,
) {
    val account: Account? get() = AccountStore.get().find(provider, ref)

    val displayName: String get() = "${ref.name}  (${provider.displayName})"

    /** Blocking; call off the UI thread. */
    fun client(): HostingClient {
        val account = account ?: throw CredentialException(
            "No ${provider.displayName} account is configured for ${ref.serverUrl.substringAfter("://")}. " +
                "Add one in Settings | Tools | GitOps Studio.",
        )
        return service<ClientCache>().client(account, provider)
    }

    val currentBranch: String? get() = git.currentBranchName

    fun sameAs(other: RepoTarget?): Boolean = other != null && other.git.root == git.root && other.ref == ref

    override fun toString(): String = displayName
}

/** One client per account, rebuilt when accounts change. */
@Service(Service.Level.APP)
class ClientCache {
    private val clients = ConcurrentHashMap<String, HostingClient>()

    init {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(AccountListener.TOPIC, AccountListener { clients.clear() })
    }

    fun client(account: Account, provider: HostingProvider): HostingClient =
        clients.computeIfAbsent(account.id) {
            provider.createClient(account.serverUrl, JdkHttpTransport, service<CredentialService>().source(account, provider))
        }
}

/** A remote that no enabled module recognises, with a guess at the module that would. */
class UnknownRemote(val url: String, val host: String, val suggestedModule: String?)

/** Resolves the project's git repositories to cloud repositories. Cheap: no network involved. */
@Service(Service.Level.PROJECT)
class RepoContext(private val project: Project) {
    @Volatile
    private var selectedRoot: String? = null

    fun targets(): List<RepoTarget> {
        if (project.isDisposed) return emptyList()
        val providers = ModuleRegistry.get().providers()
        if (providers.isEmpty()) return emptyList()
        val accounts = AccountStore.get()
        return GitRepositoryManager.getInstance(project).repositories.mapNotNull { repo ->
            orderedRemotes(repo).firstNotNullOfOrNull { remote ->
                val url = remote.firstUrl ?: return@firstNotNullOfOrNull null
                val parsed = GitUrl.parse(url) ?: return@firstNotNullOfOrNull null
                providers.firstNotNullOfOrNull { provider ->
                    val servers = accounts.forProvider(provider.id).map { it.serverUrl }
                    provider.parseRemote(parsed, servers)?.let { RepoTarget(repo, remote, url, it, provider) }
                }
            }
        }
    }

    /** The repository the tool window works on: the user's pick, else the first one with an account. */
    fun current(): RepoTarget? {
        val all = targets()
        return all.firstOrNull { it.git.root.path == selectedRoot }
            ?: all.firstOrNull { it.account != null }
            ?: all.firstOrNull()
    }

    fun select(target: RepoTarget) {
        selectedRoot = target.git.root.path
    }

    /** Remotes of the project that no enabled module handles; drives the setup hints. */
    fun unknownRemotes(): List<UnknownRemote> {
        if (project.isDisposed) return emptyList()
        val handled = targets().map { it.git.root }.toSet()
        return GitRepositoryManager.getInstance(project).repositories
            .filter { it.root !in handled }
            .mapNotNull { repo -> orderedRemotes(repo).firstNotNullOfOrNull { it.firstUrl } }
            .mapNotNull { url -> GitUrl.parse(url)?.let { UnknownRemote(url, it.host, ModuleGuess.forRemote(it)) } }
            .distinctBy { it.url }
    }

    fun hasGitRepository(): Boolean = !project.isDisposed && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    /** Tracked remote of the current branch first, then `origin`, then the rest. */
    private fun orderedRemotes(repo: GitRepository): List<GitRemote> {
        val tracked = repo.currentBranch?.findTrackedBranch(repo)?.remote
        return repo.remotes.sortedBy { remote ->
            when {
                remote == tracked -> 0
                remote.name == GitRemote.ORIGIN -> 1
                else -> 2
            }
        }
    }

    companion object {
        fun get(project: Project): RepoContext = project.service()
    }
}

/** Host-based guess of the module behind a remote; works without loading any module class. */
object ModuleGuess {
    fun forRemote(url: GitUrl): String? {
        val host = url.host
        return when {
            host == "github.com" || host.startsWith("github.") -> "github"
            host.endsWith("dev.azure.com") || host.endsWith("visualstudio.com") || "/_git/" in "/${url.path}/" -> "azure"
            host == "gitlab.com" || host.startsWith("gitlab.") -> "gitlab"
            host == "bitbucket.org" -> "bitbucket"
            host == "codeberg.org" || host == "gitea.com" || host.startsWith("gitea.") || host.startsWith("forgejo.") -> "gitea"
            else -> null
        }
    }
}
