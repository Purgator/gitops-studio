package io.github.purgator.gitops.providers.azure

import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.encodePath
import io.github.purgator.gitops.core.http.urlEncode
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class AzureDevOpsModule : GitOpsModule {
    override val provider: HostingProvider = AzureDevOpsProvider
}

object AzureDevOpsProvider : HostingProvider {
    /** Resource id of Azure DevOps, the audience of tokens issued by `az`. */
    private const val RESOURCE_ID = "499b84ac-1321-427f-aa17-267ca6975798"

    override val id = "azure"
    override val displayName = "Azure DevOps"
    override val defaultServerUrl = "https://dev.azure.com/"
    override val requiresOrganization = true
    override val tokenScopesHint = "Scopes: Code (read & write), Build (read & execute), Work Items (read & write), Identity (read)."

    override val capabilities = setOf(
        Capability.DRAFT_PR, Capability.REVIEWERS, Capability.LABELS, Capability.LINK_ISSUES, Capability.TRANSITION_ISSUES, Capability.AUTO_COMPLETE,
        Capability.REOPEN_PR, Capability.VOTE_SUGGESTIONS, Capability.VOTE_WAIT, Capability.VOTE_REJECT, Capability.VOTE_RESET,
        Capability.PIPELINES, Capability.RUN_PIPELINE, Capability.RERUN_PIPELINE, Capability.CANCEL_PIPELINE, Capability.JOB_LOGS,
        Capability.ISSUES, Capability.CREATE_ISSUE, Capability.ISSUE_TYPES, Capability.ISSUE_COMMENTS, Capability.REPOSITORIES,
    )

    override val terms = Terminology(
        issue = "Work Item",
        issues = "Work Items",
        mergeVerb = "Complete",
        closeVerb = "Abandon",
    )

    override val cliLogin = CliLogin(
        tool = "az",
        displayName = "Azure CLI",
        tokenArgs = { listOf("account", "get-access-token", "--resource", RESOURCE_ID, "--query", "accessToken", "-o", "tsv") },
        loginHint = "Run `az login` in a terminal first.",
    )

    override fun tokenCreationUrl(serverUrl: String): String {
        val base = GitUrl.normalizeServerUrl(serverUrl)
        // Without an organisation there is no token page to open; send the user to the portal.
        return if (organizationKey(base).substringAfter(':').isBlank() || base.trimEnd('/') == "https://dev.azure.com") {
            "https://dev.azure.com/"
        } else {
            "$base/_usersSettings/tokens"
        }
    }

    override fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef? {
        // Remote URLs carry percent-encoded names ("My%20Project"); the model holds the real ones.
        val seg = url.segments.map(::decode)
        val host = url.host
        return when {
            // git@ssh.dev.azure.com:v3/org/project/repo and org@vs-ssh.visualstudio.com:v3/org/project/repo
            (host == "ssh.dev.azure.com" || host == "vs-ssh.visualstudio.com") && seg.size >= 4 && seg[0] == "v3" ->
                RepoRef(id, "https://dev.azure.com/${seg[1]}", seg[1], seg[3], seg[2])

            host == "dev.azure.com" -> {
                val git = seg.indexOf("_git")
                if (git < 2 || git + 1 >= seg.size) return null
                RepoRef(id, "https://dev.azure.com/${seg[0]}", seg[0], seg[git + 1], seg[git - 1])
            }

            host.endsWith(".visualstudio.com") -> {
                val org = host.substringBefore('.')
                val git = seg.indexOf("_git")
                if (git < 0 || git + 1 >= seg.size) return null
                val repo = seg[git + 1]
                // `https://org.visualstudio.com/_git/repo` is the default repository of the project of the same name.
                val project = seg.getOrNull(git - 1)?.takeUnless { it.equals("DefaultCollection", true) } ?: repo
                RepoRef(id, "https://$host", org, repo, project)
            }

            // Azure DevOps Server / TFS: https://server[:port]/[tfs/]Collection/project/_git/repo
            else -> {
                val git = seg.indexOf("_git")
                if (git < 1 || git + 1 >= seg.size) return null
                val collection = seg.subList(0, git - 1)
                val base = knownServers.firstOrNull { GitUrl.hostOf(it) == host && it.trimEnd('/').endsWith(collection.joinToString("/"), true) }
                    ?: (listOf(url.webBase) + collection).joinToString("/")
                RepoRef(id, base.trimEnd('/'), collection.lastOrNull() ?: host, seg[git + 1], seg[git - 1])
            }
        }
    }

    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(segment)

    override fun accountMatches(serverUrl: String, repo: RepoRef): Boolean =
        organizationKey(serverUrl) == organizationKey(repo.serverUrl)

    /** `dev.azure.com/acme` and `acme.visualstudio.com` are the same organisation. */
    internal fun organizationKey(serverUrl: String): String {
        val url = GitUrl.normalizeServerUrl(serverUrl)
        val host = GitUrl.hostOf(url)
        val path = url.substringAfter("://").substringAfter('/', "").trim('/').lowercase()
        return when {
            host == "dev.azure.com" -> "cloud:" + path.substringBefore('/')
            host.endsWith(".visualstudio.com") -> "cloud:" + host.substringBefore('.')
            else -> "$host:$path"
        }
    }

    override fun authHeaders(secret: Secret): Map<String, String> {
        // Entra ID access tokens (from `az` or the git credential manager) are JWTs and go as Bearer;
        // personal access tokens use basic auth with an empty user name.
        val jwt = secret.token.startsWith("eyJ") && secret.token.count { it == '.' } == 2
        val value = if (jwt) {
            "Bearer ${secret.token}"
        } else {
            "Basic " + Base64.getEncoder().encodeToString(":${secret.token}".toByteArray())
        }
        return mapOf("Authorization" to value)
    }

    override fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient =
        AzureDevOpsClient(GitUrl.normalizeServerUrl(serverUrl), transport, secrets)

    internal fun repoWebUrl(repo: RepoRef): String =
        "${repo.serverUrl.trimEnd('/')}/${urlEncode(repo.project)}/_git/${urlEncode(repo.name)}"

    override fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange?): String {
        val version = if (ref.matches(Regex("[0-9a-f]{40}"))) "GC$ref" else "GB$ref"
        // Lines are zero-based and inclusive; the range ends at column 1 of the line after the last one.
        val range = lines?.let { "&line=${it.first + 1}&lineEnd=${it.last + 2}&lineStartColumn=1&lineEndColumn=1&lineStyle=plain" }.orEmpty()
        return "${repoWebUrl(repo)}?path=${urlEncode("/" + path.trimStart('/'))}&version=${urlEncode(version)}$range&_a=contents"
    }

    override fun commitUrl(repo: RepoRef, sha: String): String = "${repoWebUrl(repo)}/commit/$sha"

    override fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String =
        "${repoWebUrl(repo)}/pullrequestcreate?sourceRef=${urlEncode(sourceBranch)}" +
            targetBranch?.let { "&targetRef=${urlEncode(it)}" }.orEmpty()

    internal fun projectPath(repo: RepoRef): String = encodePath(repo.project)
}
