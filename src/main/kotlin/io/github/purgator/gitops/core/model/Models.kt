package io.github.purgator.gitops.core.model

import java.time.Instant

/** Provider-neutral domain model shared by every hosting module and by the UI. */

data class User(
    val id: String,
    val name: String,
    val login: String = "",
    val avatarUrl: String? = null,
) {
    val label: String get() = name.ifBlank { login.ifBlank { id } }
    override fun toString(): String = label
}

/** A repository on a hosting service, resolved from a git remote URL. */
data class RepoRef(
    val providerId: String,
    /** Web base URL of the server or organisation, e.g. `https://github.com` or `https://dev.azure.com/acme`. */
    val serverUrl: String,
    /** Owner, workspace or group path. For Azure DevOps: the organisation / collection. */
    val owner: String,
    val name: String,
    /** Azure DevOps project; empty elsewhere. */
    val project: String = "",
) {
    val fullName: String
        get() = listOf(owner, project, name).filter { it.isNotBlank() }.joinToString("/")
}

enum class PrState(val label: String) { OPEN("Open"), DRAFT("Draft"), MERGED("Merged"), CLOSED("Closed") }

enum class Vote(val label: String) {
    APPROVED("Approved"),
    APPROVED_WITH_SUGGESTIONS("Approved with suggestions"),
    NONE("No vote"),
    WAITING("Waiting for author"),
    REJECTED("Changes requested"),
}

data class Reviewer(val user: User, val vote: Vote = Vote.NONE, val required: Boolean = false)

enum class CheckState(val label: String) {
    SUCCESS("Succeeded"),
    FAILURE("Failed"),
    PENDING("Queued"),
    RUNNING("Running"),
    CANCELLED("Cancelled"),
    SKIPPED("Skipped"),
    UNKNOWN("Unknown");

    val finished: Boolean get() = this == SUCCESS || this == FAILURE || this == CANCELLED || this == SKIPPED

    companion object {
        /** Worst state wins; used to summarise several checks into one badge. */
        fun summarize(states: Collection<CheckState>): CheckState? = when {
            states.isEmpty() -> null
            states.any { it == FAILURE } -> FAILURE
            states.any { it == RUNNING } -> RUNNING
            states.any { it == PENDING } -> PENDING
            states.any { it == CANCELLED } -> CANCELLED
            states.any { it == SUCCESS } -> SUCCESS
            states.all { it == SKIPPED } -> SKIPPED
            else -> UNKNOWN
        }
    }
}

data class Check(val name: String, val state: CheckState, val url: String? = null, val description: String = "")

enum class MergeStatus(val label: String) {
    CLEAN("No conflicts"),
    CONFLICTS("Has conflicts"),
    BLOCKED("Blocked"),
    UNKNOWN(""),
}

data class PullRequest(
    /** Identifier used in API calls (number, iid, …). */
    val id: String,
    val title: String,
    val description: String,
    val state: PrState,
    val author: User,
    val sourceBranch: String,
    val targetBranch: String,
    val webUrl: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val reviewers: List<Reviewer> = emptyList(),
    val labels: List<String> = emptyList(),
    val mergeStatus: MergeStatus = MergeStatus.UNKNOWN,
    val checkState: CheckState? = null,
    val commentCount: Int? = null,
    val autoComplete: Boolean = false,
    val headSha: String? = null,
    /** True when the source branch lives in another repository (a fork). */
    val fromFork: Boolean = false,
) {
    val isOpen: Boolean get() = state == PrState.OPEN || state == PrState.DRAFT
    val isDraft: Boolean get() = state == PrState.DRAFT
}

enum class MergeStrategy(val label: String) {
    MERGE("Merge commit"),
    SQUASH("Squash"),
    REBASE("Rebase and fast-forward"),
    REBASE_MERGE("Rebase with merge commit");

    override fun toString(): String = label
}

data class NewPullRequest(
    val title: String,
    val description: String,
    val sourceBranch: String,
    val targetBranch: String,
    val draft: Boolean = false,
    val reviewers: List<User> = emptyList(),
    val labels: List<String> = emptyList(),
    /** Issue / work item ids to link. */
    val linkedIssues: List<String> = emptyList(),
    /** When set, the request completes on its own once policies pass (where supported). */
    val autoComplete: MergeOptions? = null,
)

data class MergeOptions(
    val strategy: MergeStrategy = MergeStrategy.MERGE,
    val deleteSourceBranch: Boolean = false,
    val message: String? = null,
    /** Complete automatically when every policy passes instead of merging right now. */
    val autoComplete: Boolean = false,
    /** Azure DevOps: transition linked work items when the request completes. */
    val completeWorkItems: Boolean = false,
)

data class PullRequestUpdate(
    val title: String? = null,
    val description: String? = null,
    val draft: Boolean? = null,
    val targetBranch: String? = null,
)

enum class PrFilter(val label: String) {
    MINE("Created by me"),
    REVIEWING("Assigned to me"),
    OPEN("All open"),
    MERGED("Merged"),
    CLOSED("Closed / abandoned"),
    ALL("All");

    override fun toString(): String = label
}

data class Comment(val id: String, val author: User, val body: String, val createdAt: Instant? = null)

data class CommentThread(
    val id: String,
    val comments: List<Comment>,
    val filePath: String? = null,
    val line: Int? = null,
    val resolved: Boolean? = null,
)

data class PipelineDefinition(val id: String, val name: String) {
    override fun toString(): String = name
}

data class PipelineRun(
    val id: String,
    /** Display name, e.g. `CI #1234`. */
    val name: String,
    val state: CheckState,
    val branch: String,
    val webUrl: String,
    val title: String = "",
    val commitSha: String = "",
    val triggeredBy: String = "",
    /** Definition / workflow the run belongs to, when the service has such a notion. */
    val definitionId: String = "",
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

/** A stage of a run: a group of jobs that succeeds, fails and can be run again as one. */
data class PipelineStage(
    /** Handle used to rerun the stage. */
    val id: String,
    val name: String,
    val state: CheckState,
)

data class PipelineJob(
    val id: String,
    val name: String,
    val state: CheckState,
    val stage: String = "",
    val webUrl: String? = null,
    /** Provider-specific handle needed to download the log; null when no log is available. */
    val logRef: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

enum class IssueFilter(val label: String) {
    ASSIGNED_TO_ME("Assigned to me"),
    CREATED_BY_ME("Created by me"),
    OPEN("All open"),
    ALL("All");

    override fun toString(): String = label
}

data class Issue(
    val id: String,
    val title: String,
    val description: String,
    /** Raw provider state, e.g. `Active`, `open`, `To Do`. */
    val state: String,
    val open: Boolean,
    val webUrl: String,
    val type: String = "",
    val author: User? = null,
    val assignee: User? = null,
    val labels: List<String> = emptyList(),
    val updatedAt: Instant? = null,
    /** True when [description] is HTML rather than Markdown. */
    val htmlDescription: Boolean = false,
)

data class NewIssue(
    val title: String,
    val description: String,
    val type: String? = null,
    val assignToMe: Boolean = false,
    val labels: List<String> = emptyList(),
)

data class RemoteRepository(
    val fullName: String,
    val httpUrl: String,
    val sshUrl: String,
    val webUrl: String,
    val description: String = "",
    val private: Boolean = false,
)

data class RepoInfo(
    val defaultBranch: String,
    val webUrl: String,
    val mergeStrategies: List<MergeStrategy> = MergeStrategy.entries,
)
