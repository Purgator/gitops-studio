package io.github.purgator.gitops.providers.github

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.purgator.gitops.core.http.ApiClient
import io.github.purgator.gitops.core.http.HttpException
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.http.encodePath
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
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.provider.unsupported
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** GitHub REST v3 client (github.com and GitHub Enterprise Server), with GraphQL for what REST cannot do. */
class GitHubClient(
    private val serverUrl: String,
    transport: HttpTransport,
    secrets: SecretSource,
) : HostingClient {

    private val api = ApiClient(
        baseUrl = GitHubProvider.apiBase(serverUrl),
        transport = transport,
        defaultHeaders = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
        authHeaders = { GitHubProvider.authHeaders(secrets.get()) },
        onUnauthorized = { secrets.invalidate() },
    )

    @Volatile
    private var me: User? = null
    private val collaborators = ConcurrentHashMap<RepoRef, List<User>>()

    private fun repoPath(repo: RepoRef) = "repos/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"
    private fun prPath(repo: RepoRef, id: String) = "${repoPath(repo)}/pulls/$id"
    private fun slug(repo: RepoRef) = "${repo.owner}/${repo.name}"

    override fun currentUser(): User {
        me?.let { return it }
        return toUser(api.get("user")).also { me = it }
    }

    override fun repoInfo(repo: RepoRef): RepoInfo {
        val json = api.get(repoPath(repo))
        val strategies = buildList {
            if (json.bool("allow_merge_commit")) add(MergeStrategy.MERGE)
            if (json.bool("allow_squash_merge")) add(MergeStrategy.SQUASH)
            if (json.bool("allow_rebase_merge")) add(MergeStrategy.REBASE)
        }.ifEmpty { STRATEGIES }
        return RepoInfo(
            defaultBranch = json.str("default_branch").ifBlank { "main" },
            webUrl = json.str("html_url").ifBlank { GitHubProvider.repoWebUrl(repo) },
            mergeStrategies = strategies,
        )
    }

    override fun listRepositories(query: String): List<RemoteRepository> {
        val mine = api.get(
            "user/repos",
            "sort" to "pushed",
            "per_page" to 100,
            "affiliation" to "owner,collaborator,organization_member",
        ).list().map(::toRepository)
        val text = query.trim()
        if (text.isEmpty()) return mine
        return mine.filter { it.fullName.contains(text, ignoreCase = true) }.ifEmpty {
            api.get("search/repositories", "q" to text, "per_page" to 30).list("items").map(::toRepository)
        }
    }

    private fun toRepository(json: JsonElement) = RemoteRepository(
        fullName = json.str("full_name"),
        httpUrl = json.str("clone_url"),
        sshUrl = json.str("ssh_url"),
        webUrl = json.str("html_url"),
        description = json.str("description"),
        private = json.bool("private"),
    )

    // ---------------------------------------------------------------- pull requests

    override fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest> {
        if (filter == PrFilter.MINE || filter == PrFilter.REVIEWING) {
            val who = if (filter == PrFilter.MINE) "author:@me" else "review-requested:@me"
            // Search hits are issue-shaped (no branches), so each one is completed with its pull request.
            return api.get("search/issues", "q" to "repo:${slug(repo)} is:pr is:open $who", "per_page" to SEARCH_CAP)
                .list("items").take(SEARCH_CAP)
                .mapNotNull { hit -> runCatching { getPullRequest(repo, hit.str("number")) }.getOrNull() }
        }
        val state = when (filter) {
            PrFilter.OPEN -> "open"
            PrFilter.MERGED, PrFilter.CLOSED -> "closed"
            else -> "all"
        }
        val all = api.get(
            "${repoPath(repo)}/pulls",
            "state" to state,
            "sort" to "updated",
            "direction" to "desc",
            "per_page" to PAGE,
        ).list().map { toPullRequest(it) }
        return when (filter) {
            PrFilter.MERGED -> all.filter { it.state == PrState.MERGED }
            PrFilter.CLOSED -> all.filter { it.state == PrState.CLOSED }
            else -> all
        }
    }

    override fun getPullRequest(repo: RepoRef, id: String): PullRequest {
        val json = api.get(prPath(repo, id))
        val reviews = runCatching { api.get("${prPath(repo, id)}/reviews", "per_page" to 100).list() }.getOrDefault(emptyList())
        return toPullRequest(json, reviews)
    }

    override fun findOpenPullRequest(repo: RepoRef, sourceBranch: String): PullRequest? =
        api.get("${repoPath(repo)}/pulls", "state" to "open", "head" to "${repo.owner}:$sourceBranch", "per_page" to 1)
            .list().firstOrNull()?.let { toPullRequest(it) }

    override fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest {
        val links = request.linkedIssues.map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.joinToString("\n") { "Closes #$it" }
        val description = listOf(request.description.trimEnd(), links).filter { it.isNotBlank() }.joinToString("\n\n")
        val body = jsonOf(
            "title" to request.title,
            "body" to description,
            "head" to request.sourceBranch,
            "base" to request.targetBranch,
            "draft" to request.draft,
        )
        val created = api.post("${repoPath(repo)}/pulls", body)
        val number = created.str("number")
        // Reviewers and labels are best effort: the pull request exists either way.
        if (request.reviewers.isNotEmpty()) {
            runCatching {
                api.post("${prPath(repo, number)}/requested_reviewers", jsonOf("reviewers" to request.reviewers.map { it.login.ifBlank { it.id } }))
            }
        }
        if (request.labels.isNotEmpty()) {
            runCatching { api.post("${repoPath(repo)}/issues/$number/labels", jsonOf("labels" to request.labels)) }
        }
        request.autoComplete?.let { enableAutoMerge(created.str("node_id"), it) }
        return if (request.reviewers.isEmpty() && request.labels.isEmpty() && request.autoComplete == null) {
            toPullRequest(created)
        } else {
            runCatching { getPullRequest(repo, number) }.getOrElse { toPullRequest(created) }
        }
    }

    override fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest {
        val body = jsonOf("title" to update.title, "body" to update.description, "base" to update.targetBranch)
        if (body.size() > 0) api.patch(prPath(repo, pr.id), body)
        val draft = update.draft
        if (draft != null && draft != pr.isDraft) {
            // Draft state can only be changed through GraphQL.
            val mutation = if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"
            graphql(
                "mutation(\$id: ID!) { $mutation(input: {pullRequestId: \$id}) { clientMutationId } }",
                jsonOf("id" to nodeId(repo, pr)),
            )
        }
        return getPullRequest(repo, pr.id)
    }

    override fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions) {
        if (options.autoComplete) {
            enableAutoMerge(nodeId(repo, pr), options)
            return
        }
        val body = jsonOf(
            "merge_method" to restMethod(options.strategy),
            "commit_message" to options.message?.takeIf { it.isNotBlank() },
        )
        api.put("${prPath(repo, pr.id)}/merge", body)
        if (options.deleteSourceBranch && !pr.fromFork) {
            runCatching { api.delete("${repoPath(repo)}/git/refs/heads/${encodePath(pr.sourceBranch)}") }
        }
    }

    private fun restMethod(strategy: MergeStrategy) = when (strategy) {
        MergeStrategy.MERGE -> "merge"
        MergeStrategy.SQUASH -> "squash"
        MergeStrategy.REBASE, MergeStrategy.REBASE_MERGE -> "rebase"
    }

    private fun enableAutoMerge(nodeId: String, options: MergeOptions) {
        graphql(
            "mutation(\$id: ID!, \$method: PullRequestMergeMethod!, \$body: String) { " +
                "enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: \$method, commitBody: \$body}) { clientMutationId } }",
            jsonOf(
                "id" to nodeId,
                "method" to restMethod(options.strategy).uppercase(),
                "body" to options.message?.takeIf { it.isNotBlank() },
            ),
        )
    }

    override fun cancelAutoComplete(repo: RepoRef, pr: PullRequest) {
        graphql(
            "mutation(\$id: ID!) { disablePullRequestAutoMerge(input: {pullRequestId: \$id}) { clientMutationId } }",
            jsonOf("id" to nodeId(repo, pr)),
        )
    }

    private fun nodeId(repo: RepoRef, pr: PullRequest): String =
        api.get(prPath(repo, pr.id)).str("node_id").ifBlank { throw IOException("GitHub did not return the pull request id.") }

    /** GraphQL answers `200` even on failure; errors come in the body. */
    private fun graphql(query: String, variables: JsonObject): JsonElement {
        val response = api.post(GitHubProvider.graphqlUrl(serverUrl), jsonOf("query" to query, "variables" to variables))
        val error = response.list("errors").firstOrNull()?.str("message")
        if (!error.isNullOrBlank()) throw IOException(error)
        return response["data"] ?: response
    }

    override fun closePullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("state" to "closed"))
    }

    override fun reopenPullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("state" to "open"))
    }

    override fun vote(repo: RepoRef, pr: PullRequest, vote: Vote) {
        val body = when (vote) {
            Vote.APPROVED -> jsonOf("event" to "APPROVE")
            Vote.REJECTED -> jsonOf("event" to "REQUEST_CHANGES", "body" to "Changes requested.")
            else -> unsupported()
        }
        api.post("${prPath(repo, pr.id)}/reviews", body)
    }

    override fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> {
        val sha = pr.headSha ?: return emptyList()
        val commit = "${repoPath(repo)}/commits/$sha"
        val checks = ArrayList<Check>()
        runCatching { api.get("$commit/check-runs", "per_page" to 100).list("check_runs") }.getOrDefault(emptyList()).forEach { run ->
            checks += Check(
                name = run.str("name"),
                state = runState(run.str("status"), run.str("conclusion")),
                url = run.str("html_url").ifBlank { run.str("details_url") }.ifBlank { null },
                description = run.str("output", "title"),
            )
        }
        // Legacy commit statuses posted by external services.
        runCatching { api.get("$commit/status").list("statuses") }.getOrDefault(emptyList()).forEach { status ->
            checks += Check(
                name = status.str("context"),
                state = when (status.str("state")) {
                    "success" -> CheckState.SUCCESS
                    "failure", "error" -> CheckState.FAILURE
                    "pending" -> CheckState.PENDING
                    else -> CheckState.UNKNOWN
                },
                url = status.str("target_url").ifBlank { null },
                description = status.str("description"),
            )
        }
        return checks
    }

    override fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread> {
        val threads = ArrayList<CommentThread>()
        api.get("${repoPath(repo)}/issues/${pr.id}/comments", "per_page" to 100).list().forEach {
            threads += CommentThread("$ISSUE_THREAD${it.str("id")}", listOf(toComment(it)))
        }
        runCatching { api.get("${prPath(repo, pr.id)}/reviews", "per_page" to 100).list() }.getOrDefault(emptyList())
            .filter { it.str("body").isNotBlank() }
            .forEach {
                val comment = Comment(it.str("id"), toUser(it["user"]), it.str("body"), it.instant("submitted_at"))
                threads += CommentThread("$REVIEW_BODY_THREAD${it.str("id")}", listOf(comment))
            }
        val reviewComments = runCatching { api.get("${prPath(repo, pr.id)}/comments", "per_page" to 100).list() }.getOrDefault(emptyList())
        val ids = reviewComments.map { it.str("id") }.toSet()
        reviewComments.groupBy { c -> c.str("in_reply_to_id").takeIf { it in ids } ?: c.str("id") }.forEach { (rootId, group) ->
            val root = group.firstOrNull { it.str("id") == rootId } ?: group.first()
            threads += CommentThread(
                id = "$REVIEW_THREAD$rootId",
                comments = group.sortedBy { it.str("created_at") }.map(::toComment),
                filePath = root.str("path").ifBlank { null },
                line = root.int("line") ?: root.int("original_line"),
            )
        }
        return threads.sortedBy { it.comments.firstOrNull()?.createdAt ?: java.time.Instant.MAX }
    }

    private fun toComment(json: JsonElement) =
        Comment(json.str("id"), toUser(json["user"]), json.str("body"), json.instant("created_at"))

    override fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String?) {
        if (threadId != null && threadId.startsWith(REVIEW_THREAD)) {
            api.post("${prPath(repo, pr.id)}/comments/${threadId.removePrefix(REVIEW_THREAD)}/replies", jsonOf("body" to body))
        } else {
            api.post("${repoPath(repo)}/issues/${pr.id}/comments", jsonOf("body" to body))
        }
    }

    override fun searchReviewers(repo: RepoRef, query: String): List<User> {
        val text = query.trim()
        val known = try {
            collaborators[repo] ?: api.get("${repoPath(repo)}/collaborators", "per_page" to 100).list().map(::toUser)
                .also { collaborators[repo] = it }
        } catch (e: HttpException) {
            // Listing collaborators needs push access; fall back to the global user search.
            if (e.status != 403 && e.status != 404) throw e
            if (text.isEmpty()) return emptyList()
            return api.get("search/users", "q" to text, "per_page" to 20).list("items").map(::toUser)
        }
        return known.filter { text.isEmpty() || it.login.contains(text, true) || it.name.contains(text, true) }
    }

    override fun listLabels(repo: RepoRef): List<String> =
        api.get("${repoPath(repo)}/labels", "per_page" to 100).list().map { it.str("name") }

    private fun toPullRequest(json: JsonElement, reviews: List<JsonElement>? = null): PullRequest {
        val state = when {
            json.str("merged_at").isNotBlank() || json.bool("merged") -> PrState.MERGED
            json.str("state") == "closed" -> PrState.CLOSED
            json.bool("draft") -> PrState.DRAFT
            else -> PrState.OPEN
        }
        val reviewers = LinkedHashMap<String, Reviewer>()
        // The latest decisive review of each user wins; a pending re-request resets it to "no vote".
        reviews.orEmpty().sortedBy { it.str("submitted_at") }.forEach { review ->
            val vote = when (review.str("state")) {
                "APPROVED" -> Vote.APPROVED
                "CHANGES_REQUESTED" -> Vote.REJECTED
                else -> return@forEach
            }
            val user = toUser(review["user"])
            reviewers[user.id] = Reviewer(user, vote)
        }
        json.list("requested_reviewers").map(::toUser).forEach { reviewers[it.id] = Reviewer(it, Vote.NONE) }
        val headRepo = json.str("head", "repo", "full_name")
        val comments = json.int("comments")
        return PullRequest(
            id = json.str("number"),
            title = json.str("title"),
            description = json.str("body"),
            state = state,
            author = toUser(json["user"]),
            sourceBranch = json.str("head", "ref"),
            targetBranch = json.str("base", "ref"),
            webUrl = json.str("html_url"),
            createdAt = json.instant("created_at"),
            updatedAt = json.instant("updated_at"),
            reviewers = reviewers.values.toList(),
            labels = json.list("labels").map { it.str("name") },
            mergeStatus = when {
                json.str("mergeable_state") == "dirty" -> MergeStatus.CONFLICTS
                json.str("mergeable_state") == "blocked" -> MergeStatus.BLOCKED
                json.str("mergeable_state") in setOf("clean", "unstable", "has_hooks") -> MergeStatus.CLEAN
                else -> MergeStatus.UNKNOWN
            },
            commentCount = comments?.let { it + (json.int("review_comments") ?: 0) },
            autoComplete = json["auto_merge"] != null,
            headSha = json.str("head", "sha").ifBlank { null },
            fromFork = headRepo != json.str("base", "repo", "full_name"),
        )
    }

    /** The login doubles as id: it is what every write endpoint expects. */
    private fun toUser(json: JsonElement?): User {
        val login = json.str("login")
        return User(login, json.str("name").ifBlank { login }, login, json.str("avatar_url").ifBlank { null })
    }

    // ---------------------------------------------------------------- actions

    override fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> =
        api.get("${repoPath(repo)}/actions/runs", "branch" to branch, "per_page" to PAGE).list("workflow_runs").map { run ->
            PipelineRun(
                id = run.str("id"),
                name = "${run.str("name")} #${run.str("run_number")}",
                state = runState(run.str("status"), run.str("conclusion")),
                branch = run.str("head_branch"),
                webUrl = run.str("html_url"),
                title = run.str("display_title").ifBlank { run.str("head_commit", "message").lineSequence().firstOrNull().orEmpty() },
                commitSha = run.str("head_sha"),
                triggeredBy = run.str("actor", "login"),
                definitionId = run.str("workflow_id"),
                startedAt = run.instant("run_started_at") ?: run.instant("created_at"),
                finishedAt = if (run.str("status") == "completed") run.instant("updated_at") else null,
            )
        }

    private fun runState(status: String, conclusion: String): CheckState = when (status) {
        "completed" -> when (conclusion) {
            "success" -> CheckState.SUCCESS
            "failure", "timed_out", "startup_failure", "action_required" -> CheckState.FAILURE
            "cancelled" -> CheckState.CANCELLED
            "skipped", "neutral", "stale" -> CheckState.SKIPPED
            else -> CheckState.UNKNOWN
        }
        "in_progress" -> CheckState.RUNNING
        "queued", "requested", "waiting", "pending" -> CheckState.PENDING
        else -> CheckState.UNKNOWN
    }

    override fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> =
        api.get("${repoPath(repo)}/actions/workflows", "per_page" to 100).list("workflows")
            .filter { it.str("state") == "active" }
            .map { PipelineDefinition(it.str("id"), it.str("name").ifBlank { it.str("path") }) }
            .sortedBy { it.name.lowercase() }

    override fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String) {
        try {
            api.post("${repoPath(repo)}/actions/workflows/${definition.id}/dispatches", jsonOf("ref" to branch))
        } catch (e: HttpException) {
            if (e.status == 422) throw IOException("This workflow has no workflow_dispatch trigger, so it cannot be started manually.", e)
            throw e
        }
    }

    override fun rerunPipeline(repo: RepoRef, run: PipelineRun) {
        api.post("${repoPath(repo)}/actions/runs/${run.id}/rerun")
    }

    override fun cancelPipeline(repo: RepoRef, run: PipelineRun) {
        api.post("${repoPath(repo)}/actions/runs/${run.id}/cancel")
    }

    override fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> =
        api.get("${repoPath(repo)}/actions/runs/${run.id}/jobs", "per_page" to 100).list("jobs").map { job ->
            PipelineJob(
                id = job.str("id"),
                name = job.str("name"),
                state = runState(job.str("status"), job.str("conclusion")),
                webUrl = job.str("html_url").ifBlank { null },
                logRef = job.str("id"),
                startedAt = job.instant("started_at"),
                finishedAt = job.instant("completed_at"),
            )
        }

    override fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String {
        val id = job.logRef ?: return "No log is available for this job yet."
        return try {
            api.getText("${repoPath(repo)}/actions/jobs/$id/logs")
        } catch (e: HttpException) {
            if (e.notFound) "No log is available for this job yet." else throw e
        }
    }

    // ---------------------------------------------------------------- issues

    override fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> {
        val text = query.trim()
        val login = if (filter == IssueFilter.ASSIGNED_TO_ME || filter == IssueFilter.CREATED_BY_ME) currentUser().login else null
        if (text.isNotEmpty()) {
            val q = buildList {
                add("repo:${slug(repo)}")
                add("is:issue")
                if (filter != IssueFilter.ALL) add("is:open")
                if (filter == IssueFilter.ASSIGNED_TO_ME) add("assignee:$login")
                if (filter == IssueFilter.CREATED_BY_ME) add("author:$login")
                add(text)
            }.joinToString(" ")
            return api.get("search/issues", "q" to q, "per_page" to PAGE).list("items")
                .filter { it["pull_request"] == null }.map(::toIssue)
        }
        return api.get(
            "${repoPath(repo)}/issues",
            "state" to if (filter == IssueFilter.ALL) "all" else "open",
            "assignee" to login.takeIf { filter == IssueFilter.ASSIGNED_TO_ME },
            "creator" to login.takeIf { filter == IssueFilter.CREATED_BY_ME },
            "sort" to "updated",
            "per_page" to PAGE,
        ).list().filter { it["pull_request"] == null }.map(::toIssue)
    }

    override fun getIssue(repo: RepoRef, id: String): Issue = toIssue(api.get("${repoPath(repo)}/issues/$id"))

    override fun createIssue(repo: RepoRef, issue: NewIssue): Issue {
        val body = jsonOf(
            "title" to issue.title,
            "body" to issue.description,
            "labels" to issue.labels.takeIf { it.isNotEmpty() },
            "assignees" to if (issue.assignToMe) listOf(currentUser().login) else null,
        )
        return toIssue(api.post("${repoPath(repo)}/issues", body))
    }

    override fun issueStates(repo: RepoRef, issue: Issue): List<String> = if (issue.open) listOf("closed") else listOf("open")

    override fun setIssueState(repo: RepoRef, issue: Issue, state: String) {
        api.patch("${repoPath(repo)}/issues/${issue.id}", jsonOf("state" to if (state.equals("open", true)) "open" else "closed"))
    }

    override fun addIssueComment(repo: RepoRef, issue: Issue, body: String) {
        api.post("${repoPath(repo)}/issues/${issue.id}/comments", jsonOf("body" to body))
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
            type = json.str("type", "name"),
            author = json["user"]?.let { toUser(it) },
            assignee = (json["assignee"] ?: json.list("assignees").firstOrNull())?.let { toUser(it) },
            labels = json.list("labels").map { it.str("name").ifBlank { it.asStringOrEmpty() } }.filter { it.isNotEmpty() },
            updatedAt = json.instant("updated_at"),
        )
    }

    private fun JsonElement.asStringOrEmpty(): String = if (isJsonPrimitive) asString else ""

    companion object {
        private const val PAGE = 50
        private const val SEARCH_CAP = 30
        private const val ISSUE_THREAD = "issue:"
        private const val REVIEW_THREAD = "review:"
        private const val REVIEW_BODY_THREAD = "reviewbody:"
        private val STRATEGIES = listOf(MergeStrategy.MERGE, MergeStrategy.SQUASH, MergeStrategy.REBASE)
    }
}
