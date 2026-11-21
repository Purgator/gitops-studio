package io.github.purgator.gitops.providers

import io.github.purgator.gitops.FakeTransport
import io.github.purgator.gitops.FixedSecret
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
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.providers.gitea.GiteaClient
import io.github.purgator.gitops.providers.gitea.GiteaProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class GiteaProviderTest {
    private val repo = RepoRef("gitea", "https://codeberg.org", "acme", "widgets")
    private val transport = FakeTransport()
    private val client = GiteaClient("https://codeberg.org", transport, FixedSecret("abc"))

    private fun parse(url: String, known: List<String> = emptyList()) = GiteaProvider.parseRemote(GitUrl.parse(url)!!, known)

    private fun pr(
        number: Int,
        title: String = "Add widgets",
        state: String = "open",
        merged: Boolean = false,
        draft: Boolean = false,
        author: String = "alice",
        reviewers: List<String> = emptyList(),
        extra: String = "",
    ) = """{"number":$number,"title":"$title","body":"desc","state":"$state","merged":$merged,"draft":$draft,
        "user":{"login":"$author","full_name":""},
        "head":{"ref":"feature/x","sha":"abc123","repo":{"full_name":"acme/widgets"}},
        "base":{"ref":"main","repo":{"full_name":"acme/widgets"}},
        "html_url":"https://codeberg.org/acme/widgets/pulls/$number",
        "created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-03T03:04:05+01:00",
        "labels":[{"id":1,"name":"bug"}],"comments":3,
        "requested_reviewers":[${reviewers.joinToString(",") { """{"login":"$it"}""" }}]$extra}"""

    // ---------------------------------------------------------------- remotes

    @Test
    fun `parses codeberg https and ssh remotes`() {
        val expected = RepoRef("gitea", "https://codeberg.org", "acme", "widgets")
        assertEquals(expected, parse("https://codeberg.org/acme/widgets.git"))
        assertEquals(expected, parse("git@codeberg.org:acme/widgets.git"))
        assertEquals(expected, parse("ssh://git@codeberg.org/acme/widgets"))
    }

    @Test
    fun `recognises gitea and forgejo host prefixes`() {
        assertEquals("https://gitea.example.com", parse("https://gitea.example.com/a/b.git")?.serverUrl)
        assertEquals("https://forgejo.example.com", parse("git@forgejo.example.com:a/b.git")?.serverUrl)
    }

    @Test
    fun `self hosted remotes use the known server url`() {
        val known = listOf("http://git.corp.local:3000")
        val expected = RepoRef("gitea", "http://git.corp.local:3000", "team", "app")
        assertEquals(expected, parse("http://git.corp.local:3000/team/app.git", known))
        // The ssh port never matches the web port: the account URL must win.
        assertEquals(expected, parse("ssh://git@git.corp.local:2222/team/app.git", known))
        assertEquals(expected, parse("git@git.corp.local:team/app.git", known))
    }

    @Test
    fun `unknown hosts and incomplete paths are rejected`() {
        assertNull(parse("https://git.corp.local/team/app.git"))
        assertNull(parse("https://github.com/acme/widgets.git"))
        assertNull(parse("https://codeberg.org/acme"))
    }

    @Test
    fun `accounts match on host`() {
        assertTrue(GiteaProvider.accountMatches("https://codeberg.org/", repo))
        assertFalse(GiteaProvider.accountMatches("https://gitea.com", repo))
    }

    // ---------------------------------------------------------------- urls

    @Test
    fun `builds file urls`() {
        assertEquals(
            "https://codeberg.org/acme/widgets/src/branch/feature/x/src/My%20File.kt#L3-L5",
            GiteaProvider.fileUrl(repo, "feature/x", "/src/My File.kt", 2..4),
        )
        assertEquals("https://codeberg.org/acme/widgets/src/branch/main/a.kt#L1", GiteaProvider.fileUrl(repo, "main", "a.kt", 0..0))
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("https://codeberg.org/acme/widgets/src/commit/$sha/a.kt", GiteaProvider.fileUrl(repo, sha, "a.kt"))
        assertEquals("https://codeberg.org/acme/widgets/commit/$sha", GiteaProvider.commitUrl(repo, sha))
    }

    @Test
    fun `builds compare urls and head refs`() {
        assertEquals("https://codeberg.org/acme/widgets/compare/main...feature/x", GiteaProvider.newPullRequestUrl(repo, "feature/x", "main"))
        assertEquals("https://codeberg.org/acme/widgets/compare/feature/x", GiteaProvider.newPullRequestUrl(repo, "feature/x", null))
        assertEquals("https://codeberg.org/user/settings/applications", GiteaProvider.tokenCreationUrl("codeberg.org/"))
    }

    // ---------------------------------------------------------------- pull requests

    @Test
    fun `maps pull request states`() {
        transport.on(
            "GET", "/pulls",
            "[" + listOf(
                pr(1, extra = ""","mergeable":true"""),
                pr(2, title = "WIP: half done"),
                pr(3, title = "draft: lower case"),
                pr(4, title = "[WIP] bracket"),
                pr(5, draft = true, extra = ""","mergeable":false"""),
                pr(6, state = "closed", merged = true),
                pr(7, state = "closed", title = "WIP: abandoned", extra = ""","mergeable":false"""),
            ).joinToString(",") + "]",
        )
        val all = client.listPullRequests(repo, PrFilter.ALL)
        assertEquals(
            listOf(PrState.OPEN, PrState.DRAFT, PrState.DRAFT, PrState.DRAFT, PrState.DRAFT, PrState.MERGED, PrState.CLOSED),
            all.map { it.state },
        )
        val first = all.first()
        assertEquals("1", first.id)
        assertEquals("feature/x", first.sourceBranch)
        assertEquals("main", first.targetBranch)
        assertEquals("alice", first.author.login)
        assertEquals("alice", first.author.name)
        assertEquals(listOf("bug"), first.labels)
        assertEquals(3, first.commentCount)
        assertEquals("abc123", first.headSha)
        assertEquals(MergeStatus.CLEAN, first.mergeStatus)
        assertEquals(MergeStatus.CONFLICTS, all[4].mergeStatus)
        assertEquals(MergeStatus.UNKNOWN, all[6].mergeStatus)
        assertFalse(first.fromFork)
        assertEquals(first.createdAt!!.plusSeconds(82_800), first.updatedAt)

        val request = transport.last("GET", "/pulls")
        assertTrue("https://codeberg.org/api/v1/repos/acme/widgets/pulls?" in request.url)
        assertTrue("state=all" in request.url && "sort=recentupdate" in request.url)
        assertEquals("token abc", request.headers["Authorization"])
    }

    @Test
    fun `filters pull requests client side`() {
        transport.on("GET", "/api/v1/user", """{"login":"me","full_name":"Me Myself"}""")
        transport.on(
            "GET", "/pulls",
            "[" + listOf(pr(1, author = "me"), pr(2, reviewers = listOf("me")), pr(3)).joinToString(",") + "]",
        )
        assertEquals(listOf("1"), client.listPullRequests(repo, PrFilter.MINE).map { it.id })
        assertEquals(listOf("2"), client.listPullRequests(repo, PrFilter.REVIEWING).map { it.id })
        assertTrue("state=open" in transport.last("GET", "/pulls").url)
        assertEquals(1, transport.count("GET", "/api/v1/user"))

        transport.on("GET", "/pulls", "[" + pr(4, state = "closed", merged = true) + "," + pr(5, state = "closed") + "]")
        assertEquals(listOf("4"), client.listPullRequests(repo, PrFilter.MERGED).map { it.id })
        assertEquals(listOf("5"), client.listPullRequests(repo, PrFilter.CLOSED).map { it.id })
        assertTrue("state=closed" in transport.last("GET", "/pulls").url)
    }

    @Test
    fun `merges review votes into a single pull request`() {
        transport.on("GET", "/pulls/9", pr(9, reviewers = listOf("carol")))
        transport.on(
            "GET", "/pulls/9/reviews",
            """[{"id":1,"state":"REQUEST_CHANGES","user":{"login":"bob"}},
                {"id":2,"state":"COMMENT","user":{"login":"bob"}},
                {"id":3,"state":"APPROVED","user":{"login":"bob"}},
                {"id":4,"state":"APPROVED","user":{"login":"carol"}},
                {"id":5,"state":"REQUEST_CHANGES","dismissed":true,"user":{"login":"dave"}}]""",
        )
        val reviewers = client.getPullRequest(repo, "9").reviewers.associate { it.user.login to it.vote }
        assertEquals(mapOf("carol" to Vote.NONE, "bob" to Vote.APPROVED), reviewers)
    }

    @Test
    fun `creates a draft pull request with labels reviewers and linked issues`() {
        transport.on("GET", "/labels", """[{"id":7,"name":"bug"},{"id":8,"name":"Feature"}]""")
        transport.on("POST", "/pulls", pr(12, title = "WIP: Add widgets"))
        transport.on("POST", "/requested_reviewers", "{}")
        transport.on("POST", "/pulls/12/merge", "")
        transport.on("GET", "/pulls/12", pr(12, title = "WIP: Add widgets", reviewers = listOf("bob")))
        transport.on("GET", "/pulls/12/reviews", "[]")

        val created = client.createPullRequest(
            repo,
            NewPullRequest(
                title = "Add widgets",
                description = "Body",
                sourceBranch = "feature/x",
                targetBranch = "main",
                draft = true,
                reviewers = listOf(User("bob", "Bob", "bob")),
                labels = listOf("feature", "unknown"),
                linkedIssues = listOf("4", "#5"),
                autoComplete = MergeOptions(MergeStrategy.SQUASH, deleteSourceBranch = true),
            ),
        )
        assertEquals(PrState.DRAFT, created.state)
        assertEquals(listOf("bob"), created.reviewers.map { it.user.login })

        val body = parseJson(transport.requests.first { it.method == "POST" && it.url.endsWith("/pulls") }.body!!)
        assertEquals("WIP: Add widgets", body.str("title"))
        assertEquals("Body\n\nCloses #4\nCloses #5", body.str("body"))
        assertEquals("feature/x", body.str("head"))
        assertEquals("main", body.str("base"))
        assertEquals(listOf("8"), body.list("labels").map { it.asString })

        val reviewers = parseJson(transport.last("POST", "/requested_reviewers").body!!)
        assertEquals(listOf("bob"), reviewers.list("reviewers").map { it.asString })

        val merge = parseJson(transport.last("POST", "/pulls/12/merge").body!!)
        assertEquals("squash", merge.str("Do"))
        assertTrue(merge.bool("merge_when_checks_succeed"))
        assertTrue(merge.bool("delete_branch_after_merge"))
    }

    @Test
    fun `toggles draft through the title prefix`() {
        transport.on("GET", "/pulls", "[" + pr(3, title = "WIP: half done") + "]")
        transport.on("PATCH", "/pulls/3", pr(3, title = "half done"))
        val draft = client.listPullRequests(repo, PrFilter.OPEN).single()
        val ready = client.updatePullRequest(repo, draft, io.github.purgator.gitops.core.model.PullRequestUpdate(draft = false))
        assertEquals(PrState.OPEN, ready.state)
        assertEquals("half done", parseJson(transport.last("PATCH", "/pulls/3").body!!).str("title"))

        client.updatePullRequest(repo, ready, io.github.purgator.gitops.core.model.PullRequestUpdate(draft = true))
        assertEquals("WIP: half done", parseJson(transport.last("PATCH", "/pulls/3").body!!).str("title"))
    }

    @Test
    fun `votes map to reviews`() {
        transport.on("GET", "/pulls", "[" + pr(3) + "]")
        transport.on("POST", "/pulls/3/reviews", "{}")
        val pr = client.listPullRequests(repo, PrFilter.OPEN).single()
        client.vote(repo, pr, Vote.APPROVED)
        assertEquals("APPROVED", parseJson(transport.last("POST", "/reviews").body!!).str("event"))
        client.vote(repo, pr, Vote.REJECTED)
        assertEquals("REQUEST_CHANGES", parseJson(transport.last("POST", "/reviews").body!!).str("event"))
        assertThrows(UnsupportedOperationException::class.java) { client.vote(repo, pr, Vote.WAITING) }
    }

    // ---------------------------------------------------------------- actions

    @Test
    fun `maps action run states`() {
        fun run(id: Int, status: String, conclusion: String?) =
            """{"id":$id,"name":"CI","run_number":$id,"status":"$status","conclusion":${conclusion?.let { "\"$it\"" }},
               "head_branch":"main","head_sha":"abc","html_url":"https://codeberg.org/acme/widgets/actions/runs/$id",
               "actor":{"login":"alice"},"display_title":"Fix it","workflow_id":"ci.yml","created_at":"2026-01-02T03:04:05Z"}"""
        transport.on(
            "GET", "/actions/runs",
            """{"workflow_runs":[${
                listOf(
                    run(1, "completed", "success"), run(2, "completed", "failure"), run(3, "completed", "cancelled"),
                    run(4, "completed", "skipped"), run(5, "in_progress", null), run(6, "running", null),
                    run(7, "queued", null), run(8, "waiting", null), run(9, "blocked", null), run(10, "weird", null),
                ).joinToString(",")
            }]}""",
        )
        val runs = client.listPipelineRuns(repo, "main")
        assertEquals(
            listOf(
                CheckState.SUCCESS, CheckState.FAILURE, CheckState.CANCELLED, CheckState.SKIPPED, CheckState.RUNNING,
                CheckState.RUNNING, CheckState.PENDING, CheckState.PENDING, CheckState.PENDING, CheckState.UNKNOWN,
            ),
            runs.map { it.state },
        )
        assertEquals("CI #1", runs.first().name)
        assertEquals("Fix it", runs.first().title)
        assertEquals("alice", runs.first().triggeredBy)
        assertEquals("ci.yml", runs.first().definitionId)
        assertTrue("branch=main" in transport.last("GET", "/actions/runs").url)
    }

    @Test
    fun `reports missing actions clearly`() {
        val error = assertThrows(IOException::class.java) { client.listPipelineRuns(repo, null) }
        assertEquals("Actions are not available on this server or repository.", error.message)
    }

    // ---------------------------------------------------------------- issues

    @Test
    fun `lists and creates issues`() {
        transport.on("GET", "/api/v1/user", """{"login":"me"}""")
        transport.on(
            "GET", "/issues",
            """[{"number":4,"title":"Broken","body":"b","state":"open","html_url":"u","user":{"login":"alice"},
                 "assignees":[{"login":"me"}],"labels":[{"name":"bug"}],"updated_at":"2026-01-02T03:04:05Z"},
                {"number":5,"title":"A pull","state":"open","pull_request":{"merged":false}}]""",
        )
        val issues = client.listIssues(repo, io.github.purgator.gitops.core.model.IssueFilter.ASSIGNED_TO_ME, " crash ")
        assertEquals(listOf("4"), issues.map { it.id })
        assertTrue(issues.single().open)
        assertEquals("me", issues.single().assignee?.login)
        val url = transport.last("GET", "/issues").url
        assertTrue("type=issues" in url && "state=open" in url && "q=crash" in url && "assigned_by=me" in url)

        transport.on("POST", "/issues", """{"number":6,"title":"New","state":"open","html_url":"u"}""")
        val created = client.createIssue(repo, io.github.purgator.gitops.core.model.NewIssue("New", "text", assignToMe = true))
        assertEquals("6", created.id)
        assertEquals(listOf("me"), parseJson(transport.last("POST", "/issues").body!!).list("assignees").map { it.asString })
        assertEquals(listOf("closed"), client.issueStates(repo, created))
    }
}
