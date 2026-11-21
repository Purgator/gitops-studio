package io.github.purgator.gitops.providers.gitlab

import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.encodePath
import io.github.purgator.gitops.core.http.urlEncode
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.modules.GitOpsModule
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.provider.CliLogin
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.provider.Terminology
import io.github.purgator.gitops.core.util.GitUrl

class GitLabModule : GitOpsModule {
    override val provider: HostingProvider = GitLabProvider
}

object GitLabProvider : HostingProvider {
    override val id = "gitlab"
    override val displayName = "GitLab"
    override val defaultServerUrl = "https://gitlab.com"
    override val tokenScopesHint = "Scope: api."

    override val capabilities = setOf(
        Capability.DRAFT_PR, Capability.REVIEWERS, Capability.LABELS, Capability.LINK_ISSUES, Capability.AUTO_COMPLETE,
        Capability.REOPEN_PR, Capability.VOTE_RESET,
        Capability.PIPELINES, Capability.RUN_PIPELINE, Capability.RERUN_PIPELINE, Capability.CANCEL_PIPELINE, Capability.JOB_LOGS,
        Capability.ISSUES, Capability.CREATE_ISSUE, Capability.ISSUE_COMMENTS, Capability.REPOSITORIES,
    )

    override val terms = Terminology(
        pullRequest = "Merge Request",
        pullRequests = "Merge Requests",
        pullRequestShort = "MR",
    )

    override val cliLogin = CliLogin(
        tool = "glab",
        displayName = "GitLab CLI",
        tokenArgs = { server -> listOf("config", "get", "token", "--host", GitUrl.hostOf(server.ifBlank { defaultServerUrl })) },
        loginHint = "Run `glab auth login` in a terminal first.",
    )

    override fun tokenCreationUrl(serverUrl: String): String =
        "${server(serverUrl)}/-/user_settings/personal_access_tokens?name=GitOps+Studio&scopes=api"

    override fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef? {
        val host = url.host
        val known = knownServers.firstOrNull { GitUrl.hostOf(it) == host }
        if (host != "gitlab.com" && !host.startsWith("gitlab.") && known == null) return null
        val seg = url.segments
        if (seg.size < 2) return null
        // A self-managed instance may live on a non-default port or scheme; trust the account's URL over an ssh remote.
        val serverUrl = when {
            !url.isSsh -> url.webBase
            known != null -> GitUrl.normalizeServerUrl(known)
            else -> "https://$host"
        }
        return RepoRef(id, serverUrl, seg.dropLast(1).joinToString("/"), seg.last())
    }

    override fun authHeaders(secret: Secret): Map<String, String> = mapOf("Authorization" to "Bearer ${secret.token}")

    override fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient =
        GitLabClient(server(serverUrl), transport, secrets)

    private fun server(serverUrl: String): String =
        GitUrl.normalizeServerUrl(serverUrl.ifBlank { defaultServerUrl })

    internal fun repoWebUrl(repo: RepoRef): String =
        "${repo.serverUrl.trimEnd('/')}/${encodePath(repo.owner)}/${urlEncode(repo.name)}"

    override fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange?): String {
        val anchor = lines?.let {
            val first = it.first + 1
            val last = it.last + 1
            if (last > first) "#L$first-$last" else "#L$first"
        }.orEmpty()
        return "${repoWebUrl(repo)}/-/blob/${encodePath(ref)}/${encodePath(path.trimStart('/'))}$anchor"
    }

    override fun commitUrl(repo: RepoRef, sha: String): String = "${repoWebUrl(repo)}/-/commit/$sha"

    override fun pullRequestHeadRef(pr: PullRequest): String = "refs/merge-requests/${pr.id}/head"

    override fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String =
        "${repoWebUrl(repo)}/-/merge_requests/new?${urlEncode("merge_request[source_branch]")}=${urlEncode(sourceBranch)}" +
            targetBranch?.let { "&${urlEncode("merge_request[target_branch]")}=${urlEncode(it)}" }.orEmpty()
}
