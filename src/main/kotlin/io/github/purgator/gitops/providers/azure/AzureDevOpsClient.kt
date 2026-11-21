package io.github.purgator.gitops.providers.azure

import com.google.gson.JsonElement
import io.github.purgator.gitops.core.http.ApiClient
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.at
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.http.get
import io.github.purgator.gitops.core.http.instant
import io.github.purgator.gitops.core.http.int
import io.github.purgator.gitops.core.http.jsonArrayOf
import io.github.purgator.gitops.core.http.jsonOf
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.parseJson
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
import java.util.concurrent.ConcurrentHashMap

/** Azure DevOps Services / Server REST client. [serverUrl] is the organisation (collection) URL. */
class AzureDevOpsClient(
    private val serverUrl: String,
    transport: HttpTransport,
    secrets: SecretSource,
) : HostingClient {

    private val api = ApiClient(
        baseUrl = serverUrl,
        transport = transport,
        authHeaders = { AzureDevOpsProvider.authHeaders(secrets.get()) },
        onUnauthorized = { secrets.invalidate() },
    )

    @Volatile
    private var me: User? = null
    private val repoMeta = ConcurrentHashMap<RepoRef, JsonElement>()

    private val version = "api-version" to API_VERSION
    private val preview = "api-version" to "$API_VERSION-preview"

    private fun project(repo: RepoRef) = AzureDevOpsProvider.projectPath(repo)
    private fun gitPath(repo: RepoRef) = "${project(repo)}/_apis/git/repositories/${urlEncode(repo.name)}"
    private fun prPath(repo: RepoRef, id: String) = "${gitPath(repo)}/pullrequests/$id"

    override fun currentUser(): User {
        me?.let { return it }
        val user = api.get("_apis/connectionData", preview)["authenticatedUser"]
        val login = user.str("properties", "Account", "\$value")
        return User(user.str("id"), user.str("providerDisplayName").ifBlank { user.str("customDisplayName") }, login).also { me = it }
    }

    private fun meta(repo: RepoRef): JsonElement = repoMeta.computeIfAbsent(repo) { api.get(gitPath(repo), version) }

    override fun repoInfo(repo: RepoRef): RepoInfo {
        val json = meta(repo)
        return RepoInfo(
            defaultBranch = json.str("defaultBranch").removePrefix(HEADS).ifBlank { "main" },
            webUrl = json.str("webUrl").ifBlank { AzureDevOpsProvider.repoWebUrl(repo) },
            mergeStrategies = MergeStrategy.entries,
        )
    }

    override fun listRepositories(query: String): List<RemoteRepository> =
        api.get("_apis/git/repositories", version).list("value")
            .filter { !it.bool("isDisabled") }
            .map {
                RemoteRepository(
                    fullName = "${it.str("project", "name")}/${it.str("name")}",
                    httpUrl = it.str("remoteUrl"),
                    sshUrl = it.str("sshUrl"),
                    webUrl = it.str("webUrl"),
                    description = it.str("project", "description"),
                    private = it.str("project", "visibility") != "public",
                )
            }
            .filter { query.isBlank() || it.fullName.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.fullName.lowercase() }

    // ---------------------------------------------------------------- pull requests

    override fun listPullRequests(repo: RepoRef, filter: PrFilter): List<PullRequest> {
        val status = when (filter) {
            PrFilter.MINE, PrFilter.REVIEWING, PrFilter.OPEN -> "active"
            PrFilter.MERGED -> "completed"
            PrFilter.CLOSED -> "abandoned"
            PrFilter.ALL -> "all"
        }
        val json = api.get(
            "${gitPath(repo)}/pullrequests",
            "searchCriteria.status" to status,
            "searchCriteria.creatorId" to if (filter == PrFilter.MINE) currentUser().id else null,
            "searchCriteria.reviewerId" to if (filter == PrFilter.REVIEWING) currentUser().id else null,
            "\$top" to PAGE,
            version,
        )
        return json.list("value").map { toPullRequest(repo, it) }
    }

    override fun getPullRequest(repo: RepoRef, id: String): PullRequest =
        toPullRequest(repo, api.get(prPath(repo, id), version))

    override fun findOpenPullRequest(repo: RepoRef, sourceBranch: String): PullRequest? =
        api.get(
            "${gitPath(repo)}/pullrequests",
            "searchCriteria.status" to "active",
            "searchCriteria.sourceRefName" to HEADS + sourceBranch,
            "\$top" to 1,
            version,
        ).list("value").firstOrNull()?.let { toPullRequest(repo, it) }

    override fun createPullRequest(repo: RepoRef, request: NewPullRequest): PullRequest {
        val body = jsonOf(
            "sourceRefName" to HEADS + request.sourceBranch,
            "targetRefName" to HEADS + request.targetBranch,
            "title" to request.title.take(MAX_TITLE),
            "description" to request.description.take(MAX_DESCRIPTION),
            "isDraft" to request.draft,
            "reviewers" to request.reviewers.map { jsonOf("id" to it.id) },
            "labels" to request.labels.map { jsonOf("name" to it) },
            "workItemRefs" to request.linkedIssues.map { jsonOf("id" to it) },
        )
        val created = api.post("${gitPath(repo)}/pullrequests", body, version)
        val id = created.str("pullRequestId")
        val auto = request.autoComplete ?: return toPullRequest(repo, created)
        return toPullRequest(repo, api.patch(prPath(repo, id), autoCompleteBody(auto), version))
    }

    override fun updatePullRequest(repo: RepoRef, pr: PullRequest, update: PullRequestUpdate): PullRequest {
        val body = jsonOf(
            "title" to update.title?.take(MAX_TITLE),
            "description" to update.description?.take(MAX_DESCRIPTION),
            "isDraft" to update.draft,
            "targetRefName" to update.targetBranch?.let { HEADS + it },
        )
        return toPullRequest(repo, api.patch(prPath(repo, pr.id), body, version))
    }

    override fun mergePullRequest(repo: RepoRef, pr: PullRequest, options: MergeOptions) {
        if (options.autoComplete) {
            api.patch(prPath(repo, pr.id), autoCompleteBody(options), version)
            return
        }
        // Completing requires the commit we believe is the head, so the server can reject a stale request.
        val head = api.get(prPath(repo, pr.id), version).str("lastMergeSourceCommit", "commitId")
        val body = jsonOf(
            "status" to "completed",
            "lastMergeSourceCommit" to jsonOf("commitId" to head),
            "completionOptions" to completionOptions(options),
        )
        api.patch(prPath(repo, pr.id), body, version)
    }

    private fun autoCompleteBody(options: MergeOptions) = jsonOf(
        "autoCompleteSetBy" to jsonOf("id" to currentUser().id),
        "completionOptions" to completionOptions(options),
    )

    private fun completionOptions(options: MergeOptions) = jsonOf(
        "deleteSourceBranch" to options.deleteSourceBranch,
        "mergeStrategy" to when (options.strategy) {
            MergeStrategy.MERGE -> "noFastForward"
            MergeStrategy.SQUASH -> "squash"
            MergeStrategy.REBASE -> "rebase"
            MergeStrategy.REBASE_MERGE -> "rebaseMerge"
        },
        "transitionWorkItems" to options.completeWorkItems,
        "mergeCommitMessage" to options.message?.takeIf { it.isNotBlank() },
    )

    override fun closePullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("status" to "abandoned"), version)
    }

    override fun reopenPullRequest(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("status" to "active"), version)
    }

    override fun cancelAutoComplete(repo: RepoRef, pr: PullRequest) {
        api.patch(prPath(repo, pr.id), jsonOf("autoCompleteSetBy" to jsonOf("id" to EMPTY_GUID)), version)
    }

    override fun vote(repo: RepoRef, pr: PullRequest, vote: Vote) {
        val value = when (vote) {
            Vote.APPROVED -> 10
            Vote.APPROVED_WITH_SUGGESTIONS -> 5
            Vote.NONE -> 0
            Vote.WAITING -> -5
            Vote.REJECTED -> -10
        }
        api.put("${prPath(repo, pr.id)}/reviewers/${currentUser().id}", jsonOf("vote" to value), version)
    }

    override fun listChecks(repo: RepoRef, pr: PullRequest): List<Check> {
        val checks = ArrayList<Check>()
        val projectId = meta(repo).str("project", "id")
        if (projectId.isNotEmpty()) {
            // Branch policies (build validation, required reviewers, linked work items, …).
            runCatching {
                api.get(
                    "${project(repo)}/_apis/policy/evaluations",
                    "artifactId" to "vstfs:///CodeReview/CodeReviewId/$projectId/${pr.id}",
                    preview,
                ).list("value")
            }.getOrDefault(emptyList()).forEach { evaluation ->
                val status = evaluation.str("status")
                if (status == "notApplicable") return@forEach
                val settings = evaluation.at("configuration", "settings")
                val name = settings.str("displayName").ifBlank { evaluation.str("configuration", "type", "displayName") }
                val buildId = evaluation.str("context", "buildId")
                val optional = !evaluation.bool("configuration", "isBlocking")
                checks += Check(
                    name = name + if (optional) " (optional)" else "",
                    state = when (status) {
                        "approved" -> CheckState.SUCCESS
                        "rejected", "broken" -> CheckState.FAILURE
                        "running" -> CheckState.RUNNING
                        "queued" -> CheckState.PENDING
                        else -> CheckState.UNKNOWN
                    },
                    url = buildId.takeIf { it.isNotEmpty() && it != "0" }?.let { "$serverUrl/${project(repo)}/_build/results?buildId=$it" },
                )
            }
        }
        // Statuses posted by external services.
        runCatching { api.get("${prPath(repo, pr.id)}/statuses", version).list("value") }.getOrDefault(emptyList())
            .groupBy { it.str("context", "genre") + "/" + it.str("context", "name") }
            .values.mapNotNull { group -> group.maxByOrNull { it.int("id") ?: 0 } }
            .forEach { status ->
                val state = when (status.str("state")) {
                    "succeeded" -> CheckState.SUCCESS
                    "failed", "error" -> CheckState.FAILURE
                    "pending" -> CheckState.PENDING
                    "notApplicable" -> return@forEach
                    else -> CheckState.UNKNOWN
                }
                checks += Check(status.str("context", "name"), state, status.str("targetUrl").ifBlank { null }, status.str("description"))
            }
        return checks
    }

    override fun listComments(repo: RepoRef, pr: PullRequest): List<CommentThread> =
        api.get("${prPath(repo, pr.id)}/threads", version).list("value")
            .filter { !it.bool("isDeleted") }
            .mapNotNull { thread ->
                val comments = thread.list("comments")
                    .filter { !it.bool("isDeleted") && it.str("commentType") != "system" }
                    .map { Comment(it.str("id"), toUser(it["author"]), it.str("content"), it.instant("publishedDate")) }
                if (comments.isEmpty()) return@mapNotNull null
                val status = thread.str("status")
                CommentThread(
                    id = thread.str("id"),
                    comments = comments,
                    filePath = thread.str("threadContext", "filePath").trimStart('/').ifBlank { null },
                    line = thread.int("threadContext", "rightFileStart", "line") ?: thread.int("threadContext", "leftFileStart", "line"),
                    resolved = if (status.isBlank() || status == "unknown") null else status != "active" && status != "pending",
                )
            }

    override fun addComment(repo: RepoRef, pr: PullRequest, body: String, threadId: String?) {
        if (threadId == null) {
            val thread = jsonOf(
                "comments" to listOf(jsonOf("parentCommentId" to 0, "content" to body, "commentType" to 1)),
                "status" to 1,
            )
            api.post("${prPath(repo, pr.id)}/threads", thread, version)
        } else {
            api.post("${prPath(repo, pr.id)}/threads/$threadId/comments", jsonOf("content" to body, "parentCommentId" to 1, "commentType" to 1), version)
        }
    }

    override fun searchReviewers(repo: RepoRef, query: String): List<User> {
        if (query.isBlank()) return emptyList()
        val body = jsonOf(
            "query" to query.trim(),
            "identityTypes" to listOf("user", "group"),
            "operationScopes" to listOf("ims", "source"),
            "options" to jsonOf("MinResults" to 5, "MaxResults" to 20),
            "properties" to listOf("DisplayName", "Mail", "SignInAddress"),
        )
        return api.post("_apis/IdentityPicker/Identities", body, "api-version" to "$API_VERSION-preview.1")
            .list("results").flatMap { it.list("identities") }
            .mapNotNull { identity ->
                // Identities never seen by the organisation have no local id and cannot be added as reviewers.
                val id = identity.str("localId").ifBlank { return@mapNotNull null }
                User(id, identity.str("displayName"), identity.str("signInAddress").ifBlank { identity.str("mail") })
            }
            .distinctBy { it.id }
    }

    private fun toPullRequest(repo: RepoRef, json: JsonElement): PullRequest {
        val id = json.str("pullRequestId")
        val state = when (json.str("status")) {
            "completed" -> PrState.MERGED
            "abandoned" -> PrState.CLOSED
            else -> if (json.bool("isDraft")) PrState.DRAFT else PrState.OPEN
        }
        return PullRequest(
            id = id,
            title = json.str("title"),
            description = json.str("description"),
            state = state,
            author = toUser(json["createdBy"]),
            sourceBranch = json.str("sourceRefName").removePrefix(HEADS),
            targetBranch = json.str("targetRefName").removePrefix(HEADS),
            webUrl = "${AzureDevOpsProvider.repoWebUrl(repo)}/pullrequest/$id",
            createdAt = json.instant("creationDate"),
            updatedAt = json.instant("closedDate") ?: json.instant("creationDate"),
            reviewers = json.list("reviewers").map {
                Reviewer(toUser(it), toVote(it.int("vote") ?: 0), it.bool("isRequired"))
            },
            labels = json.list("labels").filter { it.bool("active") || it["active"] == null }.map { it.str("name") },
            mergeStatus = when (json.str("mergeStatus")) {
                "succeeded" -> MergeStatus.CLEAN
                "conflicts" -> MergeStatus.CONFLICTS
                "rejectedByPolicy", "failure" -> MergeStatus.BLOCKED
                else -> MergeStatus.UNKNOWN
            },
            autoComplete = json["autoCompleteSetBy"] != null,
            headSha = json.str("lastMergeSourceCommit", "commitId").ifBlank { null },
            fromFork = json["forkSource"] != null,
        )
    }

    private fun toVote(value: Int): Vote = when {
        value >= 10 -> Vote.APPROVED
        value >= 5 -> Vote.APPROVED_WITH_SUGGESTIONS
        value <= -10 -> Vote.REJECTED
        value <= -5 -> Vote.WAITING
        else -> Vote.NONE
    }

    private fun toUser(json: JsonElement?): User =
        User(json.str("id"), json.str("displayName"), json.str("uniqueName"), json.str("imageUrl").ifBlank { null })

    // ---------------------------------------------------------------- pipelines

    override fun listPipelineRuns(repo: RepoRef, branch: String?): List<PipelineRun> =
        api.get(
            "${project(repo)}/_apis/build/builds",
            "repositoryId" to meta(repo).str("id"),
            "repositoryType" to "TfsGit",
            "branchName" to branch?.let { HEADS + it },
            "queryOrder" to "queueTimeDescending",
            "\$top" to PAGE,
            version,
        ).list("value").map { build ->
            PipelineRun(
                id = build.str("id"),
                name = "${build.str("definition", "name")} #${build.str("buildNumber")}",
                state = buildState(build.str("status"), build.str("result")),
                branch = build.str("sourceBranch").removePrefix(HEADS),
                webUrl = build.str("_links", "web", "href").ifBlank { "$serverUrl/${project(repo)}/_build/results?buildId=${build.str("id")}" },
                title = build.str("triggerInfo", "ci.message").ifBlank { build.str("triggerInfo", "pr.title") }.lineSequence().firstOrNull().orEmpty(),
                commitSha = build.str("sourceVersion"),
                triggeredBy = build.str("requestedFor", "displayName"),
                definitionId = build.str("definition", "id"),
                startedAt = build.instant("startTime") ?: build.instant("queueTime"),
                finishedAt = build.instant("finishTime"),
            )
        }

    private fun buildState(status: String, result: String): CheckState = when (status) {
        "completed" -> when (result) {
            "succeeded", "partiallySucceeded" -> CheckState.SUCCESS
            "failed" -> CheckState.FAILURE
            "canceled" -> CheckState.CANCELLED
            else -> CheckState.UNKNOWN
        }
        "inProgress", "cancelling" -> CheckState.RUNNING
        "notStarted", "postponed" -> CheckState.PENDING
        else -> CheckState.UNKNOWN
    }

    override fun listPipelineDefinitions(repo: RepoRef): List<PipelineDefinition> {
        fun query(vararg extra: Pair<String, Any?>) =
            api.get("${project(repo)}/_apis/build/definitions", *extra, version).list("value")
                .map { PipelineDefinition(it.str("id"), it.str("name")) }
        // Pipelines of this repository first; some pipelines build a repository they are not attached to.
        return query("repositoryId" to meta(repo).str("id"), "repositoryType" to "TfsGit").ifEmpty { query() }
            .sortedBy { it.name.lowercase() }
    }

    override fun runPipeline(repo: RepoRef, definition: PipelineDefinition, branch: String) {
        val body = jsonOf("definition" to jsonOf("id" to definition.id.toIntOrNull()), "sourceBranch" to HEADS + branch)
        api.post("${project(repo)}/_apis/build/builds", body, version)
    }

    override fun rerunPipeline(repo: RepoRef, run: PipelineRun) {
        val body = jsonOf(
            "definition" to jsonOf("id" to run.definitionId.toIntOrNull()),
            "sourceBranch" to (if (run.branch.startsWith("refs/")) run.branch else HEADS + run.branch),
            "sourceVersion" to run.commitSha.ifBlank { null },
        )
        api.post("${project(repo)}/_apis/build/builds", body, version)
    }

    override fun cancelPipeline(repo: RepoRef, run: PipelineRun) {
        api.patch("${project(repo)}/_apis/build/builds/${run.id}", jsonOf("status" to "cancelling"), version)
    }

    override fun listJobs(repo: RepoRef, run: PipelineRun): List<PipelineJob> {
        val records = api.get("${project(repo)}/_apis/build/builds/${run.id}/timeline", version).list("records")
        val byId = records.associateBy { it.str("id") }
        fun stageOf(record: JsonElement): String {
            var current: JsonElement? = record
            repeat(6) {
                current = byId[current.str("parentId")] ?: return ""
                if (current.str("type") == "Stage") return current.str("name")
            }
            return ""
        }
        return records.filter { it.str("type") == "Job" }
            .sortedWith(compareBy({ it.str("startTime").ifBlank { "9" } }, { it.int("order") ?: 0 }))
            .map { job ->
                PipelineJob(
                    id = job.str("id"),
                    name = job.str("name"),
                    state = timelineState(job.str("state"), job.str("result")),
                    stage = stageOf(job).takeUnless { it == "__default" }.orEmpty(),
                    webUrl = "${run.webUrl}&view=logs&j=${job.str("id")}",
                    logRef = job.str("log", "id").ifBlank { null },
                    startedAt = job.instant("startTime"),
                    finishedAt = job.instant("finishTime"),
                )
            }
    }

    private fun timelineState(state: String, result: String): CheckState = when (state) {
        "completed" -> when (result) {
            "succeeded", "succeededWithIssues" -> CheckState.SUCCESS
            "failed" -> CheckState.FAILURE
            "canceled", "abandoned" -> CheckState.CANCELLED
            "skipped" -> CheckState.SKIPPED
            else -> CheckState.UNKNOWN
        }
        "inProgress" -> CheckState.RUNNING
        "pending" -> CheckState.PENDING
        else -> CheckState.UNKNOWN
    }

    override fun jobLog(repo: RepoRef, run: PipelineRun, job: PipelineJob): String {
        val log = job.logRef ?: return "No log is available for this job yet."
        return api.getText("${project(repo)}/_apis/build/builds/${run.id}/logs/$log", version)
    }

    // ---------------------------------------------------------------- work items

    override fun listIssues(repo: RepoRef, filter: IssueFilter, query: String): List<Issue> {
        val conditions = mutableListOf("[System.TeamProject] = @project")
        val openOnly = "[System.State] NOT IN (${CLOSED_STATES.joinToString(", ") { "'$it'" }})"
        when (filter) {
            IssueFilter.ASSIGNED_TO_ME -> { conditions += "[System.AssignedTo] = @Me"; conditions += openOnly }
            IssueFilter.CREATED_BY_ME -> { conditions += "[System.CreatedBy] = @Me"; conditions += openOnly }
            IssueFilter.OPEN -> conditions += openOnly
            IssueFilter.ALL -> Unit
        }
        val text = query.trim()
        if (text.isNotEmpty()) {
            val title = "[System.Title] CONTAINS '${text.replace("'", "''")}'"
            conditions += text.trimStart('#').toIntOrNull()?.let { "([System.Id] = $it OR $title)" } ?: title
        }
        val wiql = "SELECT [System.Id] FROM WorkItems WHERE ${conditions.joinToString(" AND ")} ORDER BY [System.ChangedDate] DESC"
        val ids = api.post("${project(repo)}/_apis/wit/wiql", jsonOf("query" to wiql), "\$top" to PAGE, version)
            .list("workItems").map { it.str("id") }
        if (ids.isEmpty()) return emptyList()
        val items = api.get("_apis/wit/workitems", "ids" to ids.joinToString(","), "fields" to FIELDS.joinToString(","), version)
            .list("value").associateBy { it.str("id") }
        return ids.mapNotNull { items[it] }.map { toIssue(repo, it) }
    }

    override fun getIssue(repo: RepoRef, id: String): Issue =
        toIssue(repo, api.get("_apis/wit/workitems/$id", version))

    override fun issueTypes(repo: RepoRef): List<String> =
        api.get("${project(repo)}/_apis/wit/workitemtypes", version).list("value")
            .filter { !it.bool("isDisabled") }
            .map { it.str("name") }
            .filter { it !in HIDDEN_TYPES }

    override fun createIssue(repo: RepoRef, issue: NewIssue): Issue {
        val type = issue.type?.takeIf { it.isNotBlank() } ?: "Task"
        val fields = buildList {
            add("System.Title" to issue.title)
            if (issue.description.isNotBlank()) add("System.Description" to issue.description.replace("\n", "<br/>"))
            if (issue.assignToMe) add("System.AssignedTo" to currentUser().let { it.login.ifBlank { it.name } })
            if (issue.labels.isNotEmpty()) add("System.Tags" to issue.labels.joinToString("; "))
        }
        val created = patchDocument("POST", "${project(repo)}/_apis/wit/workitems/\$${urlEncode(type)}", fields)
        return toIssue(repo, created)
    }

    override fun issueStates(repo: RepoRef, issue: Issue): List<String> {
        if (issue.type.isBlank()) return emptyList()
        return api.get("${project(repo)}/_apis/wit/workitemtypes/${urlEncode(issue.type)}/states", version)
            .list("value").map { it.str("name") }.filter { it != issue.state }
    }

    override fun setIssueState(repo: RepoRef, issue: Issue, state: String) {
        patchDocument("PATCH", "_apis/wit/workitems/${issue.id}", listOf("System.State" to state))
    }

    override fun addIssueComment(repo: RepoRef, issue: Issue, body: String) {
        api.post("${project(repo)}/_apis/wit/workItems/${issue.id}/comments", jsonOf("text" to body), "api-version" to "$API_VERSION-preview.3")
    }

    /** Work item writes use JSON Patch documents with their own media type. */
    private fun patchDocument(method: String, path: String, fields: List<Pair<String, String>>): JsonElement {
        val document = jsonArrayOf(fields.map { (field, value) -> jsonOf("op" to "add", "path" to "/fields/$field", "value" to value) })
        val response = api.send(method, path, document.toString(), listOf(version), mapOf("Content-Type" to "application/json-patch+json"))
        return parseJson(response.body)
    }

    private fun toIssue(repo: RepoRef, json: JsonElement): Issue {
        val fields = json["fields"]
        val id = json.str("id")
        val state = fields.str("System.State")
        return Issue(
            id = id,
            title = fields.str("System.Title"),
            description = fields.str("System.Description"),
            state = state,
            open = state !in CLOSED_STATES,
            webUrl = "$serverUrl/${project(repo)}/_workitems/edit/$id",
            type = fields.str("System.WorkItemType"),
            author = fields["System.CreatedBy"]?.let { toUser(it) },
            assignee = fields["System.AssignedTo"]?.let { toUser(it) },
            labels = fields.str("System.Tags").split(';').map { it.trim() }.filter { it.isNotEmpty() },
            updatedAt = fields.instant("System.ChangedDate"),
            htmlDescription = true,
        )
    }

    companion object {
        /** 7.0 is served by Azure DevOps Services and by Azure DevOps Server 2022. */
        const val API_VERSION = "7.0"
        private const val HEADS = "refs/heads/"
        private const val PAGE = 100
        private const val MAX_TITLE = 400
        private const val MAX_DESCRIPTION = 4000
        private const val EMPTY_GUID = "00000000-0000-0000-0000-000000000000"
        private val CLOSED_STATES = setOf("Closed", "Done", "Removed", "Completed", "Inactive")
        private val FIELDS = listOf(
            "System.Id", "System.Title", "System.State", "System.WorkItemType", "System.AssignedTo",
            "System.CreatedBy", "System.ChangedDate", "System.Tags", "System.Description",
        )
        private val HIDDEN_TYPES = setOf(
            "Code Review Request", "Code Review Response", "Feedback Request", "Feedback Response",
            "Shared Steps", "Shared Parameter", "Test Plan", "Test Suite", "Test Case",
        )
    }
}
