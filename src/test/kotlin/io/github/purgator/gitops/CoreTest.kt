package io.github.purgator.gitops

import io.github.purgator.gitops.core.ModuleGuess
import io.github.purgator.gitops.core.http.ApiClient
import io.github.purgator.gitops.core.http.HttpException
import io.github.purgator.gitops.core.http.HttpResponse
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.http.jsonOf
import io.github.purgator.gitops.core.http.list
import io.github.purgator.gitops.core.http.parseJson
import io.github.purgator.gitops.core.http.str
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.git.BranchRules
import io.github.purgator.gitops.git.Gitmojis
import io.github.purgator.gitops.ui.Format
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitUrlTest {
    @Test
    fun `parses https urls with user and port`() {
        val url = GitUrl.parse("https://me@git.acme.io:8443/team/app.git")!!
        assertEquals("https", url.scheme)
        assertEquals("git.acme.io", url.host)
        assertEquals(8443, url.port)
        assertEquals("team/app", url.path)
        assertEquals("me", url.user)
        assertEquals("https://git.acme.io:8443", url.webBase)
    }

    @Test
    fun `parses scp-like and ssh urls`() {
        val scp = GitUrl.parse("git@github.com:octo/repo.git")!!
        assertEquals("ssh", scp.scheme)
        assertEquals(listOf("octo", "repo"), scp.segments)
        assertEquals("https://github.com", scp.webBase)

        val ssh = GitUrl.parse("ssh://git@gitlab.acme.io:2222/group/sub/app.git")!!
        assertEquals("group/sub/app", ssh.path)
        // The ssh port says nothing about the web port.
        assertEquals("https://gitlab.acme.io", ssh.webBase)
    }

    @Test
    fun `rejects local paths and garbage`() {
        assertNull(GitUrl.parse(""))
        assertNull(GitUrl.parse("C:/repos/app"))
        assertNull(GitUrl.parse("file:///repos/app.git"))
        assertNull(GitUrl.parse("../relative/repo"))
    }

    @Test
    fun `normalises server urls typed by users`() {
        assertEquals("https://gitlab.acme.io", GitUrl.normalizeServerUrl("gitlab.acme.io/"))
        assertEquals("http://localhost:3000", GitUrl.normalizeServerUrl("http://localhost:3000/"))
        assertEquals("gitlab.acme.io", GitUrl.hostOf("https://user@gitlab.acme.io:8443/x"))
    }

    @Test
    fun `guesses the module from the host`() {
        fun guess(url: String) = ModuleGuess.forRemote(GitUrl.parse(url)!!)
        assertEquals("github", guess("git@github.com:a/b.git"))
        assertEquals("azure", guess("https://dev.azure.com/org/proj/_git/repo"))
        assertEquals("azure", guess("git@ssh.dev.azure.com:v3/org/proj/repo"))
        assertEquals("azure", guess("https://tfs.acme.io/tfs/Main/proj/_git/repo"))
        assertEquals("gitlab", guess("https://gitlab.com/g/s/r.git"))
        assertEquals("bitbucket", guess("git@bitbucket.org:ws/repo.git"))
        assertEquals("gitea", guess("https://codeberg.org/a/b.git"))
        assertNull(guess("https://git.acme.io/a/b.git"))
    }
}

class ApiClientTest {
    @Test
    fun `builds urls, sends auth and parses json`() {
        val transport = FakeTransport().on("GET", "/things", """{"value":[{"name":"a"},{"name":"b"}]}""")
        val api = ApiClient("https://api.example.com/", transport, authHeaders = { mapOf("Authorization" to "Bearer x") })
        val json = api.get("/things", "q" to "a b", "skip" to null, "n" to 2)
        assertEquals(listOf("a", "b"), json.list("value").map { it.str("name") })
        val request = transport.last("GET", "/things")
        assertEquals("https://api.example.com/things?q=a%20b&n=2", request.url)
        assertEquals("Bearer x", request.headers["Authorization"])
    }

