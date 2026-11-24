package io.github.purgator.gitops

import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.git.AheadBehind
import io.github.purgator.gitops.git.Blame
import io.github.purgator.gitops.git.BlameLine
import io.github.purgator.gitops.git.Gitmojis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class GitmojiSuggestionTest {
    private fun codes(word: String) = Gitmojis.matching(word).map { it.code }

    @Test
    fun `the first word suggests gitmojis by alias or by name`() {
        assertEquals(":sparkles:", codes("feature").first())
        assertEquals(":sparkles:", codes("sparkles").single())
        assertEquals(":sparkles:", codes("Feat").first())
        assertEquals(":sparkles:", codes(":spark").single())
        assertEquals(":memo:", codes("docs").first())
        assertEquals(":recycle:", codes("refactor").first())
        assertTrue(codes("fix").containsAll(listOf(":bug:", ":adhesive_bandage:")))
        assertTrue(codes("zzz").isEmpty())
        assertEquals(Gitmojis.ALL.size, codes("").size)
    }

    @Test
    fun `an exact keyword comes before longer ones`() {
        // "ci" is a keyword of two gitmojis and only the start of others ("circle" would be).
        assertEquals(listOf(":green_heart:", ":construction_worker:"), codes("ci").take(2))
        assertEquals(":construction:", codes("construction").first())
    }

    @Test
    fun `every gitmoji has distinct lower-case keywords`() {
        for (gitmoji in Gitmojis.ALL) {
            assertTrue(gitmoji.keywords.isNotEmpty(), gitmoji.code)
            assertEquals(gitmoji.keywords.distinct(), gitmoji.keywords, gitmoji.code)
            assertTrue(gitmoji.keywords.all { it == it.lowercase() && it.isNotBlank() }, gitmoji.code)
        }
        assertEquals(Gitmojis.ALL.size, Gitmojis.ALL.map { it.code }.distinct().size)
    }

    @Test
    fun `only the first word of the message is completed`() {
        assertEquals("feat", Gitmojis.firstWord("feat", 4))
        assertEquals("fe", Gitmojis.firstWord("feat", 2))
        assertEquals(":spa", Gitmojis.firstWord(":spa", 4))
        assertEquals("", Gitmojis.firstWord("", 0))
        assertNull(Gitmojis.firstWord("Add login", 9))
        assertNull(Gitmojis.firstWord("✨ Add", 5))
        assertNull(Gitmojis.firstWord("Subject\nbody", 12))
        assertNull(Gitmojis.firstWord("feat", 9))
    }

    @Test
    fun `the caret lands right after the inserted gitmoji`() {
        val sparkles = Gitmojis.ALL.first { it.code == ":sparkles:" }
        val bug = Gitmojis.ALL.first { it.code == ":bug:" }
        assertEquals("✨ ", Gitmojis.prefix(sparkles, asCode = false))
        assertEquals(":bug: ", Gitmojis.prefix(bug, asCode = true))
        // What the picker replaces: nothing, leading blanks, or the gitmoji already there.
        assertEquals(0, Gitmojis.prefixLength("Add login"))
        assertEquals(2, Gitmojis.prefixLength("  Add login"))
        assertEquals("✨ ".length, Gitmojis.prefixLength("✨ Add login"))
        assertEquals(":sparkles: ".length, Gitmojis.prefixLength(":sparkles: Add login"))
        assertEquals("♻️ ".length, Gitmojis.prefixLength("♻️ Refactor"))
        for (message in listOf("Add login", "✨ Add login", ":sparkles: Add login", "")) {
            val replaced = Gitmojis.prefix(bug, false) + message.substring(Gitmojis.prefixLength(message))
            assertEquals(Gitmojis.apply(message, bug, asCode = false), replaced)
        }
    }
}

class BlameTest {
    private val porcelain = listOf(
        "1111111111111111111111111111111111111111 1 1 2",
        "author Ann Lee",
        "author-mail <ann@example.com>",
        "author-time 1700000000",
        "author-tz +0100",
        "committer Ann Lee",
        "committer-mail <ann@example.com>",
        "committer-time 1700000000",
        "committer-tz +0100",
        "summary Add the login page",
        "filename src/Login.kt",
        "\tclass Login {",
        "1111111111111111111111111111111111111111 2 2",
        "\t",
        "2222222222222222222222222222222222222222 7 3 1",
        "author Bob",
        "author-time 1700086400",
        "summary Fix the redirect",
        "previous 1111111111111111111111111111111111111111 src/Login.kt",
        "filename src/Login.kt",
        "\tauthor of this line is not Ann",
        "1111111111111111111111111111111111111111 3 4 1",
        "\t}",
    )

    @Test
    fun `reads porcelain blame line by line`() {
        val lines = Blame.parse(porcelain)
        assertEquals(4, lines.size)
        assertEquals(listOf("Ann Lee", "Ann Lee", "Bob", "Ann Lee"), lines.map { it?.author })
        assertEquals("Fix the redirect", lines[2]?.summary)
        assertEquals("2222222222222222222222222222222222222222", lines[2]?.hash)
        assertEquals(Instant.ofEpochSecond(1700000000), lines[0]?.time)
        // Details are given once per commit and shared by its later lines.
        assertEquals("Add the login page", lines[3]?.summary)
        assertTrue(Blame.parse(emptyList()).isEmpty())
    }

