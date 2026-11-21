package io.github.purgator.gitops.providers.bitbucket

import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.encodePath
import io.github.purgator.gitops.core.http.urlEncode
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.modules.GitOpsModule
import io.github.purgator.gitops.core.provider.Capability
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.provider.Terminology
import io.github.purgator.gitops.core.util.GitUrl
import java.util.Base64

class BitbucketModule : GitOpsModule {
    override val provider: HostingProvider = BitbucketProvider
}

/** Bitbucket Cloud. Bitbucket Data Center speaks another API and is not covered. */
object BitbucketProvider : HostingProvider {
    const val HOST = "bitbucket.org"
    const val WEB_URL = "https://bitbucket.org"
    const val API_URL = "https://api.bitbucket.org/2.0"

    override val id = "bitbucket"
    override val displayName = "Bitbucket"
    override val defaultServerUrl = WEB_URL
    override val supportsCustomServer = false
    override val tokenNeedsUsername = true
    override val tokenScopesHint =
        "API token with scopes: read/write for repositories, pull requests, pipelines and issues. " +
            "Use your Atlassian e-mail as user name; leave the user name empty for a repository or workspace access token."

    override val capabilities = setOf(
        Capability.DRAFT_PR, Capability.REVIEWERS, Capability.VOTE_REJECT, Capability.VOTE_RESET,
        Capability.PIPELINES, Capability.RUN_PIPELINE, Capability.CANCEL_PIPELINE, Capability.JOB_LOGS,
        Capability.ISSUES, Capability.CREATE_ISSUE, Capability.ISSUE_TYPES, Capability.ISSUE_COMMENTS,
        Capability.REPOSITORIES,
    )

    override val terms = Terminology(closeVerb = "Decline")

    override fun tokenCreationUrl(serverUrl: String): String = "https://id.atlassian.com/manage-profile/security/api-tokens"

    override fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef? {
        if (url.host != HOST) return null
        val seg = url.segments
        if (seg.size < 2) return null
        return RepoRef(id, WEB_URL, seg[0], seg[1])
    }

    override fun authHeaders(secret: Secret): Map<String, String> {
        // E-mail + API token (or user name + app password) is basic auth; access tokens are bearer tokens.
        val value = if (secret.username.isNotBlank()) {
            "Basic " + Base64.getEncoder().encodeToString("${secret.username}:${secret.token}".toByteArray())
        } else {
            "Bearer ${secret.token}"
        }
        return mapOf("Authorization" to value)
    }

    override fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient =
        BitbucketClient(serverUrl, transport, secrets)

    internal fun repoWebUrl(repo: RepoRef): String = "$WEB_URL/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"

    override fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange?): String {
        val anchor = lines?.let {
            val first = it.first + 1
            val last = it.last + 1
            if (last > first) "#lines-$first:$last" else "#lines-$first"
        }.orEmpty()
        return "${repoWebUrl(repo)}/src/${encodePath(ref)}/${encodePath(path.trimStart('/'))}$anchor"
    }

    override fun commitUrl(repo: RepoRef, sha: String): String = "${repoWebUrl(repo)}/commits/$sha"

    override fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String =
        "${repoWebUrl(repo)}/pull-requests/new?source=${urlEncode(sourceBranch)}" +
            targetBranch?.let { "&dest=${urlEncode(it)}" }.orEmpty() + "&t=1"
}
