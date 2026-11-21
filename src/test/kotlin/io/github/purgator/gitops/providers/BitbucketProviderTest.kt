package io.github.purgator.gitops.providers

import io.github.purgator.gitops.FakeTransport
import io.github.purgator.gitops.FixedSecret
import io.github.purgator.gitops.core.http.get
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.parseJson
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.http.bool
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.providers.bitbucket.BitbucketClient
import io.github.purgator.gitops.providers.bitbucket.BitbucketProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.util.Base64

class BitbucketProviderTest {
    private val repo = RepoRef("bitbucket", "https://bitbucket.org", "acme", "shop")
    private val transport = FakeTransport()
    private val client = BitbucketClient("https://bitbucket.org", transport, FixedSecret())
    private val prs = "/repositories/acme/shop/pullrequests"

    private fun parse(url: String) = BitbucketProvider.parseRemote(GitUrl.parse(url)!!, emptyList())

    private fun pr(id: Int, state: String, extra: String = "") = """
        {"id": $id, "title": "PR $id", "description": "body", "state": "$state", $extra
         "author": {"uuid": "{a}", "display_name": "Ann", "nickname": "ann"},
         "source": {"branch": {"name": "feature/x"}, "commit": {"hash": "abc123"}, "repository": {"full_name": "acme/shop"}},
         "destination": {"branch": {"name": "main"}, "repository": {"full_name": "acme/shop"}},
         "links": {"html": {"href": "https://bitbucket.org/acme/shop/pull-requests/$id"}},
         "created_on": "2026-01-02T10:00:00.000000+00:00", "updated_on": "2026-01-03T10:00:00.000000+00:00",
         "comment_count": 3}
    """.trimIndent()

    @Test
    fun `parses https and ssh remotes`() {
        assertEquals(repo, parse("https://bob@bitbucket.org/acme/shop.git"))
        assertEquals(repo, parse("git@bitbucket.org:acme/shop.git"))
        assertEquals(repo, parse("ssh://git@bitbucket.org/acme/shop"))
        assertNull(parse("https://github.com/acme/shop.git"))
        assertNull(parse("https://bitbucket.org/acme"))
    }

    @Test
    fun `basic auth with a user name, bearer without`() {
        val basic = BitbucketProvider.authHeaders(Secret("me@acme.io", "tok"))["Authorization"]
        assertEquals("Basic " + Base64.getEncoder().encodeToString("me@acme.io:tok".toByteArray()), basic)
        assertEquals("Bearer tok", BitbucketProvider.authHeaders(Secret("", "tok"))["Authorization"])
    }

    @Test
    fun `maps pull request states`() {
        transport.on(
            "GET", prs,
            """{"values": [${pr(1, "OPEN")}, ${pr(2, "OPEN", "\"draft\": true,")}, ${pr(3, "MERGED")}, ${pr(4, "DECLINED")}, ${pr(5, "SUPERSEDED")}]}""",
        )
        val list = client.listPullRequests(repo, PrFilter.ALL)
        assertEquals(listOf(PrState.OPEN, PrState.DRAFT, PrState.MERGED, PrState.CLOSED, PrState.CLOSED), list.map { it.state })
        val first = list.first()
        assertEquals("feature/x", first.sourceBranch)
        assertEquals("main", first.targetBranch)
        assertEquals("abc123", first.headSha)
        assertEquals(3, first.commentCount)
        assertEquals("Ann", first.author.name)
        assertFalse(first.fromFork)
        assertEquals("https://bitbucket.org/acme/shop/pull-requests/1", first.webUrl)
    }

    @Test
    fun `maps participants to reviewers`() {
        val participants = """
            "participants": [
              {"role": "REVIEWER", "approved": true, "state": "approved", "user": {"uuid": "{b}", "display_name": "Bob"}},
              {"role": "REVIEWER", "approved": false, "state": "changes_requested", "user": {"uuid": "{c}", "display_name": "Cy"}},
              {"role": "REVIEWER", "approved": false, "state": null, "user": {"uuid": "{d}", "display_name": "Di"}},
              {"role": "PARTICIPANT", "approved": false, "state": null, "user": {"uuid": "{e}", "display_name": "Ed"}}
            ],
        """.trimIndent()
        transport.on("GET", "$prs/7", pr(7, "OPEN", participants))
        val reviewers = client.getPullRequest(repo, "7").reviewers
        assertEquals(listOf("Bob", "Cy", "Di"), reviewers.map { it.user.name })
        assertEquals(listOf(Vote.APPROVED, Vote.REJECTED, Vote.NONE), reviewers.map { it.vote })
    }

    @Test
    fun `list filters build the query`() {
        transport.on("GET", "/user", """{"uuid": "{me}", "display_name": "Me", "nickname": "me"}""")
        transport.on("GET", prs, """{"values": []}""")

        client.listPullRequests(repo, PrFilter.CLOSED)
        val closed = transport.last("GET", prs).url
        assertTrue("state=DECLINED" in closed && "state=SUPERSEDED" in closed, closed)
        assertTrue("sort=-updated_on" in closed, closed)

        client.listPullRequests(repo, PrFilter.MINE)
        val mine = URLDecoder.decode(transport.last("GET", prs).url, Charsets.UTF_8)
        assertTrue("q=author.uuid=\"{me}\" AND state=\"OPEN\"" in mine, mine)

        client.listPullRequests(repo, PrFilter.REVIEWING)
        val reviewing = URLDecoder.decode(transport.last("GET", prs).url, Charsets.UTF_8)
        assertTrue("q=reviewers.uuid=\"{me}\" AND state=\"OPEN\"" in reviewing, reviewing)
    }

