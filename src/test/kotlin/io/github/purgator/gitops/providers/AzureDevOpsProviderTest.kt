package io.github.purgator.gitops.providers

import io.github.purgator.gitops.FakeTransport
import io.github.purgator.gitops.FixedSecret
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.parseJson
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.IssueFilter
import io.github.purgator.gitops.core.model.MergeOptions
import io.github.purgator.gitops.core.model.MergeStatus
import io.github.purgator.gitops.core.model.MergeStrategy
import io.github.purgator.gitops.core.model.NewPullRequest
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.model.Vote
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.providers.azure.AzureDevOpsClient
import io.github.purgator.gitops.providers.azure.AzureDevOpsProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class AzureDevOpsProviderTest {
    private val repo = RepoRef("azure", "https://dev.azure.com/acme", "acme", "web app", "My Project")
    private val me = """{"authenticatedUser":{"id":"me-id","providerDisplayName":"Me","properties":{"Account":{"${'$'}value":"me@acme.io"}}}}"""

    private fun parse(url: String, servers: List<String> = emptyList()) = AzureDevOpsProvider.parseRemote(GitUrl.parse(url)!!, servers)

    private fun client(transport: FakeTransport) = AzureDevOpsClient("https://dev.azure.com/acme", transport, FixedSecret("pat"))

    private fun pr(id: Int, status: String = "active", extra: String = "") = """
        {"pullRequestId":$id,"title":"Add login","description":"Body","status":"$status","isDraft":false,
         "createdBy":{"id":"u1","displayName":"Ann","uniqueName":"ann@acme.io"},
         "sourceRefName":"refs/heads/feature/1-login","targetRefName":"refs/heads/main",
         "creationDate":"2026-05-01T10:00:00Z","mergeStatus":"succeeded",
         "lastMergeSourceCommit":{"commitId":"abc123"},
         "reviewers":[{"id":"u2","displayName":"Bob","vote":10,"isRequired":true},{"id":"u3","displayName":"Cy","vote":-5}]$extra}
    """.trimIndent()

    @Test
    fun `parses every azure remote flavour`() {
        val https = parse("https://acme@dev.azure.com/acme/My%20Project/_git/web")!!
        assertEquals(RepoRef("azure", "https://dev.azure.com/acme", "acme", "web", "My Project"), https)

        val ssh = parse("git@ssh.dev.azure.com:v3/acme/Proj/web")!!
        assertEquals("https://dev.azure.com/acme", ssh.serverUrl)
        assertEquals("Proj", ssh.project)
        assertEquals("web", ssh.name)

        val legacy = parse("https://acme.visualstudio.com/DefaultCollection/Proj/_git/web")!!
        assertEquals("https://acme.visualstudio.com", legacy.serverUrl)
        assertEquals("Proj", legacy.project)

        val defaultRepo = parse("https://acme.visualstudio.com/_git/web")!!
        assertEquals("web", defaultRepo.project)

        val server = parse("https://tfs.acme.io/tfs/Main/Proj/_git/web")!!
        assertEquals("https://tfs.acme.io/tfs/Main", server.serverUrl)
        assertEquals("Proj", server.project)

        assertNull(parse("https://github.com/acme/web.git"))
        assertNull(parse("https://dev.azure.com/acme"))
    }

    @Test
    fun `matches accounts across the two cloud host names`() {
        val ref = parse("https://acme.visualstudio.com/Proj/_git/web")!!
        assertTrue(AzureDevOpsProvider.accountMatches("https://dev.azure.com/acme", ref))
        assertTrue(AzureDevOpsProvider.accountMatches("https://dev.azure.com/ACME/", ref))
        assertFalse(AzureDevOpsProvider.accountMatches("https://dev.azure.com/other", ref))
    }

    @Test
    fun `sends personal access tokens as basic and entra tokens as bearer`() {
        val basic = AzureDevOpsProvider.authHeaders(Secret("", "pat"))["Authorization"]!!
        assertEquals("Basic " + Base64.getEncoder().encodeToString(":pat".toByteArray()), basic)
        assertEquals("Bearer eyJa.b.c", AzureDevOpsProvider.authHeaders(Secret("", "eyJa.b.c"))["Authorization"])
    }

    @Test
    fun `maps pull requests`() {
        val transport = FakeTransport().on("GET", "/pullrequests/7", pr(7))
        val result = client(transport).getPullRequest(repo, "7")
        assertEquals("7", result.id)
        assertEquals(PrState.OPEN, result.state)
        assertEquals("feature/1-login", result.sourceBranch)
        assertEquals("main", result.targetBranch)
        assertEquals(MergeStatus.CLEAN, result.mergeStatus)
        assertEquals(listOf(Vote.APPROVED, Vote.WAITING), result.reviewers.map { it.vote })
        assertTrue(result.reviewers[0].required)
        assertEquals("https://dev.azure.com/acme/My%20Project/_git/web%20app/pullrequest/7", result.webUrl)
        assertTrue(transport.last("GET", "/pullrequests/7").url.contains("/My%20Project/_apis/git/repositories/web%20app/"))
    }

    @Test
    fun `maps completed, abandoned and draft states`() {
        val transport = FakeTransport().on(
            "GET", "/pullrequests?",
            """{"value":[${pr(1, "completed")},${pr(2, "abandoned")},${pr(3).replace("\"isDraft\":false", "\"isDraft\":true")},${pr(4, extra = ",\"autoCompleteSetBy\":{\"id\":\"x\"}")}]}""",
        )
        val all = client(transport).listPullRequests(repo, PrFilter.ALL)
        assertEquals(listOf(PrState.MERGED, PrState.CLOSED, PrState.DRAFT, PrState.OPEN), all.map { it.state })
        assertTrue(all[3].autoComplete)
        assertTrue(transport.last("GET", "/pullrequests?").url.contains("searchCriteria.status=all"))
    }

    @Test
    fun `filters my pull requests by creator`() {
        val transport = FakeTransport().on("GET", "connectionData", me).on("GET", "/pullrequests?", """{"value":[]}""")
        client(transport).listPullRequests(repo, PrFilter.MINE)
        val url = transport.last("GET", "/pullrequests?").url
        assertTrue(url.contains("searchCriteria.creatorId=me-id"))
        assertTrue(url.contains("searchCriteria.status=active"))
    }

    @Test
    fun `creates a pull request with reviewers, work items and auto-complete`() {
        val transport = FakeTransport()
            .on("GET", "connectionData", me)
            .on("POST", "/pullrequests?", pr(9))
            .on("PATCH", "/pullrequests/9", pr(9, extra = ",\"autoCompleteSetBy\":{\"id\":\"me-id\"}"))
        val created = client(transport).createPullRequest(
            repo,
            NewPullRequest(
                title = "Add login", description = "Body", sourceBranch = "feature/1-login", targetBranch = "main", draft = true,
                reviewers = listOf(User("u2", "Bob")), labels = listOf("ui"), linkedIssues = listOf("1"),
                autoComplete = MergeOptions(MergeStrategy.SQUASH, deleteSourceBranch = true, autoComplete = true, completeWorkItems = true),
            ),
        )
        assertTrue(created.autoComplete)

        val body = parseJson(transport.last("POST", "/pullrequests?").body!!)
        assertEquals("refs/heads/feature/1-login", body.str("sourceRefName"))
        assertEquals("refs/heads/main", body.str("targetRefName"))
        assertEquals("true", body.str("isDraft"))
        assertEquals(listOf("u2"), body.list("reviewers").map { it.str("id") })
        assertEquals(listOf("1"), body.list("workItemRefs").map { it.str("id") })
        assertEquals(listOf("ui"), body.list("labels").map { it.str("name") })

        val patch = parseJson(transport.last("PATCH", "/pullrequests/9").body!!)
        assertEquals("me-id", patch.str("autoCompleteSetBy", "id"))
        assertEquals("squash", patch.str("completionOptions", "mergeStrategy"))
        assertEquals("true", patch.str("completionOptions", "deleteSourceBranch"))
        assertEquals("true", patch.str("completionOptions", "transitionWorkItems"))
    }

    @Test
    fun `completes with the head commit and votes for the current user`() {
        val transport = FakeTransport()
            .on("GET", "connectionData", me)
            .on("GET", "/pullrequests/7", pr(7))
            .on("PATCH", "/pullrequests/7", pr(7, "completed"))
            .on("PUT", "/reviewers/me-id", "{}")
        val client = client(transport)
        val existing = client.getPullRequest(repo, "7")

        client.mergePullRequest(repo, existing, MergeOptions(MergeStrategy.REBASE))
        val body = parseJson(transport.last("PATCH", "/pullrequests/7").body!!)
        assertEquals("completed", body.str("status"))
        assertEquals("abc123", body.str("lastMergeSourceCommit", "commitId"))
        assertEquals("rebase", body.str("completionOptions", "mergeStrategy"))

        client.vote(repo, existing, Vote.REJECTED)
        assertEquals("-10", parseJson(transport.last("PUT", "/reviewers/me-id").body!!).str("vote"))
    }

    @Test
    fun `maps builds and timeline jobs`() {
        val transport = FakeTransport()
            .on("GET", "/_apis/git/repositories/web%20app?", """{"id":"repo-id","defaultBranch":"refs/heads/main","project":{"id":"p1"}}""")
            .on(
                "GET", "/_apis/build/builds?",
                """{"value":[
                    {"id":1,"buildNumber":"20260501.1","status":"completed","result":"succeeded","sourceBranch":"refs/heads/main","definition":{"id":5,"name":"CI"}},
                    {"id":2,"buildNumber":"20260501.2","status":"completed","result":"failed","sourceBranch":"refs/heads/main","definition":{"id":5,"name":"CI"}},
                    {"id":3,"buildNumber":"20260501.3","status":"inProgress","sourceBranch":"refs/heads/main","definition":{"id":5,"name":"CI"}},
                    {"id":4,"buildNumber":"20260501.4","status":"notStarted","sourceBranch":"refs/heads/main","definition":{"id":5,"name":"CI"}},
                    {"id":5,"buildNumber":"20260501.5","status":"completed","result":"canceled","sourceBranch":"refs/heads/main","definition":{"id":5,"name":"CI"}}]}""",
            )
            .on(
                "GET", "/timeline",
                """{"records":[
                    {"id":"s","type":"Stage","name":"Build"},
                    {"id":"p","parentId":"s","type":"Phase","name":"Phase"},
                    {"id":"j","parentId":"p","type":"Job","name":"Compile","state":"completed","result":"succeeded","log":{"id":12}},
                    {"id":"t","parentId":"j","type":"Task","name":"Step"}]}""",
            )
        val client = client(transport)
        val runs = client.listPipelineRuns(repo, "main")
        assertEquals(
            listOf(CheckState.SUCCESS, CheckState.FAILURE, CheckState.RUNNING, CheckState.PENDING, CheckState.CANCELLED),
            runs.map { it.state },
        )
        assertEquals("CI #20260501.1", runs[0].name)
        assertEquals("5", runs[0].definitionId)
        val url = transport.last("GET", "/_apis/build/builds?").url
        assertTrue(url.contains("repositoryId=repo-id"))
        assertTrue(url.contains("branchName=refs%2Fheads%2Fmain"))

        val jobs = client.listJobs(repo, runs[0])
        assertEquals(1, jobs.size)
        assertEquals("Compile", jobs[0].name)
        assertEquals("Build", jobs[0].stage)
        assertEquals("12", jobs[0].logRef)
    }

    @Test
    fun `queries work items with wiql and keeps the server order`() {
        val transport = FakeTransport()
            .on("POST", "/_apis/wit/wiql", """{"workItems":[{"id":12},{"id":7}]}""")
            .on(
                "GET", "/_apis/wit/workitems?",
                """{"value":[
                    {"id":7,"fields":{"System.Title":"Old","System.State":"Closed","System.WorkItemType":"Bug"}},
                    {"id":12,"fields":{"System.Title":"New","System.State":"Active","System.WorkItemType":"Task","System.Tags":"ui; api",
                      "System.AssignedTo":{"id":"u1","displayName":"Ann"}}}]}""",
            )
        val items = client(transport).listIssues(repo, IssueFilter.ASSIGNED_TO_ME, "it's")
        assertEquals(listOf("12", "7"), items.map { it.id })
        assertTrue(items[0].open)
        assertFalse(items[1].open)
        assertEquals(listOf("ui", "api"), items[0].labels)
        assertEquals("Ann", items[0].assignee?.name)
        assertEquals("https://dev.azure.com/acme/My%20Project/_workitems/edit/12", items[0].webUrl)

        val wiql = parseJson(transport.last("POST", "/_apis/wit/wiql").body!!).str("query")
        assertTrue(wiql.contains("[System.AssignedTo] = @Me"))
        assertTrue(wiql.contains("CONTAINS 'it''s'"))
    }

    @Test
    fun `builds web links`() {
        assertEquals(
            "https://dev.azure.com/acme/My%20Project/_git/web%20app?path=%2Fsrc%2FApp.kt&version=GBmain&line=4&lineEnd=6&lineStartColumn=1&lineEndColumn=1&lineStyle=plain&_a=contents",
            AzureDevOpsProvider.fileUrl(repo, "main", "src/App.kt", 3..4),
        )
        assertTrue(AzureDevOpsProvider.fileUrl(repo, "a".repeat(40), "x", null).contains("version=GC" + "a".repeat(40)))
        assertEquals(
            "https://dev.azure.com/acme/My%20Project/_git/web%20app/pullrequestcreate?sourceRef=feature%2Fx&targetRef=main",
            AzureDevOpsProvider.newPullRequestUrl(repo, "feature/x", "main"),
        )
    }
}
