package io.github.purgator.gitops.core.provider

import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.model.Check
import io.github.purgator.gitops.core.model.CommentThread
import io.github.purgator.gitops.core.model.Issue
import io.github.purgator.gitops.core.model.IssueFilter
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.NewIssue
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PipelineDefinition
import io.github.purgator.gitops.core.model.PipelineJob
import io.github.purgator.gitops.core.model.PipelineRun
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.PullRequestUpdate
import io.github.purgator.gitops.core.model.RemoteRepository
import io.github.purgator.gitops.core.model.RepoInfo
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.util.GitUrl

/** What a hosting service can do; the UI hides everything a provider does not declare. */
enum class Capability {
    DRAFT_PR,
    REVIEWERS,
    LABELS,
    LINK_ISSUES,

    /** Linked issues can be transitioned when the pull request completes. */
    TRANSITION_ISSUES,
    AUTO_COMPLETE,
    REOPEN_PR,
    VOTE_SUGGESTIONS,
    VOTE_WAIT,
    VOTE_REJECT,
    VOTE_RESET,
    PIPELINES,
    RUN_PIPELINE,
    RERUN_PIPELINE,
    CANCEL_PIPELINE,
    JOB_LOGS,
    ISSUES,
    CREATE_ISSUE,
    ISSUE_TYPES,
    ISSUE_COMMENTS,
    REPOSITORIES,
}

/** The words a service uses for the same concepts. */
data class Terminology(
    val pullRequest: String = "Pull Request",
    val pullRequests: String = "Pull Requests",
    val pullRequestShort: String = "PR",
    val issue: String = "Issue",
    val issues: String = "Issues",
    val pipeline: String = "Pipeline",
    val pipelines: String = "Pipelines",
    val mergeVerb: String = "Merge",
    val closeVerb: String = "Close",
)

/** A resolved credential. [username] is empty for pure token schemes. */
data class Secret(val username: String, val token: String)

/** Lazily provides (and can refresh) the credential of one account. */
interface SecretSource {
    fun get(): Secret

    /** Drops any cached value. Returns true when a fresh credential may differ from the previous one. */
    fun invalidate(): Boolean
}

/** How an account can authenticate without the user pasting anything. */
data class CliLogin(
    /** Executable name, e.g. `gh`. */
    val tool: String,
    val displayName: String,
    /** Arguments printing the token on stdout. */
    val tokenArgs: (serverUrl: String) -> List<String>,
    val loginHint: String,
)

/**
 * A hosting service (GitHub, Azure DevOps, …). Stateless and cheap to create: all state lives in
 * accounts and in the [HostingClient]s created from them.
 */
interface HostingProvider {
    val id: String
    val displayName: String
    val defaultServerUrl: String
    val capabilities: Set<Capability>
    val terms: Terminology get() = Terminology()

    /** True when the service can be self-hosted, so the setup asks for a server URL. */
    val supportsCustomServer: Boolean get() = true

    /** True when an account is tied to an organisation that is part of the server URL (Azure DevOps). */
    val requiresOrganization: Boolean get() = false

    /** Short explanation of the token scopes shown in the setup. */
    val tokenScopesHint: String

    /** Whether the token form needs a user name (basic auth schemes). */
    val tokenNeedsUsername: Boolean get() = false

    val cliLogin: CliLogin? get() = null

    /** Page where the user creates a token, ideally with name and scopes pre-filled. */
    fun tokenCreationUrl(serverUrl: String): String

    /**
     * Resolves a remote URL to a repository of this service, or null when it is not one of ours.
     * [knownServers] are the server URLs of the configured accounts (for self-hosted instances).
     */
    fun parseRemote(url: GitUrl, knownServers: List<String>): RepoRef?

    /** True when [serverUrl] of an account can serve [repo]. */
    fun accountMatches(serverUrl: String, repo: RepoRef): Boolean =
        GitUrl.hostOf(serverUrl) == GitUrl.hostOf(repo.serverUrl)

    fun authHeaders(secret: Secret): Map<String, String>

    fun createClient(serverUrl: String, transport: HttpTransport, secrets: SecretSource): HostingClient

    /** Web URL of a file (optionally a line range) at a given ref. */
    fun fileUrl(repo: RepoRef, ref: String, path: String, lines: IntRange? = null): String

    /** Web URL of a commit. */
    fun commitUrl(repo: RepoRef, sha: String): String

    /** Git ref (fetchable from the target remote) holding the head of a pull request, if the service exposes one. */
    fun pullRequestHeadRef(pr: PullRequest): String? = null

    /** Page where a new pull request for [sourceBranch] can be created in the browser. */
    fun newPullRequestUrl(repo: RepoRef, sourceBranch: String, targetBranch: String?): String

    fun supports(capability: Capability): Boolean = capability in capabilities
}

/**
 * Remote operations for one account. Every method blocks and must be called off the UI thread.
 * Methods a provider does not support keep the default implementation; callers gate on [Capability].
 */
interface HostingClient {
    fun currentUser(): User

    fun repoInfo(repo: RepoRef): RepoInfo

    fun listRepositories(query: String): List<RemoteRepository> = unsupported()

    // Pull requests
    fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest>
    fun getPullRequest(repo: RepoRef, id: String): PullRequest
    fun findOpenPullRequest(repo: RepoRef, sourceBranch: String): PullRequest? =
        listPullRequests(repo, PrFilter.OPEN).firstOrNull { it.sourceBranch == sourceBranch && !it.fromFork }

    fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest
    fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest
    fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions)
    fun closePullRequest(repo: RepoRef, pr: PullRequest)
    fun reopenPullRequest(repo: RepoRef, pr: PullRequest): Unit = unsupported()
    fun cancelAutoComplete(repo: RepoRef, pr: PullRequest): Unit = unsupported()
    fun vote(repo: RepoRef, pr: PullRequest, vote: Vote)
    fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> = emptyList()
    fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread>
    fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String? = null)
    fun searchReviewers(repo: RepoRef, query: String): List<User> = emptyList()
    fun listLabels(repo: RepoRef): List<String> = emptyList()

    // Pipelines
    fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> = unsupported()
    fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> = unsupported()
    fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String): Unit = unsupported()
    fun rerunPipeline(repo: RepoRef, run: PipelineRun): Unit = unsupported()
    fun cancelPipeline(repo: RepoRef, run: PipelineRun): Unit = unsupported()
    fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> = unsupported()
    fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String = unsupported()

    // Issues / work items
    fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> = unsupported()
    fun getIssue(repo: RepoRef, id: String): Issue = unsupported()
    fun issueTypes(repo: RepoRef): List<String> = emptyList()
    fun createIssue(repo: RepoRef, issue: NewIssue): Issue = unsupported()

    /** States the issue can move to from where it is. */
    fun issueStates(repo: RepoRef, issue: Issue): List<String> = emptyList()
    fun setIssueState(repo: RepoRef, issue: Issue, state: String): Unit = unsupported()
    fun addIssueComment(repo: RepoRef, issue: Issue, body: String): Unit = unsupported()
}

class UnsupportedOperation : UnsupportedOperationException("This hosting service does not support this operation.")

fun unsupported(): Nothing = throw UnsupportedOperation()

/** Merge strategies offered by default, overridden per provider through [RepoInfo.mergeStrategies]. */
val DEFAULT_STRATEGIES: List<MergeStrategy> = listOf(MergeStrategy.MERGE, MergeStrategy.SQUASH, MergeStrategy.REBASE)
