package io.github.purgator.gitops.providers.bitbucket

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

/** Bitbucket Cloud REST 2.0 client. The API lives on a fixed host, so [serverUrl] is informative only. */
class BitbucketClient(
    @Suppress("unused") private val serverUrl: String,
    transport: HttpTransport,
    secrets: SecretSource,
) : HostingClient {

    private val api = ApiClient(
        baseUrl = BitbucketProvider.API_URL,
        transport = transport,
        authHeaders = { BitbucketProvider.authHeaders(secrets.get()) },
        onUnauthorized = { secrets.invalidate() },
    )

    @Volatile
    private var me: User? = null

    private fun repoPath(repo: RepoRef) = "repositories/${urlEncode(repo.owner)}/${urlEncode(repo.name)}"
    private fun prPath(repo: RepoRef, id: String) = "${repoPath(repo)}/pullrequests/$id"
    private fun pipelinePath(repo: RepoRef, uuid: String) = "${repoPath(repo)}/pipelines/${urlEncode(uuid)}"

    override fun currentUser(): User {
        me?.let { return it }
        return toUser(api.get("user")).also { me = it }
    }

    override fun repoInfo(repo: RepoRef): RepoInfo {
        val json = api.get(repoPath(repo))
        return RepoInfo(
            defaultBranch = json.str("mainbranch", "name").ifBlank { "main" },
            webUrl = json.str("links", "html", "href").ifBlank { BitbucketProvider.repoWebUrl(repo) },
            mergeStrategies = listOf(MergeStrategy.MERGE, MergeStrategy.SQUASH, MergeStrategy.REBASE),
        )
    }

    override fun listRepositories(query: String): List<RemoteRepository> =
        api.get(
            "repositories",
            "role" to "member",
            "pagelen" to 100,
            "sort" to "-updated_on",
            "q" to query.trim().takeIf { it.isNotEmpty() }?.let { "name ~ ${quote(it)}" },
        ).list("values").map { json ->
            val clone = json.list("links", "clone").associate { it.str("name") to it.str("href") }
            RemoteRepository(
                fullName = json.str("full_name"),
                // The https clone URL embeds the user name (`https://user@bitbucket.org/...`).
                httpUrl = clone["https"].orEmpty().replace(Regex("^(https?://)[^@/]+@"), "$1"),
                sshUrl = clone["ssh"].orEmpty(),
                webUrl = json.str("links", "html", "href"),
                description = json.str("description"),
                private = json.bool("is_private"),
            )
        }

    // ---------------------------------------------------------------- pull requests

    override fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest> {
        val query = ArrayList<Pair<String, Any?>>()
        when (filter) {
            PrFilter.OPEN -> query += "state" to "OPEN"
            PrFilter.MERGED -> query += "state" to "MERGED"
            PrFilter.CLOSED -> listOf("DECLINED", "SUPERSEDED").forEach { query += "state" to it }
            PrFilter.ALL -> listOf("OPEN", "MERGED", "DECLINED", "SUPERSEDED").forEach { query += "state" to it }
            PrFilter.MINE -> query += "q" to "author.uuid=${quote(currentUser().id)} AND state=\"OPEN\""
            PrFilter.REVIEWING -> query += "q" to "reviewers.uuid=${quote(currentUser().id)} AND state=\"OPEN\""
        }
        query += "sort" to "-updated_on"
        query += "pagelen" to PAGE
        return api.get("${repoPath(repo)}/pullrequests", *query.toTypedArray()).list("values").map { toPullRequest(it) }
    }

    override fun getPullRequest(repo: RepoRef, id: String): PullRequest = toPullRequest(api.get(prPath(repo, id)))

    override fun findOpenPullRequest(repo: RepoRef, sourceBranch: String): PullRequest? =
        api.get(
            "${repoPath(repo)}/pullrequests",
            "q" to "source.branch.name=${quote(sourceBranch)} AND state=\"OPEN\"",
            "pagelen" to 10,
        ).list("values").map { toPullRequest(it) }.firstOrNull { !it.fromFork }

    override fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest {
        // The API rejects a request whose author is also listed as reviewer.
        val self = runCatching { currentUser().id }.getOrNull()
        val body = jsonOf(
            "title" to request.title,
            "description" to request.description,
            "source" to branch(request.sourceBranch),
            "destination" to branch(request.targetBranch),
            "reviewers" to request.reviewers.filter { it.id != self }.map { jsonOf("uuid" to it.id) },
            "draft" to request.draft,
            "close_source_branch" to (request.autoComplete?.deleteSourceBranch ?: false),
        )
        return toPullRequest(api.post("${repoPath(repo)}/pullrequests", body))
    }

    override fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest {
        val body = jsonOf(
            // The title is mandatory on every update.
            "title" to (update.title ?: pr.title),
            "description" to update.description,
            "destination" to update.targetBranch?.let { branch(it) },
            "draft" to update.draft,
        )
        return toPullRequest(api.put(prPath(repo, pr.id), body))
    }

    override fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions) {
        val body = jsonOf(
            "merge_strategy" to when (options.strategy) {
                MergeStrategy.MERGE, MergeStrategy.REBASE_MERGE -> "merge_commit"
                MergeStrategy.SQUASH -> "squash"
                MergeStrategy.REBASE -> "fast_forward"
            },
            "close_source_branch" to options.deleteSourceBranch,
            "message" to options.message?.takeIf { it.isNotBlank() },
        )
        api.post("${prPath(repo, pr.id)}/merge", body)
    }

    override fun closePullRequest(repo: RepoRef, pr: PullRequest) {
        api.post("${prPath(repo, pr.id)}/decline")
    }

    override fun vote(repo: RepoRef, pr: PullRequest, vote: Vote) {
        val path = prPath(repo, pr.id)
        when (vote) {
            Vote.APPROVED -> api.post("$path/approve")
            Vote.REJECTED -> api.post("$path/request-changes")
            Vote.NONE -> {
                // Either may be absent; removing what is not there answers 4xx.
                runCatching { api.delete("$path/approve") }
                runCatching { api.delete("$path/request-changes") }
            }
            else -> unsupported()
        }
    }

    override fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> =
        api.get("${prPath(repo, pr.id)}/statuses", "pagelen" to 100).list("values").map {
            Check(
                name = it.str("name").ifBlank { it.str("key") },
                state = when (it.str("state")) {
                    "SUCCESSFUL" -> CheckState.SUCCESS
                    "FAILED" -> CheckState.FAILURE
                    "INPROGRESS" -> CheckState.RUNNING
                    "STOPPED" -> CheckState.CANCELLED
                    else -> CheckState.UNKNOWN
                },
                url = it.str("url").ifBlank { null },
                description = it.str("description"),
            )
        }

    override fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread> {
        val all = api.get("${prPath(repo, pr.id)}/comments", "pagelen" to 100).list("values").filter { !it.bool("deleted") }
        val parentOf = all.associate { it.str("id") to it.str("parent", "id") }
        fun rootOf(id: String): String {
            var current = id
            repeat(20) {
                val parent = parentOf[current].orEmpty()
                if (parent.isEmpty() || parent !in parentOf) return current
                current = parent
            }
            return current
        }
        val byRoot = all.groupBy { rootOf(it.str("id")) }
        return all.filter { rootOf(it.str("id")) == it.str("id") }.map { root ->
            val inline = root["inline"]
            CommentThread(
                id = root.str("id"),
                comments = byRoot[root.str("id")].orEmpty().sortedBy { it.str("created_on") }.map {
                    Comment(it.str("id"), toUser(it["user"]), it.str("content", "raw"), it.instant("created_on"))
                },
                filePath = inline.str("path").ifBlank { null },
                line = inline.int("to") ?: inline.int("from"),
                resolved = if (inline != null) root["resolution"] != null else null,
            )
        }
    }

    override fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String?) {
        val json = jsonOf(
            "content" to jsonOf("raw" to body),
            "parent" to threadId?.let { jsonOf("id" to (it.toLongOrNull() ?: it)) },
        )
        api.post("${prPath(repo, pr.id)}/comments", json)
    }

    override fun searchReviewers(repo: RepoRef, query: String): List<User> {
        val text = query.trim()
        return runCatching {
            api.get("workspaces/${urlEncode(repo.owner)}/members", "pagelen" to 100).list("values")
                .map { toUser(it["user"]) }
                .filter { it.id.isNotEmpty() && (text.isEmpty() || it.name.contains(text, true) || it.login.contains(text, true)) }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun branch(name: String) = jsonOf("branch" to jsonOf("name" to name))

    private fun toPullRequest(json: JsonElement): PullRequest {
        val state = when (json.str("state")) {
            "MERGED" -> PrState.MERGED
            "DECLINED", "SUPERSEDED" -> PrState.CLOSED
            else -> if (json.bool("draft")) PrState.DRAFT else PrState.OPEN
        }
        val sourceRepo = json.str("source", "repository", "full_name")
        val targetRepo = json.str("destination", "repository", "full_name")
        return PullRequest(
            id = json.str("id"),
            title = json.str("title"),
            description = json.str("description").ifBlank { json.str("summary", "raw") },
            state = state,
            author = toUser(json["author"]),
            sourceBranch = json.str("source", "branch", "name"),
            targetBranch = json.str("destination", "branch", "name"),
            webUrl = json.str("links", "html", "href"),
            createdAt = json.instant("created_on"),
            updatedAt = json.instant("updated_on"),
            reviewers = json.list("participants")
                .filter { it.str("role") == "REVIEWER" || it.bool("approved") || it["state"] != null }
                .map { participant ->
                    val vote = when {
                        participant.bool("approved") -> Vote.APPROVED
                        participant.str("state") == "changes_requested" -> Vote.REJECTED
                        else -> Vote.NONE
                    }
                    Reviewer(toUser(participant["user"]), vote)
                },
            commentCount = json.int("comment_count"),
            headSha = json.str("source", "commit", "hash").ifBlank { null },
            fromFork = sourceRepo.isNotEmpty() && targetRepo.isNotEmpty() && sourceRepo != targetRepo,
        )
    }

    private fun toUser(json: JsonElement?): User = User(
        id = json.str("uuid"),
        name = json.str("display_name"),
        login = json.str("nickname").ifBlank { json.str("username") },
        avatarUrl = json.str("links", "avatar", "href").ifBlank { null },
    )

    // ---------------------------------------------------------------- pipelines

    override fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> =
        api.get(
            "${repoPath(repo)}/pipelines/",
            "sort" to "-created_on",
            "pagelen" to PAGE,
            "target.ref_name" to branch,
        ).list("values").map { run ->
            val number = run.str("build_number")
            PipelineRun(
                id = run.str("uuid"),
                name = "Pipeline #$number",
                state = pipelineState(run["state"]),
                branch = run.str("target", "ref_name"),
                webUrl = "${BitbucketProvider.repoWebUrl(repo)}/pipelines/results/$number",
                title = run.str("target", "commit", "message").lineSequence().firstOrNull().orEmpty(),
                commitSha = run.str("target", "commit", "hash"),
                triggeredBy = run.str("creator", "display_name"),
                startedAt = run.instant("created_on"),
                finishedAt = run.instant("completed_on"),
            )
        }

    internal fun pipelineState(state: JsonElement?): CheckState = when (state.str("name")) {
        "COMPLETED" -> resultState(state.str("result", "name"))
        "IN_PROGRESS" -> when (state.str("stage", "name")) {
            "PAUSED", "HALTED" -> CheckState.PENDING
            else -> CheckState.RUNNING
        }
        "PENDING" -> CheckState.PENDING
        // Steps sometimes report the result directly as the state name.
        else -> resultState(state.str("name"))
    }

    private fun resultState(result: String): CheckState = when (result) {
        "SUCCESSFUL" -> CheckState.SUCCESS
        "FAILED", "ERROR" -> CheckState.FAILURE
        "STOPPED", "EXPIRED" -> CheckState.CANCELLED
        "NOT_RUN" -> CheckState.SKIPPED
        else -> CheckState.UNKNOWN
    }

    override fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> =
        listOf(PipelineDefinition("default", "Default pipeline"))

    override fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String) {
        val body = jsonOf(
            "target" to jsonOf("type" to "pipeline_ref_target", "ref_type" to "branch", "ref_name" to branch),
        )
        api.post("${repoPath(repo)}/pipelines/", body)
    }

    override fun cancelPipeline(repo: RepoRef, run: PipelineRun) {
        api.post("${pipelinePath(repo, run.id)}/stopPipeline")
    }

    override fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> =
        api.get("${pipelinePath(repo, run.id)}/steps/", "pagelen" to 100).list("values").map { step ->
            PipelineJob(
                id = step.str("uuid"),
                name = step.str("name").ifBlank { "Step" },
                state = pipelineState(step["state"]),
                webUrl = run.webUrl,
                logRef = step.str("uuid").ifBlank { null },
                startedAt = step.instant("started_on"),
                finishedAt = step.instant("completed_on"),
            )
        }

    override fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String {
        val step = job.logRef ?: return NO_LOG
        return try {
            api.getText("${pipelinePath(repo, run.id)}/steps/${urlEncode(step)}/log")
        } catch (e: HttpException) {
            if (e.notFound) NO_LOG else throw e
        }
    }

    // ---------------------------------------------------------------- issues

    override fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> {
        val conditions = ArrayList<String>()
        val openOnly = "(state=\"new\" OR state=\"open\")"
        when (filter) {
            IssueFilter.ASSIGNED_TO_ME -> { conditions += "assignee.uuid=${quote(currentUser().id)}"; conditions += openOnly }
            IssueFilter.CREATED_BY_ME -> { conditions += "reporter.uuid=${quote(currentUser().id)}"; conditions += openOnly }
            IssueFilter.OPEN -> conditions += openOnly
            IssueFilter.ALL -> Unit
        }
        val text = query.trim()
        if (text.isNotEmpty()) conditions += "title ~ ${quote(text)}"
        return tracker {
            api.get(
                "${repoPath(repo)}/issues",
                "q" to conditions.joinToString(" AND ").ifEmpty { null },
                "sort" to "-updated_on",
                "pagelen" to PAGE,
            ).list("values").map { toIssue(it) }
        }
    }

    override fun getIssue(repo: RepoRef, id: String): Issue = tracker { toIssue(api.get("${repoPath(repo)}/issues/$id")) }

    override fun issueTypes(repo: RepoRef): List<String> = ISSUE_KINDS

    override fun createIssue(repo: RepoRef, issue: NewIssue): Issue {
        val body = jsonOf(
            "title" to issue.title,
            "content" to jsonOf("raw" to issue.description),
            "kind" to (issue.type?.takeIf { it in ISSUE_KINDS } ?: "bug"),
            "assignee" to if (issue.assignToMe) jsonOf("uuid" to currentUser().id) else null,
        )
        return tracker { toIssue(api.post("${repoPath(repo)}/issues", body)) }
    }

    override fun issueStates(repo: RepoRef, issue: Issue): List<String> = ISSUE_STATES.filter { it != issue.state }

    override fun setIssueState(repo: RepoRef, issue: Issue, state: String) {
        tracker { api.put("${repoPath(repo)}/issues/${issue.id}", jsonOf("state" to state)) }
    }

    override fun addIssueComment(repo: RepoRef, issue: Issue, body: String) {
        tracker { api.post("${repoPath(repo)}/issues/${issue.id}/comments", jsonOf("content" to jsonOf("raw" to body))) }
    }

    /** The issue tracker is optional per repository; a disabled one answers 404. */
    private fun <T> tracker(call: () -> T): T = try {
        call()
    } catch (e: HttpException) {
        if (e.notFound) throw IOException("The issue tracker is not enabled for this repository.", e) else throw e
    }

    private fun toIssue(json: JsonElement): Issue {
        val state = json.str("state")
        return Issue(
            id = json.str("id"),
            title = json.str("title"),
            description = json.str("content", "raw"),
            state = state,
            open = state in OPEN_STATES,
            webUrl = json.str("links", "html", "href"),
            type = json.str("kind"),
            author = json["reporter"]?.let { toUser(it) },
            assignee = json["assignee"]?.let { toUser(it) },
            updatedAt = json.instant("updated_on") ?: json.instant("created_on"),
        )
    }

    /** Quotes a value for Bitbucket's query language. */
    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val PAGE = 50
        private const val NO_LOG = "No log is available for this step yet."
        private val ISSUE_KINDS = listOf("bug", "enhancement", "proposal", "task")
        private val ISSUE_STATES = listOf("new", "open", "resolved", "on hold", "invalid", "duplicate", "wontfix", "closed")
        private val OPEN_STATES = setOf("new", "open", "on hold")
    }
}
