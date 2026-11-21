package io.github.purgator.gitops.providers

import io.github.purgator.gitops.FakeTransport
import io.github.purgator.gitops.FixedSecret
import io.github.purgator.gitops.core.http.parseJson
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.MergeStatus
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.providers.github.GitHubClient
import io.github.purgator.gitops.providers.github.GitHubProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubProviderTest {
    private val repo = RepoRef("github", "https://github.com", "acme", "widgets")
    private val transport = FakeTransport()
    private val client = GitHubClient("https://github.com", transport, FixedSecret())

    private fun parse(url: String, servers: List<String> = emptyList()) =
        GitHubProvider.parseRemote(GitUrl.parse(url)!!, servers)

    private fun prJson(
        number: Int = 7,
        state: String = "open",
        draft: Boolean = false,
        mergedAt: String? = null,
        extra: String = "",
    ) = """
        {"number":$number,"node_id":"PR_node","title":"Add widgets","body":"Body","state":"$state","draft":$draft,
         "merged_at":${mergedAt?.let { "\"$it\"" } ?: "null"},
         "html_url":"https://github.com/acme/widgets/pull/$number",
         "user":{"login":"octo","avatar_url":"https://a/1"},
         "created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-03T03:04:05Z",
         "head":{"ref":"feature/x","sha":"abc123","repo":{"full_name":"acme/widgets"}},
         "base":{"ref":"main","repo":{"full_name":"acme/widgets"}},
         "labels":[{"name":"bug"}],
         "requested_reviewers":[{"login":"hubot"}]$extra}
    """.trimIndent()

    @Test
    fun `parses github remotes in every flavour`() {
        val expected = RepoRef("github", "https://github.com", "acme", "widgets")
        assertEquals(expected, parse("https://github.com/acme/widgets.git"))
        assertEquals(expected, parse("git@github.com:acme/widgets.git"))
        assertEquals(expected, parse("ssh://git@github.com/acme/widgets"))
        assertEquals(expected, parse("ssh://git@ssh.github.com:443/acme/widgets.git"))
    }

    @Test
    fun `parses enterprise hosts only when an account knows them`() {
        assertNull(parse("https://git.acme.io/team/app.git"))
        assertNull(parse("https://gitlab.com/team/app.git"))
        assertEquals(
            RepoRef("github", "https://git.acme.io", "team", "app"),
            parse("git@git.acme.io:team/app.git", listOf("https://git.acme.io/")),
        )
        assertNull(parse("https://github.com/only-owner"))
    }

    @Test
    fun `api base depends on the server`() {
        assertEquals("https://api.github.com", GitHubProvider.apiBase("https://github.com"))
        assertEquals("https://git.acme.io/api/v3", GitHubProvider.apiBase("git.acme.io"))
        assertEquals("https://git.acme.io/api/graphql", GitHubProvider.graphqlUrl("https://git.acme.io/"))
    }

    @Test
    fun `maps pull request details with reviews and merge state`() {
        transport.on("GET", "/pulls/7", prJson(extra = ""","mergeable_state":"dirty","comments":2,"review_comments":3,"auto_merge":{"merge_method":"squash"}"""))
        transport.on(
            "GET", "/pulls/7/reviews",
            """[{"id":1,"state":"CHANGES_REQUESTED","submitted_at":"2026-01-02T00:00:00Z","user":{"login":"alice"}},
                {"id":2,"state":"APPROVED","submitted_at":"2026-01-03T00:00:00Z","user":{"login":"alice"}},
                {"id":3,"state":"COMMENTED","submitted_at":"2026-01-04T00:00:00Z","user":{"login":"bob"}}]""",
        )
        val pr = client.getPullRequest(repo, "7")
        assertEquals("7", pr.id)
        assertEquals(PrState.OPEN, pr.state)
        assertEquals("feature/x", pr.sourceBranch)
        assertEquals("main", pr.targetBranch)
        assertEquals("octo", pr.author.id)
        assertEquals(MergeStatus.CONFLICTS, pr.mergeStatus)
        assertEquals(5, pr.commentCount)
        assertTrue(pr.autoComplete)
        assertFalse(pr.fromFork)
        assertEquals("abc123", pr.headSha)
        assertEquals(listOf("bug"), pr.labels)
        assertEquals(mapOf("alice" to Vote.APPROVED, "hubot" to Vote.NONE), pr.reviewers.associate { it.user.login to it.vote })

        val request = transport.last("GET", "/pulls/7")
        assertEquals("Bearer t0ken", request.headers["Authorization"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertTrue(request.url.startsWith("https://api.github.com/repos/acme/widgets/"))
    }

    @Test
    fun `maps draft merged and closed states and filters the closed list`() {
        transport.on(
            "GET", "/pulls?",
            "[${prJson(1, draft = true)},${prJson(2, state = "closed", mergedAt = "2026-01-05T00:00:00Z")},${prJson(3, state = "closed")}]",
        )
        assertEquals(listOf(PrState.DRAFT, PrState.MERGED, PrState.CLOSED), client.listPullRequests(repo, PrFilter.ALL).map { it.state })
        assertTrue("state=all" in transport.last("GET", "/pulls?").url)

        assertEquals(listOf("2"), client.listPullRequests(repo, PrFilter.MERGED).map { it.id })
        assertTrue("state=closed" in transport.last("GET", "/pulls?").url)
        assertEquals(listOf("3"), client.listPullRequests(repo, PrFilter.CLOSED).map { it.id })

        client.listPullRequests(repo, PrFilter.OPEN)
        assertTrue("state=open" in transport.last("GET", "/pulls?").url)
    }

    @Test
    fun `my pull requests go through search then details`() {
        transport.on("GET", "/search/issues", """{"items":[{"number":7},{"number":8}]}""")
        transport.on("GET", "/pulls/7", prJson(7))
        transport.on("GET", "/pulls/8", prJson(8))
        transport.on("GET", "/reviews", "[]")
        val prs = client.listPullRequests(repo, PrFilter.MINE)
        assertEquals(listOf("7", "8"), prs.map { it.id })
        assertEquals("feature/x", prs.first().sourceBranch)
        val search = transport.last("GET", "/search/issues").url
        assertTrue("repo%3Aacme%2Fwidgets" in search, search)
        assertTrue("author%3A%40me" in search, search)

        client.listPullRequests(repo, PrFilter.REVIEWING)
        assertTrue("review-requested%3A%40me" in transport.last("GET", "/search/issues").url)
    }

    @Test
    fun `creates a pull request then adds reviewers and labels`() {
        transport.on("POST", "/pulls", prJson(9))
        transport.on("POST", "/pulls/9/requested_reviewers", "{}")
        transport.on("POST", "/issues/9/labels", "[]")
        transport.on("GET", "/pulls/9", prJson(9))
        transport.on("GET", "/pulls/9/reviews", "[]")

        val created = client.createPullRequest(
            repo,
            NewPullRequest(
                title = "Add widgets",
                description = "Body",
                sourceBranch = "feature/x",
                targetBranch = "main",
                draft = true,
                reviewers = listOf(User("hubot", "Hubot", "hubot")),
                labels = listOf("bug"),
                linkedIssues = listOf("12", "#13"),
            ),
        )
        assertEquals("9", created.id)

        val body = parseJson(transport.requests.first { it.method == "POST" && it.url.endsWith("/repos/acme/widgets/pulls") }.body!!)
        assertEquals("Add widgets", body.str("title"))
        assertEquals("feature/x", body.str("head"))
        assertEquals("main", body.str("base"))
        assertEquals("true", body.str("draft"))
        assertEquals("Body\n\nCloses #12\nCloses #13", body.str("body"))

        assertEquals("""{"reviewers":["hubot"]}""", transport.last("POST", "/requested_reviewers").body)
        assertEquals("""{"labels":["bug"]}""", transport.last("POST", "/issues/9/labels").body)
        assertEquals(0, transport.count("POST", "/graphql"))
    }

    @Test
    fun `maps workflow run states`() {
        fun run(id: Int, status: String, conclusion: String?) =
            """{"id":$id,"name":"CI","run_number":$id,"status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},
               "head_branch":"main","head_sha":"abc","html_url":"https://github.com/acme/widgets/actions/runs/$id",
               "display_title":"Fix","workflow_id":42,"actor":{"login":"octo"},"created_at":"2026-01-02T03:04:05Z"}"""
        transport.on(
            "GET", "/actions/runs",
            """{"workflow_runs":[${run(1, "completed", "success")},${run(2, "completed", "failure")},${run(3, "completed", "cancelled")},
               ${run(4, "completed", "skipped")},${run(5, "in_progress", null)},${run(6, "queued", null)},${run(7, "completed", "timed_out")}]}""",
        )
        val runs = client.listPipelineRuns(repo, "main")
        assertEquals(
            listOf(
                CheckState.SUCCESS, CheckState.FAILURE, CheckState.CANCELLED, CheckState.SKIPPED,
                CheckState.RUNNING, CheckState.PENDING, CheckState.FAILURE,
            ),
            runs.map { it.state },
        )
        assertEquals("CI #1", runs.first().name)
        assertEquals("42", runs.first().definitionId)
        assertEquals("octo", runs.first().triggeredBy)
        assertTrue("branch=main" in transport.last("GET", "/actions/runs").url)
    }

    @Test
    fun `builds web urls`() {
        assertEquals(
            "https://github.com/acme/widgets/blob/main/src/App.kt#L3-L5",
            GitHubProvider.fileUrl(repo, "main", "src/App.kt", 2..4),
        )
        assertEquals("https://github.com/acme/widgets/blob/main/src/App.kt#L3", GitHubProvider.fileUrl(repo, "main", "/src/App.kt", 2..2))
        assertEquals("https://github.com/acme/widgets/blob/feature/x/README.md", GitHubProvider.fileUrl(repo, "feature/x", "README.md"))
        assertEquals("https://github.com/acme/widgets/commit/abc", GitHubProvider.commitUrl(repo, "abc"))
        assertEquals(
            "https://github.com/acme/widgets/compare/main...feature/x?expand=1",
            GitHubProvider.newPullRequestUrl(repo, "feature/x", "main"),
        )
        assertEquals("https://github.com/acme/widgets/compare/feature/x?expand=1", GitHubProvider.newPullRequestUrl(repo, "feature/x", null))
    }
}