    @Test
    fun `turns error payloads into readable messages`() {
        val transport = FakeTransport().on("POST", "/things", """{"message":"Title is required"}""", status = 422)
        val error = assertThrows(HttpException::class.java) { ApiClient("https://x", transport).post("/things", jsonOf("a" to 1)) }
        assertEquals(422, error.status)
        assertTrue(error.message!!.contains("Title is required"))
    }

    @Test
    fun `retries once after refreshing an expired credential`() {
        var calls = 0
        var token = "old"
        val transport = HttpTransport { request ->
            calls++
            if (request.headers["Authorization"] == "Bearer new") HttpResponse(200, """{"ok":true}""") else HttpResponse(401, "")
        }
        val api = ApiClient("https://x", transport, authHeaders = { mapOf("Authorization" to "Bearer $token") }, onUnauthorized = { token = "new"; true })
        assertEquals("true", api.get("/me").str("ok"))
        assertEquals(2, calls)
    }

    @Test
    fun `reports an authentication failure when the retry does not help`() {
        val transport = HttpTransport { HttpResponse(203, "<html>Sign in</html>") }
        val error = assertThrows(HttpException::class.java) { ApiClient("https://x", transport).get("/me") }
        assertTrue(error.unauthorized)
    }

    @Test
    fun `json helpers never throw on missing data`() {
        val json = parseJson("""{"a":{"b":null},"n":"12"}""")
        assertEquals("", json.str("a", "b", "c"))
        assertEquals("12", json.str("n"))
        assertTrue(parseJson("not json").list("x").isEmpty())
        assertEquals("""{"a":1,"l":["x"]}""", jsonOf("a" to 1, "skip" to null, "l" to listOf("x")).toString())
    }
}

class HelpersTest {
    @Test
    fun `derives a title and work item ids from a branch name`() {
        assertEquals("Add login page", Format.titleFromBranch("feature/1234-add-login-page"))
        assertEquals("Hotfix", Format.titleFromBranch("hotfix"))
        assertEquals(listOf("1234"), Format.issueIdsFromBranch("feature/1234-add-login"))
        assertEquals(listOf("77", "78"), Format.issueIdsFromBranch("fix/77-78-crash"))
        assertTrue(Format.issueIdsFromBranch("feature/oauth2-login").isEmpty())
        assertTrue(Format.issueIdsFromBranch("main").isEmpty())
    }

    @Test
    fun `slugifies titles for branch names`() {
        assertEquals("ajouter-l-ecran-de-connexion", Format.slug("Ajouter l'écran de connexion !"))
        assertEquals("a-very-long", Format.slug("A very long title", max = 12))
    }

    @Test
    fun `matches protected branch patterns`() {
        val patterns = "main, master, release/*"
        assertTrue(BranchRules.isProtected("main", patterns))
        assertTrue(BranchRules.isProtected("release/1.2", patterns))
        assertFalse(BranchRules.isProtected("feature/main", patterns))
        assertFalse(BranchRules.isProtected("anything", ""))
    }

    @Test
    fun `puts a gitmoji in front of the message and replaces an existing one`() {
        val sparkles = Gitmojis.ALL.first { it.code == ":sparkles:" }
        val bug = Gitmojis.ALL.first { it.code == ":bug:" }
        assertEquals("✨ Add login", Gitmojis.apply("Add login", sparkles, asCode = false))
        assertEquals("🐛 Add login", Gitmojis.apply("✨ Add login", bug, asCode = false))
        assertEquals(":bug: Add login", Gitmojis.apply(":sparkles: Add login", bug, asCode = true))
        assertEquals("✨ ", Gitmojis.apply("", sparkles, asCode = false))
        assertEquals("Refactor", Gitmojis.strip("♻️ Refactor"))
    }

    @Test
    fun `summarises check states with the worst one`() {
        assertNull(CheckState.summarize(emptyList()))
        assertEquals(CheckState.FAILURE, CheckState.summarize(listOf(CheckState.SUCCESS, CheckState.FAILURE, CheckState.RUNNING)))
        assertEquals(CheckState.RUNNING, CheckState.summarize(listOf(CheckState.SUCCESS, CheckState.RUNNING)))
        assertEquals(CheckState.SUCCESS, CheckState.summarize(listOf(CheckState.SUCCESS, CheckState.SKIPPED)))
    }
}
