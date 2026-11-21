package io.github.purgator.gitops.providers.github

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

class GitHubModule : GitOpsModule {
    override val provider: HostingProvider = GitHubProvider
}

object GitHubProvider : HostingProvider {
    private const val PUBLIC_HOST = "github.com"

    override val id = "github"
    override val displayName = "GitHub"
    override val defaultServerUrl = "https://github.com"
    override val tokenScopesHint =
        "Classic token scopes: repo, workflow, read:org. Fine-grained token: Pull requests, Issues, Actions and Contents (read & write)."

    override val capabilities = setOf(
        Capability.DRAFT_PR, Capability.REVIEWERS, Capability.LABELS, Capability.LINK_ISSUES, Capability.AUTO_COMPLETE,
        Capability.REOPEN_PR, Capability.VOTE_REJECT,
        Capability.PIPELINES, Capability.RUN_PIPELINE, Capability.RERUN_PIPELINE, Capability.CANCEL_PIPELINE, Capability.JOB_LOGS,
        Capability.ISSUES, Capability.CREATE_ISSUE, Capability.ISSUE_COMMENTS, Capability.REPOSITORIES,
    )

    override val terms = Terminology(pipeline = "Workflow run", pipelines = "Actions")

    override val cliLogin = CliLogin(
        tool = "gh",
        displayName = "GitHub CLI",
        tokenArgs = { server -> listOf("auth", "token", "--hostname", GitUrl.hostOf(server).ifBlank { PUBLIC_HOST }) },
        loginHint = "Run `gh auth login` in a terminal first.",
    )

    private fun server(serverUrl: String): String = GitUrl.normalizeServerUrl(serverUrl).ifBlank { defaultServerUrl }

    internal fun isPublic(serverUrl: String): Boolean = GitUrl.hostOf(server(serverUrl)).let { it == PUBLIC_HOST || it == "www.$PUBLIC_HOST" }

    /** REST base: `api.github.com` for the public service, `/api/v3` on GitHub Enterprise Server. */
    internal fun apiBase(serverUrl: String): String =
        if (isPublic(serverUrl)) "https://api.github.com" else "${server(serverUrl)}/api/v3"

    internal fun graphqlUrl(serverUrl: String): String =
        if (isPublic(serverUrl)) "https://api.github.com/graphql" else "${server(serverUrl)}/api/graphql"

    override fun tokenCreationUrl(serverUrl: String): String =
        "${server(serverUrl)}/settings/tokens/new?description=GitOps%20Studio&scopes=repo,workflow,read:org"

    override fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef? {
        val seg = url.segments
        if (seg.size < 2) return null
        // `ssh.github.com` is the SSH-over-443 endpoint of the public service.
        val host = if (url.host == "ssh.$PUBLIC_HOST") PUBLIC_HOST else url.host
        val base = when {
            host == PUBLIC_HOST -> defaultServerUrl
            else -> knownServers.firstOrNull { GitUrl.hostOf(it) == host }?.let { GitUrl.normalizeServerUrl(it) } ?: return null
        }
        return RepoRef(id, base, seg[0], seg[1])
    }

    override fun authHeaders(secret: Secret): Map<String, String> = mapOf("Authorization" to "Bearer ${secret.token}")

    override fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient =
        GitHubClient(server(serverUrl), transport, secrets)

    internal fun repoWebUrl(repo: RepoRef): String =
        "${repo.serverUrl.trimEnd('/')}/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"

    override fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange?): String {
        val anchor = lines?.let {
            val first = it.first + 1
            val last = it.last + 1
            if (last > first) "#L$first-L$last" else "#L$first"
        }.orEmpty()
        return "${repoWebUrl(repo)}/blob/${encodePath(ref)}/${encodePath(path.trimStart('/'))}$anchor"
    }

    override fun commitUrl(repo: RepoRef, sha: String): String = "${repoWebUrl(repo)}/commit/$sha"

    override fun pullRequestHeadRef(pr: PullRequest): String = "refs/pull/${pr.id}/head"

    override fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String {
        val range = if (targetBranch == null) encodePath(sourceBranch) else "${encodePath(targetBranch)}...${encodePath(sourceBranch)}"
        return "${repoWebUrl(repo)}/compare/$range?expand=1"
    }
}
