package io.github.purgator.gitops.providers.gitlab

import com.google.gson.JsonElement
import io.github.purgator.gitops.core.http.ApiClient
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.at
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.http.get
import io.github.purgator.gitops.core.http.instant
import io.github.purgator.gitops.core.http.int
import io.github.purgator.gitops.core.http.jsonOf
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.long
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.http.string
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

/** GitLab REST v4 client (gitlab.com and self-managed). [serverUrl] is the instance web URL. */
class GitLabClient(
    serverUrl: String,
    transport: HttpTransport,
    secrets: SecretSource,
) : HostingClient {

    private val api = ApiClient(
        baseUrl = serverUrl.trimEnd('/') + "/api/v4",
        transport = transport,
        authHeaders = { GitLabProvider.authHeaders(secrets.get()) },
        onUnauthorized = { secrets.invalidate() },
    )

    @Volatile
    private var me: User? = null

    /** Projects are addressed by their URL-encoded full path. */
    private fun project(repo: RepoRef) = "projects/${urlEncode("${repo.owner}/${repo.name}")}"
    private fun mrPath(repo: RepoRef, iid: String) = "${project(repo)}/merge_requests/$iid"

    override fun currentUser(): User {
        me?.let { return it }
        return toUser(api.get("user")).also { me = it }
    }

    override fun repoInfo(repo: RepoRef): RepoInfo {
        val json = api.get(project(repo))
        return RepoInfo(
            defaultBranch = json.str("default_branch").ifBlank { "main" },
            webUrl = json.str("web_url").ifBlank { GitLabProvider.repoWebUrl(repo) },
            mergeStrategies = listOf(MergeStrategy.MERGE, MergeStrategy.SQUASH),
        )
    }

    override fun listRepositories(query: String): List<RemoteRepository> =
        api.get(
            "projects",
            "membership" to true,
            "search" to query.trim().ifEmpty { null },
            "order_by" to "last_activity_at",
            "per_page" to PAGE,
            "simple" to true,
        ).list().map {
            RemoteRepository(
                fullName = it.str("path_with_namespace"),
                httpUrl = it.str("http_url_to_repo"),
                sshUrl = it.str("ssh_url_to_repo"),
                webUrl = it.str("web_url"),
                description = it.str("description"),
                // `simple=true` omits visibility for some versions; assume private when unknown.
                private = it.str("visibility") != "public",
            )
        }

    // ---------------------------------------------------------------- merge requests

    override fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest> {
        val state = when (filter) {
            PrFilter.MINE, PrFilter.REVIEWING, PrFilter.OPEN -> "opened"
            PrFilter.MERGED -> "merged"
            PrFilter.CLOSED -> "closed"
            PrFilter.ALL -> "all"
        }
        return api.get(
            "${project(repo)}/merge_requests",
            "state" to state,
            "scope" to if (filter == PrFilter.MINE) "created_by_me" else "all",
            "reviewer_username" to if (filter == PrFilter.REVIEWING) currentUser().login else null,
            "order_by" to "updated_at",
            "per_page" to PAGE,
        ).list().map { toPullRequest(it) }
    }

    override fun getPullRequest(repo: RepoRef, id: String): PullRequest {
        val pr = toPullRequest(api.get(mrPath(repo, id)))
        val approvers = runCatching {
            api.get("${mrPath(repo, id)}/approvals").list("approved_by").map { toUser(it["user"]) }
        }.getOrDefault(emptyList())
        if (approvers.isEmpty()) return pr
        val approvedIds = approvers.map { it.id }.toSet()
        val reviewers = pr.reviewers.map { if (it.user.id in approvedIds) it.copy(vote = Vote.APPROVED) else it } +
            approvers.filter { a -> pr.reviewers.none { it.user.id == a.id } }.map { Reviewer(it, Vote.APPROVED) }
        return pr.copy(reviewers = reviewers)
    }

    override fun findOpenPullRequest(repo: RepoRef, sourceBranch: String): PullRequest? =
        api.get(
            "${project(repo)}/merge_requests",
            "state" to "opened",
            "source_branch" to sourceBranch,
            "per_page" to 20,
        ).list().map { toPullRequest(it) }.firstOrNull { !it.fromFork }

    override fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest {
        val closes = request.linkedIssues.map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.joinToString("\n") { "Closes #$it" }
        val description = listOf(request.description.trimEnd(), closes).filter { it.isNotEmpty() }.joinToString("\n\n")
        val auto = request.autoComplete
        val body = jsonOf(
            "source_branch" to request.sourceBranch,
            "target_branch" to request.targetBranch,
            "title" to if (request.draft) DRAFT_PREFIX + stripDraft(request.title) else request.title,
            "description" to description,
            "reviewer_ids" to request.reviewers.mapNotNull { it.id.toLongOrNull() }.takeIf { it.isNotEmpty() },
            "labels" to request.labels.joinToString(",").ifEmpty { null },
            "remove_source_branch" to auto?.deleteSourceBranch,
            "squash" to auto?.let { it.strategy == MergeStrategy.SQUASH },
        )
        val created = api.post("${project(repo)}/merge_requests", body)
        if (auto == null) return toPullRequest(created)
        // Auto-merge can only be armed while a pipeline is running; not being able to is not an error.
        val armed = runCatching { api.put("${mrPath(repo, created.str("iid"))}/merge", mergeBody(auto, autoMerge = true)) }.getOrNull()
        return toPullRequest(armed?.takeIf { it.str("iid").isNotEmpty() } ?: created)
    }

    override fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest {
        val baseTitle = stripDraft(update.title ?: pr.title)
        val draft = update.draft ?: (update.title?.let { isDraftTitle(it) } ?: pr.isDraft)
        val title = if (update.title != null || update.draft != null) (if (draft) DRAFT_PREFIX + baseTitle else baseTitle) else null
        val body = jsonOf(
            "title" to title,
            "description" to update.description,
            "target_branch" to update.targetBranch,
        )
        return toPullRequest(api.put(mrPath(repo, pr.id), body))
    }

    override fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions) {
        api.put("${mrPath(repo, pr.id)}/merge", mergeBody(options, options.autoComplete))
    }

    /** Rebase strategies fall back to a plain merge: GitLab applies the project's configured merge method. */
    private fun mergeBody(options: MergeOptions, autoMerge: Boolean) = jsonOf(
        "squash" to (options.strategy == MergeStrategy.SQUASH),
        "should_remove_source_branch" to options.deleteSourceBranch,
        (if (options.strategy == MergeStrategy.SQUASH) "squash_commit_message" else "merge_commit_message") to
            options.message?.takeIf { it.isNotBlank() },
        "merge_when_pipeline_succeeds" to if (autoMerge) true else null,
    )

    override fun closePullRequest(repo: RepoRef, pr: PullRequest) {
        api.put(mrPath(repo, pr.id), jsonOf("state_event" to "close"))
    }

    override fun reopenPullRequest(repo: RepoRef, pr: PullRequest) {
        api.put(mrPath(repo, pr.id), jsonOf("state_event" to "reopen"))
    }

    override fun cancelAutoComplete(repo: RepoRef, pr: PullRequest) {
        api.post("${mrPath(repo, pr.id)}/cancel_merge_when_pipeline_succeeds")
    }

    override fun vote(repo: RepoRef, pr: PullRequest, vote: Vote) {
        when (vote) {
            Vote.APPROVED -> api.post("${mrPath(repo, pr.id)}/approve")
            Vote.NONE -> api.post("${mrPath(repo, pr.id)}/unapprove")
            else -> unsupported()
        }
    }

    override fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> = runCatching {
        val pipeline = api.get("${mrPath(repo, pr.id)}/pipelines", "per_page" to 1).list().firstOrNull()
            ?: return@runCatching emptyList()
        api.get("${project(repo)}/pipelines/${pipeline.str("id")}/jobs", "per_page" to 100).list()
            .sortedBy { it.long("id") ?: 0 }
            .map { job ->
                Check(
                    name = listOf(job.str("stage"), job.str("name")).filter { it.isNotEmpty() }.joinToString(": "),
                    state = pipelineState(job.str("status")),
                    url = job.str("web_url").ifBlank { null },
                )
            }
    }.getOrDefault(emptyList())

    override fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread> =
        api.get("${mrPath(repo, pr.id)}/discussions", "per_page" to 100).list().mapNotNull { discussion ->
            val notes = discussion.list("notes").filter { !it.bool("system") }
            if (notes.isEmpty()) return@mapNotNull null
            val position = notes.first()["position"]
            val resolvable = notes.filter { it.bool("resolvable") }
            CommentThread(
                id = discussion.str("id"),
                comments = notes.map { Comment(it.str("id"), toUser(it["author"]), it.str("body"), it.instant("created_at")) },
                filePath = position.str("new_path").ifBlank { position.str("old_path") }.ifBlank { null },
                line = position.int("new_line") ?: position.int("old_line"),
                resolved = if (resolvable.isEmpty()) null else resolvable.all { it.bool("resolved") },
            )
        }

    override fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String?) {
        if (threadId == null) {
            api.post("${mrPath(repo, pr.id)}/notes", jsonOf("body" to body))
        } else {
            api.post("${mrPath(repo, pr.id)}/discussions/$threadId/notes", jsonOf("body" to body))
        }
    }

    override fun searchReviewers(repo: RepoRef, query: String): List<User> =
        api.get("${project(repo)}/members/all", "query" to query.trim().ifEmpty { null }, "per_page" to 30).list()
            .filter { it.str("state").let { s -> s.isEmpty() || s == "active" } }
            .map { toUser(it) }
            .distinctBy { it.id }

    override fun listLabels(repo: RepoRef): List<String> =
        api.get("${project(repo)}/labels", "per_page" to 100).list().map { it.str("name") }.filter { it.isNotEmpty() }

    private fun toPullRequest(json: JsonElement): PullRequest {
        val draft = json.bool("draft") || json.bool("work_in_progress")
        val state = when (json.str("state")) {
            "merged" -> PrState.MERGED
            "closed", "locked" -> PrState.CLOSED
            else -> if (draft) PrState.DRAFT else PrState.OPEN
        }
        val detailed = json.str("detailed_merge_status")
        val mergeStatus = when {
            json.bool("has_conflicts") || detailed == "conflict" || json.str("merge_status") == "cannot_be_merged" -> MergeStatus.CONFLICTS
            detailed == "mergeable" -> MergeStatus.CLEAN
            detailed in BLOCKED_STATUSES -> MergeStatus.BLOCKED
            detailed.isEmpty() && json.str("merge_status") == "can_be_merged" -> MergeStatus.CLEAN
            else -> MergeStatus.UNKNOWN
        }
        val sourceProject = json.str("source_project_id")
        return PullRequest(
            id = json.str("iid"),
            title = json.str("title"),
            description = json.str("description"),
            state = state,
            author = toUser(json["author"]),
            sourceBranch = json.str("source_branch"),
            targetBranch = json.str("target_branch"),
            webUrl = json.str("web_url"),
            createdAt = json.instant("created_at"),
            updatedAt = json.instant("updated_at"),
            reviewers = json.list("reviewers").map { Reviewer(toUser(it)) },
            labels = json.list("labels").mapNotNull { it.string ?: it.str("name").ifBlank { null } },
            mergeStatus = mergeStatus,
            checkState = json.at("head_pipeline", "status").string?.let { pipelineState(it) },
            commentCount = json.int("user_notes_count"),
            autoComplete = json.bool("merge_when_pipeline_succeeds"),
            headSha = json.str("sha").ifBlank { null },
            fromFork = sourceProject.isNotEmpty() && sourceProject != json.str("target_project_id"),
        )
    }

    private fun toUser(json: JsonElement?): User =
        User(json.str("id"), json.str("name"), json.str("username"), json.str("avatar_url").ifBlank { null })

    // ---------------------------------------------------------------- pipelines

    override fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> =
        api.get("${project(repo)}/pipelines", "ref" to branch, "per_page" to PAGE).list().map { pipeline ->
            val state = pipelineState(pipeline.str("status"))
            PipelineRun(
                id = pipeline.str("id"),
                name = "Pipeline #${pipeline.str("iid").ifBlank { pipeline.str("id") }}",
                state = state,
                branch = pipeline.str("ref"),
                webUrl = pipeline.str("web_url"),
                title = pipeline.str("name"),
                commitSha = pipeline.str("sha"),
                triggeredBy = pipeline.str("user", "name"),
                startedAt = pipeline.instant("started_at") ?: pipeline.instant("created_at"),
                finishedAt = pipeline.instant("finished_at") ?: if (state.finished) pipeline.instant("updated_at") else null,
            )
        }

    override fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> =
        listOf(PipelineDefinition("default", "Project pipeline"))

    override fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String) {
        api.post("${project(repo)}/pipeline", null, "ref" to branch)
    }

    override fun rerunPipeline(repo: RepoRef, run: PipelineRun) {
        api.post("${project(repo)}/pipelines/${run.id}/retry")
    }

    override fun cancelPipeline(repo: RepoRef, run: PipelineRun) {
        api.post("${project(repo)}/pipelines/${run.id}/cancel")
    }

    override fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> =
        api.get("${project(repo)}/pipelines/${run.id}/jobs", "per_page" to 100).list()
            .sortedBy { it.long("id") ?: 0 }
            .map { job ->
                PipelineJob(
                    id = job.str("id"),
                    name = job.str("name"),
                    state = pipelineState(job.str("status")),
                    stage = job.str("stage"),
                    webUrl = job.str("web_url").ifBlank { null },
                    logRef = job.str("id").ifBlank { null },
                    startedAt = job.instant("started_at"),
                    finishedAt = job.instant("finished_at"),
                )
            }

    override fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String {
        val id = job.logRef ?: return "No log is available for this job yet."
        return api.getText("${project(repo)}/jobs/$id/trace")
    }

    // ---------------------------------------------------------------- issues

    override fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> =
        api.get(
            "${project(repo)}/issues",
            "state" to if (filter == IssueFilter.ALL) "all" else "opened",
            "scope" to when (filter) {
                IssueFilter.ASSIGNED_TO_ME -> "assigned_to_me"
                IssueFilter.CREATED_BY_ME -> "created_by_me"
                else -> "all"
            },
            "search" to query.trim().ifEmpty { null },
            "order_by" to "updated_at",
            "per_page" to PAGE,
        ).list().map { toIssue(it) }

    override fun getIssue(repo: RepoRef, id: String): Issue = toIssue(api.get("${project(repo)}/issues/$id"))

    override fun createIssue(repo: RepoRef, issue: NewIssue): Issue {
        val body = jsonOf(
            "title" to issue.title,
            "description" to issue.description.ifBlank { null },
            "labels" to issue.labels.joinToString(",").ifEmpty { null },
            "assignee_ids" to if (issue.assignToMe) listOfNotNull(currentUser().id.toLongOrNull()) else null,
        )
        return toIssue(api.post("${project(repo)}/issues", body))
    }

    override fun issueStates(repo: RepoRef, issue: Issue): List<String> = listOf(if (issue.open) "closed" else "opened")

    override fun setIssueState(repo: RepoRef, issue: Issue, state: String) {
        val event = if (state.startsWith("close", ignoreCase = true)) "close" else "reopen"
        api.put("${project(repo)}/issues/${issue.id}", jsonOf("state_event" to event))
    }

    override fun addIssueComment(repo: RepoRef, issue: Issue, body: String) {
        api.post("${project(repo)}/issues/${issue.id}/notes", jsonOf("body" to body))
    }

    private fun toIssue(json: JsonElement): Issue {
        val state = json.str("state")
        return Issue(
            id = json.str("iid"),
            title = json.str("title"),
            description = json.str("description"),
            state = state,
            open = state == "opened",
            webUrl = json.str("web_url"),
            type = json.str("issue_type"),
            author = json["author"]?.let { toUser(it) },
            assignee = (json.list("assignees").firstOrNull() ?: json["assignee"])?.let { toUser(it) },
            labels = json.list("labels").mapNotNull { it.string ?: it.str("name").ifBlank { null } },
            updatedAt = json.instant("updated_at"),
        )
    }

    companion object {
        private const val PAGE = 50
        private const val DRAFT_PREFIX = "Draft: "
        private val DRAFT_PATTERN = Regex("""^\s*(?:\[draft]|draft:|\(draft\)|\[wip]|wip:)\s*""", RegexOption.IGNORE_CASE)
        private val BLOCKED_STATUSES = setOf(
            "not_approved", "ci_must_pass", "discussions_not_resolved", "blocked_status", "draft_status",
            "external_status_checks", "jira_association_missing", "requested_changes", "security_policy_violations",
            "need_rebase", "not_open", "locked_paths", "merge_request_blocked",
        )

        internal fun isDraftTitle(title: String): Boolean = DRAFT_PATTERN.containsMatchIn(title)

        internal fun stripDraft(title: String): String {
            var current = title
            while (true) {
                val next = DRAFT_PATTERN.replaceFirst(current, "")
                if (next == current) return current.trim()
                current = next
            }
        }

        internal fun pipelineState(status: String): CheckState = when (status) {
            "success" -> CheckState.SUCCESS
            "failed" -> CheckState.FAILURE
            "canceled", "canceling" -> CheckState.CANCELLED
            "skipped" -> CheckState.SKIPPED
            "running" -> CheckState.RUNNING
            "created", "waiting_for_resource", "preparing", "pending", "manual", "scheduled" -> CheckState.PENDING
            else -> CheckState.UNKNOWN
        }
    }
}
