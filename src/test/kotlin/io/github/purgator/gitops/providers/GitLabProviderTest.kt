package io.github.purgator.gitops.providers

import io.github.purgator.gitops.FakeTransport
import io.github.purgator.gitops.FixedSecret
import io.github.purgator.gitops.core.http.at
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.parseJson
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStatus
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequestUpdate
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.providers.gitlab.GitLabClient
import io.github.purgator.gitops.providers.gitlab.GitLabProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabProviderTest {
    private val repo = RepoRef("gitlab", "https://gitlab.com", "acme/platform", "api")
    private val projectPath = "/api/v4/projects/acme%2Fplatform%2Fapi"

    private fun parse(url: String, servers: List<String> = emptyList()) =
        GitLabProvider.parseRemote(GitUrl.parse(url)!!, servers)

    private fun client(transport: FakeTransport) = GitLabClient("https://gitlab.com", transport, FixedSecret())

    private fun mr(extra: String = "") = """{
        "iid": 7, "id": 9001, "title": "Add cache", "description": "Body", "state": "opened",
        "author": {"id": 1, "name": "Ada", "username": "ada"},
        "source_branch": "feature/cache", "target_branch": "main",
        "web_url": "https://gitlab.com/acme/platform/api/-/merge_requests/7",
        "created_at": "2026-01-02T03:04:05.000Z", "updated_at": "2026-01-03T03:04:05.000+01:00",
        "reviewers": [{"id": 2, "name": "Bob", "username": "bob"}],
        "labels": ["backend", "perf"], "user_notes_count": 3, "sha": "abc123",
        "source_project_id": 5, "target_project_id": 5 $extra }"""

    // ------------------------------------------------------------ remote parsing

    @Test
    fun `parses https remote`() {
        val ref = parse("https://gitlab.com/acme/api.git")!!
        assertEquals(RepoRef("gitlab", "https://gitlab.com", "acme", "api"), ref)
    }

    @Test
    fun `parses scp-like ssh remote`() {
        val ref = parse("git@gitlab.com:acme/api.git")!!
        assertEquals("https://gitlab.com", ref.serverUrl)
        assertEquals("acme", ref.owner)
        assertEquals("api", ref.name)
    }

    @Test
    fun `keeps nested subgroups in the owner`() {
        val ref = parse("https://gitlab.com/acme/platform/team/api.git")!!
        assertEquals("acme/platform/team", ref.owner)
        assertEquals("api", ref.name)
        assertEquals("acme/platform/team/api", ref.fullName)
    }

    @Test
    fun `recognises self-managed host from known servers`() {
        assertNull(parse("https://code.acme.io/team/api.git"))
        val ref = parse("https://code.acme.io/team/api.git", listOf("https://code.acme.io/"))!!
        assertEquals("https://code.acme.io", ref.serverUrl)
        val ssh = parse("ssh://git@code.acme.io:2222/team/api.git", listOf("http://code.acme.io:8080"))!!
        assertEquals("http://code.acme.io:8080", ssh.serverUrl)
        assertEquals("team", ssh.owner)
    }

    @Test
    fun `recognises gitlab-prefixed hosts and rejects others`() {
        assertNotNull(parse("git@gitlab.acme.io:team/api.git"))
        assertNull(parse("https://github.com/acme/api.git"))
        assertNull(parse("https://gitlab.com/onlyone"))
    }

    // ------------------------------------------------------------ merge request mapping

    @Test
    fun `maps an open merge request`() {
        val transport = FakeTransport().on("GET", "$projectPath/merge_requests", "[${mr()}]")
        val pr = client(transport).listPullRequests(repo, PrFilter.OPEN).single()
        assertEquals("7", pr.id)
        assertEquals(PrState.OPEN, pr.state)
        assertEquals("ada", pr.author.login)
        assertEquals("feature/cache", pr.sourceBranch)
        assertEquals(listOf("backend", "perf"), pr.labels)
        assertEquals(3, pr.commentCount)
        assertEquals("abc123", pr.headSha)
        assertFalse(pr.fromFork)
        assertEquals("Bob", pr.reviewers.single().user.name)
        assertEquals(Vote.NONE, pr.reviewers.single().vote)
        assertNotNull(pr.createdAt)
        assertNotNull(pr.updatedAt)
        assertEquals("Bearer t0ken", transport.requests.last().headers["Authorization"])
    }

    @Test
    fun `maps draft merged closed and conflicting states`() {
        val body = "[" + listOf(
            mr(""", "draft": true, "detailed_merge_status": "draft_status" """),
            mr(""", "state": "merged" """),
            mr(""", "state": "closed", "work_in_progress": true """),
            mr(""", "has_conflicts": true, "detailed_merge_status": "conflict", "source_project_id": 99 """),
            mr(""", "detailed_merge_status": "mergeable", "merge_when_pipeline_succeeds": true, "head_pipeline": {"status": "running"} """),
            mr(""", "detailed_merge_status": "not_approved" """),
        ).joinToString(",") + "]"
        val prs = client(FakeTransport().on("GET", "$projectPath/merge_requests", body)).listPullRequests(repo, PrFilter.ALL)
        assertEquals(PrState.DRAFT, prs[0].state)
        assertEquals(MergeStatus.BLOCKED, prs[0].mergeStatus)
        assertEquals(PrState.MERGED, prs[1].state)
        assertEquals(PrState.CLOSED, prs[2].state)
        assertEquals(MergeStatus.CONFLICTS, prs[3].mergeStatus)
        assertTrue(prs[3].fromFork)
        assertEquals(MergeStatus.CLEAN, prs[4].mergeStatus)
        assertTrue(prs[4].autoComplete)
        assertEquals(CheckState.RUNNING, prs[4].checkState)
        assertEquals(MergeStatus.BLOCKED, prs[5].mergeStatus)
        assertNull(prs[5].checkState)
    }

    @Test
    fun `get merges approvals into reviewers`() {
        val transport = FakeTransport()
            .on("GET", "$projectPath/merge_requests/7", mr())
            .on("GET", "$projectPath/merge_requests/7/approvals",
                """{"approved_by": [{"user": {"id": 2, "name": "Bob", "username": "bob"}}, {"user": {"id": 3, "name": "Cy", "username": "cy"}}]}""")
        val pr = client(transport).getPullRequest(repo, "7")
        assertEquals(listOf("Bob", "Cy"), pr.reviewers.map { it.user.name })
        assertTrue(pr.reviewers.all { it.vote == Vote.APPROVED })
    }

    // ------------------------------------------------------------ requests

    @Test
    fun `list filters translate to query parameters`() {
        val transport = FakeTransport()
            .on("GET", "/api/v4/user", """{"id": 1, "name": "Ada", "username": "ada"}""")
            .on("GET", "$projectPath/merge_requests", "[]")
        val client = client(transport)

        client.listPullRequests(repo, PrFilter.MINE)
        assertTrue("state=opened" in transport.requests.last().url)
        assertTrue("scope=created_by_me" in transport.requests.last().url)

        client.listPullRequests(repo, PrFilter.REVIEWING)
        assertTrue("reviewer_username=ada" in transport.requests.last().url)
        assertTrue("scope=all" in transport.requests.last().url)

        client.listPullRequests(repo, PrFilter.MERGED)
        assertTrue("state=merged" in transport.requests.last().url)
        client.listPullRequests(repo, PrFilter.CLOSED)
        assertTrue("state=closed" in transport.requests.last().url)
        client.listPullRequests(repo, PrFilter.ALL)
        val url = transport.requests.last().url
        assertTrue("state=all" in url && "per_page=50" in url && "order_by=updated_at" in url)
        assertFalse("reviewer_username" in url)
    }

    @Test
    fun `create sends the expected body`() {
        val transport = FakeTransport().on("POST", "$projectPath/merge_requests", mr())
        val created = client(transport).createPullRequest(
            repo,
            NewPullRequest(
                title = "Add cache",
                description = "Body",
                sourceBranch = "feature/cache",
                targetBranch = "main",
                draft = true,
                reviewers = listOf(User("2", "Bob", "bob")),
                labels = listOf("backend", "perf"),
                linkedIssues = listOf("12", "#13"),
            ),
        )
        assertEquals("7", created.id)
        val body = parseJson(transport.last("POST", "/merge_requests").body!!)
        assertEquals("feature/cache", body.str("source_branch"))
        assertEquals("main", body.str("target_branch"))
        assertEquals("Draft: Add cache", body.str("title"))
        assertEquals("Body\n\nCloses #12\nCloses #13", body.str("description"))
        assertEquals(listOf("2"), body.list("reviewer_ids").map { it.asString })
        assertEquals("backend,perf", body.str("labels"))
        assertNull(body.at("remove_source_branch"))
        assertEquals(0, transport.count("PUT", "/merge"))
    }

    @Test
    fun `create with auto-complete arms auto-merge and survives its failure`() {
        val transport = FakeTransport().on("POST", "$projectPath/merge_requests", mr())
            .on("PUT", "$projectPath/merge_requests/7/merge", """{"message":"405 Method Not Allowed"}""", status = 405)
        val created = client(transport).createPullRequest(
            repo,
            NewPullRequest("T", "", "feature/cache", "main", autoComplete = MergeOptions(MergeStrategy.SQUASH, deleteSourceBranch = true)),
        )
        assertEquals("7", created.id)
        val post = parseJson(transport.last("POST", "/merge_requests").body!!)
        assertTrue(post.bool("remove_source_branch"))
        assertTrue(post.bool("squash"))
        val put = parseJson(transport.last("PUT", "/merge").body!!)
        assertTrue(put.bool("merge_when_pipeline_succeeds"))
        assertTrue(put.bool("should_remove_source_branch"))
    }

    @Test
    fun `draft toggling rewrites the title prefix`() {
        val transport = FakeTransport().on("PUT", "$projectPath/merge_requests/7", mr())
        val client = client(transport)
        val open = client.listPullRequestsStub()
        client.updatePullRequest(repo, open, PullRequestUpdate(draft = true))
        assertEquals("Draft: Add cache", parseJson(transport.requests.last().body!!).str("title"))

        client.updatePullRequest(repo, open.copy(title = "WIP: [Draft] Add cache", state = PrState.DRAFT), PullRequestUpdate(draft = false))
        assertEquals("Add cache", parseJson(transport.requests.last().body!!).str("title"))

        client.updatePullRequest(repo, open, PullRequestUpdate(description = "new"))
        val body = parseJson(transport.requests.last().body!!)
        assertNull(body.at("title"))
        assertEquals("new", body.str("description"))
    }

    private fun GitLabClient.listPullRequestsStub() =
        GitLabClient("https://gitlab.com", FakeTransport().on("GET", "/merge_requests", "[${mr()}]"), FixedSecret())
            .listPullRequests(repo, PrFilter.OPEN).single()

    // ------------------------------------------------------------ pipelines

    @Test
    fun `maps pipeline states`() {
        val statuses = listOf(
            "success" to CheckState.SUCCESS, "failed" to CheckState.FAILURE, "canceled" to CheckState.CANCELLED,
            "canceling" to CheckState.CANCELLED, "skipped" to CheckState.SKIPPED, "running" to CheckState.RUNNING,
            "created" to CheckState.PENDING, "waiting_for_resource" to CheckState.PENDING, "preparing" to CheckState.PENDING,
            "pending" to CheckState.PENDING, "manual" to CheckState.PENDING, "scheduled" to CheckState.PENDING,
            "whatever" to CheckState.UNKNOWN,
        )
        val body = statuses.mapIndexed { i, (status, _) ->
            """{"id": ${100 + i}, "iid": ${i + 1}, "status": "$status", "ref": "main", "sha": "s$i",
                "web_url": "https://gitlab.com/p/$i", "created_at": "2026-01-02T03:04:05Z", "updated_at": "2026-01-02T04:04:05Z"}"""
        }.joinToString(",", "[", "]")
        val transport = FakeTransport().on("GET", "$projectPath/pipelines", body)
        val runs = client(transport).listPipelineRuns(repo, "main")
        assertEquals(statuses.map { it.second }, runs.map { it.state })
        assertEquals("Pipeline #1", runs[0].name)
        assertEquals("100", runs[0].id)
        assertEquals("main", runs[0].branch)
        assertNotNull(runs[0].finishedAt)
        assertNull(runs[5].finishedAt)
        assertTrue("ref=main" in transport.requests.last().url)
    }

    // ------------------------------------------------------------ urls

    @Test
    fun `builds web urls`() {
        assertEquals(
            "https://gitlab.com/acme/platform/api/-/blob/main/src/App.kt",
            GitLabProvider.fileUrl(repo, "main", "src/App.kt"),
        )
        assertEquals(
            "https://gitlab.com/acme/platform/api/-/blob/main/src/App.kt#L10",
            GitLabProvider.fileUrl(repo, "main", "/src/App.kt", 9..9),
        )
        assertEquals(
            "https://gitlab.com/acme/platform/api/-/blob/feature/x/src/My%20File.kt#L3-5",
            GitLabProvider.fileUrl(repo, "feature/x", "src/My File.kt", 2..4),
        )
        assertEquals("https://gitlab.com/acme/platform/api/-/commit/abc", GitLabProvider.commitUrl(repo, "abc"))
        assertEquals(
            "https://gitlab.com/acme/platform/api/-/merge_requests/new?merge_request%5Bsource_branch%5D=feature%2Fx&merge_request%5Btarget_branch%5D=main",
            GitLabProvider.newPullRequestUrl(repo, "feature/x", "main"),
        )
        assertEquals(
            "https://gitlab.acme.io/-/user_settings/personal_access_tokens?name=GitOps+Studio&scopes=api",
            GitLabProvider.tokenCreationUrl("gitlab.acme.io/"),
        )
    }
}