    @Test
    fun `formats the annotation`() {
        val now = Instant.ofEpochSecond(1700000000)
        val line = BlameLine("abc", "Ann Lee", now.minus(Duration.ofDays(3)), "Fix the login")
        assertEquals("Ann Lee, 3 days ago • Fix the login", Blame.text(line, GitOpsSettings.DEFAULT_BLAME_FORMAT, now))
        assertEquals("Ann Lee, 3 days ago", Blame.text(line, "{author}, {ago}", now))
        val long = BlameLine("abc", "Ann", null, "x".repeat(200))
        assertEquals("Ann •  " + "x".repeat(79) + "…", Blame.text(long, "{author} • {ago} {subject}", now))
    }

    @Test
    fun `shows the exact date, in the pattern asked for`() {
        // 2023-11-14T22:13:20Z
        val line = BlameLine("0123456789abcdef", "Ann Lee", Instant.ofEpochSecond(1700000000), "Fix the login")
        val utc = ZoneOffset.UTC
        val now = Instant.ofEpochSecond(1700500000)
        assertEquals("Ann Lee on 2023-11-14", Blame.text(line, "{author} on {date}", now, utc))
        assertEquals("14/11/2023 22:13 Ann Lee", Blame.text(line, "{date:dd/MM/yyyy HH:mm} {author}", now, utc))
        assertEquals("15/11/2023", Blame.text(line, "{date:dd/MM/yyyy}", now, ZoneOffset.ofHours(2)))
        assertEquals("01234567 Fix the login", Blame.text(line, "{hash} {subject}", now, utc))
        // A pattern that is not one falls back to the plain date; unknown placeholders are left alone.
        assertEquals("2023-11-14", Blame.text(line, "{date:nope}", now, utc))
        assertEquals("{who} Ann Lee", Blame.text(line, "{who} {author}", now, utc))
        assertEquals("Ann", Blame.text(BlameLine("abc", "Ann", null, ""), "{author} {date}", now, utc))
    }

    @Test
    fun `tells how long ago in words`() {
        val now = Instant.ofEpochSecond(1700000000)
        fun ago(duration: Duration) = Blame.ago(now.minus(duration), now)
        assertEquals("just now", ago(Duration.ofSeconds(20)))
        assertEquals("1 minute ago", ago(Duration.ofSeconds(80)))
        assertEquals("5 hours ago", ago(Duration.ofHours(5)))
        assertEquals("yesterday", ago(Duration.ofHours(30)))
        assertEquals("12 days ago", ago(Duration.ofDays(12)))
        assertEquals("2 months ago", ago(Duration.ofDays(70)))
        assertEquals("1 year ago", ago(Duration.ofDays(400)))
    }

    /** The parser against what the installed git really prints. Skipped where git is missing. */
    @Test
    fun `reads the blame of a real repository`(@TempDir dir: Path) {
        fun git(vararg args: String): List<String>? = runCatching {
            val command = listOf("git", "-c", "user.name=Ann Lee", "-c", "user.email=ann@example.com", "-c", "commit.gpgsign=false") + args
            val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readLines()
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        }.getOrNull()

        assumeTrue(git("init", "-q") != null, "git is not available")
        val file = dir.resolve("notes.txt")
        Files.writeString(file, "first\n\nthird\n")
        assumeTrue(git("add", ".") != null && git("commit", "-q", "-m", "Add the notes") != null)
        Files.writeString(file, "first\n\nthird, edited\nfourth\n")
        assumeTrue(git("commit", "-q", "-a", "-m", "Edit the notes") != null)

        val lines = Blame.parse(git("blame", "--porcelain", "HEAD", "--", "notes.txt")!!)
        assertEquals(listOf("Add the notes", "Add the notes", "Edit the notes", "Edit the notes"), lines.map { it?.summary })
        assertTrue(lines.all { it?.author == "Ann Lee" && it.time != null })
        assertFalse(lines[0]?.hash == lines[2]?.hash)
    }
}

class AheadBehindTest {
    @Test
    fun `reads the counts of rev-list`() {
        val status = AheadBehind.parse("main", "origin/main", "2\t1\n")!!
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals("↑2 ↓1", status.label)
        assertEquals("main: 2 commits to push, 1 commit to pull (origin/main as of the last fetch). Click to fetch.", status.description)
        assertNull(AheadBehind.parse("main", "origin/main", "fatal: bad revision"))
    }

    @Test
    fun `shows nothing when the branch is up to date`() {
        assertEquals("", AheadBehind.parse("main", "origin/main", "0 0")!!.label)
        assertEquals("↓3", AheadBehind.parse("main", "origin/main", "0 3")!!.label)
        assertEquals("↑1", AheadBehind.parse("main", "origin/main", "1 0")!!.label)
    }
}

class GitDefaultsTest {
    @Test
    fun `nothing touches the remotes or deletes branches unless asked`() {
        val defaults = GitOpsSettings.State()
        assertFalse(defaults.pruneOnSync)
        assertEquals(0, defaults.autoFetchMinutes)
        assertTrue(defaults.inlineBlame)
        assertTrue(defaults.gitmojiCompletion)
    }
}
