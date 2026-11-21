package io.github.purgator.gitops.providers.gitea

import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.encodePath
import io.github.purgator.gitops.core.http.urlEncode
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.modules.GitOpsModule
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.util.GitUrl

class GiteaModule : GitOpsModule {
    override val provider: HostingProvider = GiteaProvider
}

/** Gitea and its forks (Forgejo, Codeberg): same REST API v1. */
object GiteaProvider : HostingProvider {
    private val WELL_KNOWN_HOSTS = setOf("codeberg.org", "gitea.com")
    private val SHA = Regex("[0-9a-f]{40}")

    override val id = "gitea"
    override val displayName = "Gitea / Forgejo"
    override val defaultServerUrl = "https://codeberg.org"
    override val tokenScopesHint = "Scopes: repository (read & write), issue (read & write), user (read), organization (read)."

    override val capabilities = setOf(
        Capability.DRAFT_PR, Capability.REVIEWERS, Capability.LABELS, Capability.LINK_ISSUES, Capability.AUTO_COMPLETE,
        Capability.REOPEN_PR, Capability.VOTE_REJECT,
        Capability.PIPELINES, Capability.RUN_PIPELINE, Capability.JOB_LOGS,
        Capability.ISSUES, Capability.CREATE_ISSUE, Capability.ISSUE_COMMENTS, Capability.REPOSITORIES,
    )

    override fun tokenCreationUrl(serverUrl: String): String =
        "${GitUrl.normalizeServerUrl(serverUrl.ifBlank { defaultServerUrl })}/user/settings/applications"

    override fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef? {
        val seg = url.segments
        if (seg.size < 2) return null
        val host = url.host
        // A configured account wins: its URL carries the real scheme and port of the web interface.
        val known = knownServers.firstOrNull { GitUrl.hostOf(it) == host }
        val server = when {
            known != null -> GitUrl.normalizeServerUrl(known)
            host in WELL_KNOWN_HOSTS || host.startsWith("gitea.") || host.startsWith("forgejo.") -> url.webBase
            else -> return null
        }
        return RepoRef(id, server, seg[0], seg[1])
    }

    override fun authHeaders(secret: Secret): Map<String, String> = mapOf("Authorization" to "token ${secret.token}")

    override fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient =
        GiteaClient(GitUrl.normalizeServerUrl(serverUrl), transport, secrets)

    internal fun repoWebUrl(repo: RepoRef): String =
        "${repo.serverUrl.trimEnd('/')}/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"

    override fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange?): String {
        val kind = if (ref.matches(SHA)) "commit" else "branch"
        val anchor = lines?.let {
            val first = it.first + 1
            val last = it.last + 1
            if (last > first) "#L$first-L$last" else "#L$first"
        }.orEmpty()
        return "${repoWebUrl(repo)}/src/$kind/${encodePath(ref)}/${encodePath(path.trimStart('/'))}$anchor"
    }

    override fun commitUrl(repo: RepoRef, sha: String): String = "${repoWebUrl(repo)}/commit/$sha"

    override fun pullRequestHeadRef(pr: PullRequest): String = "refs/pull/${pr.id}/head"

    override fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String =
        "${repoWebUrl(repo)}/compare/" +
            (targetBranch?.let { "${encodePath(it)}..." }.orEmpty()) + encodePath(sourceBranch)
}
