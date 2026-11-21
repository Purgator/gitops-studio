package io.github.purgator.gitops.providers.gitea

import com.google.gson.JsonElement
import io.github.purgator.gitops.core.http.ApiClient
import io.github.purgator.gitops.core.http.HttpException
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.at
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.http.get
import io.github.purgator.gitops.core.http.instant
import io.github.purgator.gitops.core.http.int
import io.github.purgator.gitops.core.http.jsonOf
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.http.urlEncode
import io.github.purgator.gitops.core.model.Check
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.Comment
import io.github.purgator.gitops.core.model.CommentThread
import io.github.purgator.gitops.core.model.Issue
import io.github.purgator.gitops.core.model.IssueFilter
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStatus
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.NewIssue
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PipelineDefinition
import io.github.purgator.gitops.core.model.PipelineJob
import io.github.purgator.gitops.core.model.PipelineRun
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.PullRequestUpdate
import io.github.purgator.gitops.core.model.RemoteRepository
import io.github.purgator.gitops.core.model.RepoInfo
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.Reviewer
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.provider.DEFAULT_STRATEGIES
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.provider.unsupported
import java.io.IOException

/** Gitea / Forgejo REST API v1 client. [serverUrl] is the web base URL of the instance. */
class GiteaClient(
    serverUrl: String,
    transport: HttpTransport,
    secrets: SecretSource,
) : HostingClient {

    private val api = ApiClient(
        baseUrl = serverUrl.trimEnd('/') + "/api/v1",
        transport = transport,
        authHeaders = { GiteaProvider.authHeaders(secrets.get()) },
        onUnauthorized = { secrets.invalidate() },
    )

    @Volatile
    private var me: User? = null

    private fun repoPath(repo: RepoRef) = "repos/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"
    private fun prPath(repo: RepoRef, id: String) = "${repoPath(repo)}/pulls/$id"
    private fun issuePath(repo: RepoRef, id: String) = "${repoPath(repo)}/issues/$id"

    override fun currentUser(): User {
        me?.let { return it }
        return toUser(api.get("user")).also { me = it }
    }

    override fun repoInfo(repo: RepoRef): RepoInfo {
        val json = api.get(repoPath(repo))
        val strategies = buildList {
            if (json.bool("allow_merge_commits")) add(MergeStrategy.MERGE)
            if (json.bool("allow_squash_merge")) add(MergeStrategy.SQUASH)
            if (json.bool("allow_rebase")) add(MergeStrategy.REBASE)
            if (json.bool("allow_rebase_explicit")) add(MergeStrategy.REBASE_MERGE)
        }
        return RepoInfo(
            defaultBranch = json.str("default_branch").ifBlank { "main" },
            webUrl = json.str("html_url").ifBlank { GiteaProvider.repoWebUrl(repo) },
            mergeStrategies = strategies.ifEmpty { DEFAULT_STRATEGIES },
        )
    }

    override fun listRepositories(query: String): List<RemoteRepository> {
        val text = query.trim()
        val repos = if (text.isEmpty()) {
            api.get("user/repos", "limit" to PAGE).list()
        } else {
            api.get("repos/search", "q" to text, "limit" to PAGE).list("data")
        }
        return repos.map {
            RemoteRepository(
                fullName = it.str("full_name"),
                httpUrl = it.str("clone_url"),
                sshUrl = it.str("ssh_url"),
                webUrl = it.str("html_url"),
                description = it.str("description"),
                private = it.bool("private"),
            )
        }
    }

    // ---------------------------------------------------------------- pull requests

    override fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest> {
        val state = when (filter) {
            PrFilter.MINE, PrFilter.REVIEWING, PrFilter.OPEN -> "open"
            PrFilter.MERGED, PrFilter.CLOSED -> "closed"
            PrFilter.ALL -> "all"
        }
        val raw = api.get("${repoPath(repo)}/pulls", "state" to state, "sort" to "recentupdate", "limit" to PAGE).list()
        val selected = when (filter) {
            PrFilter.MINE -> currentUser().login.let { me -> raw.filter { it.str("user", "login") == me } }
            PrFilter.REVIEWING -> currentUser().login.let { me ->
                raw.filter { pr -> pr.list("requested_reviewers").any { it.str("login") == me } }
            }
            PrFilter.MERGED -> raw.filter { it.bool("merged") }
            PrFilter.CLOSED -> raw.filter { !it.bool("merged") }
            PrFilter.OPEN, PrFilter.ALL -> raw
        }
        return selected.map { toPullRequest(it) }
    }

    override fun getPullRequest(repo: RepoRef, id: String): PullRequest {
        val pr = toPullRequest(api.get(prPath(repo, id)))
        val reviews = runCatching { api.get("${prPath(repo, id)}/reviews").list() }.getOrDefault(emptyList())
        // The latest decisive review of each user is their vote.
        val votes = LinkedHashMap<String, Reviewer>()
        for (review in reviews) {
            if (review.bool("dismissed")) continue
            val vote = when (review.str("state")) {
                "APPROVED" -> Vote.APPROVED
                "REQUEST_CHANGES" -> Vote.REJECTED
                else -> continue
            }
            val user = review["user"] ?: continue
            votes[user.str("login")] = Reviewer(toUser(user), vote)
        }
        if (votes.isEmpty()) return pr
        // A re-requested reviewer has not voted on the current state: the pending request wins.
        val pending = pr.reviewers.map { it.user.login }.toSet()
        return pr.copy(reviewers = pr.reviewers + votes.values.filter { it.user.login !in pending })
    }

    override fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest {
        val description = buildString {
            append(request.description)
            if (request.linkedIssues.isNotEmpty()) {
                if (isNotBlank()) append("\n\n")
                append(request.linkedIssues.joinToString("\n") { "Closes #${it.trim().trimStart('#')}" })
            }
        }
        val body = jsonOf(
            "title" to if (request.draft) withDraftPrefix(request.title) else request.title,
            "body" to description,
            "head" to request.sourceBranch,
            "base" to request.targetBranch,
            "labels" to labelIds(repo, request.labels),
            "assignees" to emptyList<String>(),
        )
        val created = api.post("${repoPath(repo)}/pulls", body)
        val id = created.str("number")
        if (request.reviewers.isNotEmpty()) {
            runCatching {
                val logins = request.reviewers.map { it.login.ifBlank { it.id } }
                api.post("${prPath(repo, id)}/requested_reviewers", jsonOf("reviewers" to logins))
            }
        }
        request.autoComplete?.let { options ->
            runCatching { api.post("${prPath(repo, id)}/merge", mergeBody(options.copy(autoComplete = true))) }
        }
        return if (request.reviewers.isEmpty() && request.autoComplete == null) {
            toPullRequest(created)
        } else {
            runCatching { getPullRequest(repo, id) }.getOrElse { toPullRequest(created) }
        }
    }

    override fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest {
        // Draft is a title convention here, so the title must be rewritten whenever the flag changes.
        val draft = update.draft ?: pr.isDraft
        val title = if (update.title != null || update.draft != null) {
            val bare = stripDraftPrefix(update.title ?: pr.title)
            if (draft) withDraftPrefix(bare) else bare
        } else {
            null
        }
        val body = jsonOf("title" to title, "body" to update.description, "base" to update.targetBranch)
        return toPullRequest(api.patch(prPath(repo, pr.id), body))
    }

    override fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions) {
        api.post("${prPath(repo, pr.id)}/merge", mergeBody(options))
    }

    private fun mergeBody(options: MergeOptions) = jsonOf(
        "Do" to when (options.strategy) {
            MergeStrategy.MERGE -> "merge"
            MergeStrategy.SQUASH -> "squash"
            MergeStrategy.REBASE -> "rebase"
            MergeStrategy.REBASE_MERGE -> "rebase-merge"
        },
        "delete_branch_after_merge" to options.deleteSourceBranch,
        "MergeMessageField" to options.message?.takeIf { it.isNotBlank() },
        "merge_when_checks_succeed" to options.autoComplete,
    )

    override fun cancelAutoComplete(repo: RepoRef, pr: PullRequest) {
        api.delete("${prPath(repo, pr.id)}/merge")
    }

    override fun closePullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("state" to "closed"))
    }

    override fun reopenPullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("state" to "open"))
    }

    override fun vote(repo: RepoRef, pr: PullRequest, vote: Vote) {
        val body = when (vote) {
            Vote.APPROVED -> jsonOf("event" to "APPROVED", "body" to "")
            Vote.REJECTED -> jsonOf("event" to "REQUEST_CHANGES", "body" to "Changes requested.")
            else -> unsupported()
        }
        api.post("${prPath(repo, pr.id)}/reviews", body)
    }

    override fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> {
        val sha = pr.headSha?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { api.get("${repoPath(repo)}/commits/$sha/statuses", "limit" to PAGE).list() }
            .getOrDefault(emptyList())
            .groupBy { it.str("context") }
            .values.mapNotNull { group -> group.maxByOrNull { it.int("id") ?: 0 } }
            .map { status ->
                Check(
                    name = status.str("context").ifBlank { "status" },
                    state = when (status.str("status").ifBlank { status.str("state") }) {
                        "success", "warning" -> CheckState.SUCCESS
                        "failure", "error" -> CheckState.FAILURE
                        "pending" -> CheckState.PENDING
                        else -> CheckState.UNKNOWN
                    },
                    url = status.str("target_url").ifBlank { null },
                    description = status.str("description"),
                )
            }
    }

    override fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread> {
        val issueComments = api.get("${issuePath(repo, pr.id)}/comments").list().map {
            Comment(it.str("id"), toUser(it["user"]), it.str("body"), it.instant("created_at")) to "issue:${it.str("id")}"
        }
        val reviews = runCatching { api.get("${prPath(repo, pr.id)}/reviews").list() }.getOrDefault(emptyList())
            .filter { it.str("body").isNotBlank() }
            .map {
                Comment(it.str("id"), toUser(it["user"]), it.str("body"), it.instant("submitted_at")) to "review:${it.str("id")}"
            }
        return (issueComments + reviews)
            .sortedBy { it.first.createdAt }
            .map { (comment, id) -> CommentThread(id, listOf(comment)) }
    }

    /** Replying inside a review thread is not exposed by the API: every comment lands in the conversation. */
    override fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String?) {
        api.post("${issuePath(repo, pr.id)}/comments", jsonOf("body" to body))
    }

    override fun searchReviewers(repo: RepoRef, query: String): List<User> {
        val text = query.trim()
        fun matches(user: User) = text.isEmpty() || user.login.contains(text, true) || user.name.contains(text, true)
        val collaborators = runCatching {
            val owner = runCatching { toUser(api.get("users/${urlEncode(repo.owner)}")) }.getOrNull()
            (api.get("${repoPath(repo)}/collaborators", "limit" to PAGE).list().map { toUser(it) } + listOfNotNull(owner))
                .filter { it.login.isNotBlank() && matches(it) }
                .distinctBy { it.login }
        }.getOrDefault(emptyList())
        if (collaborators.isNotEmpty() || text.isEmpty()) return collaborators
        return api.get("users/search", "q" to text, "limit" to 20).list("data").map { toUser(it) }
    }

    override fun listLabels(repo: RepoRef): List<String> =
        api.get("${repoPath(repo)}/labels", "limit" to PAGE).list().map { it.str("name") }

    /** The API takes label ids; names the repository does not know are dropped. */
    private fun labelIds(repo: RepoRef, names: List<String>): List<Long> {
        if (names.isEmpty()) return emptyList()
        val wanted = names.map { it.trim().lowercase() }.toSet()
        return runCatching { api.get("${repoPath(repo)}/labels", "limit" to PAGE).list() }.getOrDefault(emptyList())
            .filter { it.str("name").lowercase() in wanted }
            .mapNotNull { it.str("id").toLongOrNull() }
    }

    private fun toPullRequest(json: JsonElement): PullRequest {
        val title = json.str("title")
        val open = json.str("state") == "open"
        val state = when {
            json.bool("merged") -> PrState.MERGED
            !open -> PrState.CLOSED
            json.bool("draft") || hasDraftPrefix(title) -> PrState.DRAFT
            else -> PrState.OPEN
        }
        val headRepo = json.str("head", "repo", "full_name")
        return PullRequest(
            id = json.str("number"),
            title = title,
            description = json.str("body"),
            state = state,
            author = toUser(json["user"]),
            sourceBranch = json.str("head", "ref"),
            targetBranch = json.str("base", "ref"),
            webUrl = json.str("html_url"),
            createdAt = json.instant("created_at"),
            updatedAt = json.instant("updated_at"),
            reviewers = json.list("requested_reviewers").map { Reviewer(toUser(it)) },
            labels = json.list("labels").map { it.str("name") },
            mergeStatus = when {
                !open || json["mergeable"] == null -> MergeStatus.UNKNOWN
                json.bool("mergeable") -> MergeStatus.CLEAN
                else -> MergeStatus.CONFLICTS
            },
            commentCount = json.int("comments"),
            headSha = json.str("head", "sha").ifBlank { null },
            // A deleted fork has no head repository at all.
            fromFork = headRepo != json.str("base", "repo", "full_name"),
        )
    }

    private fun toUser(json: JsonElement?): User {
        val login = json.str("login").ifBlank { json.str("username") }
        return User(login, json.str("full_name").ifBlank { login }, login, json.str("avatar_url").ifBlank { null })
    }

    // ---------------------------------------------------------------- actions

    /** Instances without Actions (or repositories with Actions off) answer 404. */
    private fun <T> actions(call: () -> T): T = try {
        call()
    } catch (e: HttpException) {
        if (e.notFound) throw IOException("Actions are not available on this server or repository.", e) else throw e
    }

    override fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> = actions {
        api.get("${repoPath(repo)}/actions/runs", "branch" to branch, "limit" to PAGE).list("workflow_runs").map { run ->
            val name = run.str("name").ifBlank { run.str("display_title") }
            val number = run.str("run_number")
            PipelineRun(
                id = run.str("id"),
                name = if (number.isEmpty()) name else "$name #$number",
                state = runState(run.str("status"), run.str("conclusion")),
                branch = run.str("head_branch"),
                webUrl = run.str("html_url"),
                title = run.str("display_title").lineSequence().firstOrNull().orEmpty(),
                commitSha = run.str("head_sha"),
                triggeredBy = run.str("actor", "login"),
                definitionId = run.str("workflow_id"),
                startedAt = run.instant("run_started_at") ?: run.instant("started_at") ?: run.instant("created_at"),
                finishedAt = (run.instant("completed_at") ?: run.instant("updated_at")).takeIf { run.str("status") == "completed" },
            )
        }
    }

    override fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> = actions {
        api.get("${repoPath(repo)}/actions/workflows").list("workflows")
            .map { PipelineDefinition(it.str("id"), it.str("name").ifBlank { it.str("path").substringAfterLast('/') }) }
            .sortedBy { it.name.lowercase() }
    }

    override fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String) {
        actions {
            api.post("${repoPath(repo)}/actions/workflows/${urlEncode(definition.id)}/dispatches", jsonOf("ref" to branch))
        }
    }

    override fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> = actions {
        api.get("${repoPath(repo)}/actions/runs/${run.id}/jobs", "limit" to PAGE).list("jobs").map { job ->
            PipelineJob(
                id = job.str("id"),
                name = job.str("name"),
                state = runState(job.str("status"), job.str("conclusion")),
                webUrl = job.str("html_url").ifBlank { run.webUrl },
                logRef = job.str("id").ifBlank { null },
                startedAt = job.instant("started_at"),
                finishedAt = job.instant("completed_at"),
            )
        }
    }

    override fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String {
        val log = job.logRef ?: return "No log is available for this job yet."
        return actions { api.getText("${repoPath(repo)}/actions/jobs/$log/logs") }
    }

    // ---------------------------------------------------------------- issues

    override fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> {
        val me by lazy { currentUser().login }
        return api.get(
            "${repoPath(repo)}/issues",
            "type" to "issues",
            "state" to if (filter == IssueFilter.ALL) "all" else "open",
            "q" to query.trim().ifEmpty { null },
            "assigned_by" to if (filter == IssueFilter.ASSIGNED_TO_ME) me else null,
            "created_by" to if (filter == IssueFilter.CREATED_BY_ME) me else null,
            "limit" to PAGE,
        ).list().filter { it["pull_request"] == null }.map { toIssue(it) }
    }

    override fun getIssue(repo: RepoRef, id: String): Issue = toIssue(api.get(issuePath(repo, id)))

    override fun createIssue(repo: RepoRef, issue: NewIssue): Issue {
        val body = jsonOf(
            "title" to issue.title,
            "body" to issue.description,
            "assignees" to if (issue.assignToMe) listOf(currentUser().login) else null,
            "labels" to labelIds(repo, issue.labels).ifEmpty { null },
        )
        return toIssue(api.post("${repoPath(repo)}/issues", body))
    }

    override fun issueStates(repo: RepoRef, issue: Issue): List<String> = listOf(if (issue.open) "closed" else "open")

    override fun setIssueState(repo: RepoRef, issue: Issue, state: String) {
        api.patch(issuePath(repo, issue.id), jsonOf("state" to state))
    }

    override fun addIssueComment(repo: RepoRef, issue: Issue, body: String) {
        api.post("${issuePath(repo, issue.id)}/comments", jsonOf("body" to body))
    }

    private fun toIssue(json: JsonElement): Issue {
        val state = json.str("state")
        return Issue(
            id = json.str("number"),
            title = json.str("title"),
            description = json.str("body"),
            state = state,
            open = state == "open",
            webUrl = json.str("html_url"),
            type = "Issue",
            author = json["user"]?.let { toUser(it) },
            assignee = (json["assignee"] ?: json.list("assignees").firstOrNull())?.let { toUser(it) },
            labels = json.list("labels").map { it.str("name") },
            updatedAt = json.instant("updated_at"),
        )
    }

    companion object {
        private const val PAGE = 50
        private const val DRAFT_PREFIX = "WIP: "
        private val DRAFT_PATTERN = Regex("""^\s*(?:\[wip]|wip:|draft:)\s*""", RegexOption.IGNORE_CASE)

        internal fun hasDraftPrefix(title: String): Boolean = DRAFT_PATTERN.containsMatchIn(title)

        internal fun stripDraftPrefix(title: String): String {
            var current = title
            while (DRAFT_PATTERN.containsMatchIn(current)) current = DRAFT_PATTERN.replaceFirst(current, "")
            return current
        }

        internal fun withDraftPrefix(title: String): String = if (hasDraftPrefix(title)) title else DRAFT_PREFIX + title

        /** Same vocabulary as GitHub Actions. */
        internal fun runState(status: String, conclusion: String): CheckState = when (status) {
            "completed" -> conclusionState(conclusion)
            "in_progress", "running" -> CheckState.RUNNING
            "queued", "waiting", "pending", "blocked", "requested" -> CheckState.PENDING
            // Older servers put the outcome straight into `status`.
            else -> conclusionState(status)
        }

        private fun conclusionState(conclusion: String): CheckState = when (conclusion) {
            "success" -> CheckState.SUCCESS
            "failure", "timed_out", "startup_failure" -> CheckState.FAILURE
            "cancelled" -> CheckState.CANCELLED
            "skipped" -> CheckState.SKIPPED
            else -> CheckState.UNKNOWN
        }
    }
}