    @Test
    fun `create sends branches, reviewers without self, draft and close flag`() {
        transport.on("GET", "/user", """{"uuid": "{me}", "display_name": "Me"}""")
        transport.on("POST", prs, pr(9, "OPEN", "\"draft\": true,"))
        val created = client.createPullRequest(
            repo,
            NewPullRequest(
                title = "T", description = "D", sourceBranch = "feature/x", targetBranch = "main", draft = true,
                reviewers = listOf(User("{me}", "Me"), User("{b}", "Bob")),
                autoComplete = MergeOptions(deleteSourceBranch = true),
            ),
        )
        assertEquals(PrState.DRAFT, created.state)
        val body = parseJson(transport.last("POST", prs).body!!)
        assertEquals("T", body.str("title"))
        assertEquals("feature/x", body.str("source", "branch", "name"))
        assertEquals("main", body.str("destination", "branch", "name"))
        assertEquals(listOf("{b}"), body.list("reviewers").map { it.str("uuid") })
        assertTrue(body.bool("draft"))
        assertTrue(body.bool("close_source_branch"))
    }

    @Test
    fun `votes hit the approve and request-changes endpoints`() {
        transport.on("POST", "$prs/1/approve", "{}").on("POST", "$prs/1/request-changes", "{}")
        val pull = client.run {
            transport.on("GET", "$prs/1", pr(1, "OPEN"))
            getPullRequest(repo, "1")
        }
        client.vote(repo, pull, Vote.APPROVED)
        client.vote(repo, pull, Vote.REJECTED)
        client.vote(repo, pull, Vote.NONE) // both deletes fail with 404 and are swallowed
        assertEquals(1, transport.count("POST", "/approve"))
        assertEquals(1, transport.count("POST", "/request-changes"))
        assertEquals(2, transport.count("DELETE", "$prs/1/"))
    }

    @Test
    fun `maps pipeline states`() {
        fun state(json: String) = client.pipelineState(parseJson(json))
        assertEquals(CheckState.SUCCESS, state("""{"name": "COMPLETED", "result": {"name": "SUCCESSFUL"}}"""))
        assertEquals(CheckState.FAILURE, state("""{"name": "COMPLETED", "result": {"name": "FAILED"}}"""))
        assertEquals(CheckState.FAILURE, state("""{"name": "COMPLETED", "result": {"name": "ERROR"}}"""))
        assertEquals(CheckState.CANCELLED, state("""{"name": "COMPLETED", "result": {"name": "STOPPED"}}"""))
        assertEquals(CheckState.CANCELLED, state("""{"name": "COMPLETED", "result": {"name": "EXPIRED"}}"""))
        assertEquals(CheckState.RUNNING, state("""{"name": "IN_PROGRESS", "stage": {"name": "RUNNING"}}"""))
        assertEquals(CheckState.PENDING, state("""{"name": "IN_PROGRESS", "stage": {"name": "PAUSED"}}"""))
        assertEquals(CheckState.PENDING, state("""{"name": "PENDING"}"""))
    }

    @Test
    fun `lists pipeline runs`() {
        transport.on(
            "GET", "/repositories/acme/shop/pipelines/?",
            """{"values": [{"uuid": "{p1}", "build_number": 42, "state": {"name": "COMPLETED", "result": {"name": "SUCCESSFUL"}},
                "target": {"ref_name": "main", "commit": {"hash": "abc"}}, "creator": {"display_name": "Ann"},
                "created_on": "2026-01-02T10:00:00Z", "completed_on": "2026-01-02T10:05:00Z"}]}""",
        )
        val run = client.listPipelineRuns(repo, "main").single()
        assertEquals("Pipeline #42", run.name)
        assertEquals(CheckState.SUCCESS, run.state)
        assertEquals("https://bitbucket.org/acme/shop/pipelines/results/42", run.webUrl)
        assertTrue("target.ref_name=main" in transport.last("GET", "/pipelines/?").url)

        // No route for the log: a 404 reads as "no log yet".
        val job = io.github.purgator.gitops.core.model.PipelineJob("{s1}", "Build", CheckState.RUNNING, logRef = "{s1}")
        assertEquals("No log is available for this step yet.", client.jobLog(repo, run, job))
        assertTrue("%7Bp1%7D/steps/%7Bs1%7D/log" in transport.requests.last().url)
    }

    @Test
    fun `disabled issue tracker is explained`() {
        val error = runCatching { client.getIssue(repo, "1") }.exceptionOrNull()
        assertEquals("The issue tracker is not enabled for this repository.", error?.message)
    }

    @Test
    fun `builds web urls`() {
        assertEquals(
            "https://bitbucket.org/acme/shop/src/main/src/App.kt#lines-3:5",
            BitbucketProvider.fileUrl(repo, "main", "src/App.kt", 2..4),
        )
        assertEquals("https://bitbucket.org/acme/shop/src/main/src/App.kt#lines-3", BitbucketProvider.fileUrl(repo, "main", "/src/App.kt", 2..2))
        assertEquals("https://bitbucket.org/acme/shop/src/main/README.md", BitbucketProvider.fileUrl(repo, "main", "README.md"))
        assertEquals("https://bitbucket.org/acme/shop/commits/abc", BitbucketProvider.commitUrl(repo, "abc"))
        assertEquals(
            "https://bitbucket.org/acme/shop/pull-requests/new?source=feature%2Fx&dest=main&t=1",
            BitbucketProvider.newPullRequestUrl(repo, "feature/x", "main"),
        )
        assertNull(BitbucketProvider.pullRequestHeadRef(client.run {
            transport.on("GET", "$prs/1", pr(1, "OPEN"))
            getPullRequest(repo, "1")
        }))
    }
}
