package io.github.purgator.gitops

import com.intellij.openapi.util.SystemInfo
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.PipelineJob
import io.github.purgator.gitops.core.model.PipelineStage
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.git.GitSupport
import io.github.purgator.gitops.ui.pipelines.StageRows
import io.github.purgator.gitops.ui.pr.AiMode
import io.github.purgator.gitops.ui.pr.DescriptionGenerator
import io.github.purgator.gitops.ui.pr.PullRequestText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class PullRequestTextTest {
    private val pr = PullRequest(
        id = "42", title = "Add login", description = "Body", state = PrState.OPEN, author = User("u1", "Ann Lee"),
        sourceBranch = "feature/login", targetBranch = "main", webUrl = "https://example.com/pr/42",
    )

    @Test
    fun `builds the merge message from the template`() {
        assertEquals("🔀 Add login (#42)", PullRequestText.mergeMessage("🔀 {title} (#{id})", pr))
        assertEquals("Merge feature/login into main 🔀", PullRequestText.mergeMessage("Merge {source} into {target} 🔀", pr))
        assertEquals("Add login\n\nBody\nby Ann Lee", PullRequestText.mergeMessage("{title}\\n\\n{description}\\nby {author}", pr))
        // No template: the hosting service writes its own message.
        assertNull(PullRequestText.mergeMessage("", pr))
        assertNull(PullRequestText.mergeMessage("   ", pr))
        assertEquals("{unknown} Add login", PullRequestText.mergeMessage("{unknown} {title}", pr))
    }

    @Test
    fun `lists commits oldest first and appends them to the description`() {
        val commits = listOf(GitSupport.Commit("b", "Second", ""), GitSupport.Commit("a", "First", "details"))
        val list = PullRequestText.commitList(commits)
        assertEquals("- First\n- Second", list)
        assertEquals("- First\n- Second", PullRequestText.append("", list))
        assertEquals("## Why\nBecause.\n\n- First\n- Second", PullRequestText.append("## Why\nBecause.\n\n", list))
        assertEquals("Kept", PullRequestText.append("Kept", ""))
    }

    @Test
    fun `fills the prompt and adds the description when adapting it`() {
        val values = mapOf("title" to "Add login", "source" to "feature/login", "target" to "main", "commits" to "- First", "files" to "a.kt | 2 +", "description" to "## Why\n")
        val plain = PullRequestText.prompt("Describe {title} ({source} to {target}):\n{commits}", AiMode.REPLACE, values)
        assertEquals("Describe Add login (feature/login to main):\n- First", plain)

        val adapted = PullRequestText.prompt("Describe {title}", AiMode.ADAPT, values)
        assertTrue(adapted.startsWith("Describe Add login\n\nHere is the current description."))
        assertTrue(adapted.endsWith("## Why\n"))
        // A prompt that already places the description is left as written.
        assertEquals("Improve: ## Why", PullRequestText.prompt("Improve: {description}", AiMode.ADAPT, values))

        val default = PullRequestText.prompt(GitOpsSettings.DEFAULT_AI_PROMPT, AiMode.REPLACE, values)
        assertTrue(default.contains("\"Add login\"") && default.contains("- First") && default.contains("a.kt | 2 +"))
        assertFalse(default.contains("{"))
    }

    @Test
    fun `very long inputs are cut before they reach the prompt`() {
        val prompt = PullRequestText.prompt("{files}", AiMode.REPLACE, mapOf("files" to "x".repeat(50_000)))
        assertTrue(prompt.length < 21_000)
    }

    @Test
    fun `uses the answer as asked`() {
        assertEquals("Old\n\nNew", PullRequestText.applyResult(AiMode.APPEND, "Old", " New \n"))
        assertEquals("New", PullRequestText.applyResult(AiMode.REPLACE, "Old", "New"))
        assertEquals("New", PullRequestText.applyResult(AiMode.ADAPT, "Old", "New"))
        assertEquals("Old", PullRequestText.applyResult(AiMode.REPLACE, "Old", "  "))
        assertEquals(AiMode.REPLACE, AiMode.of("nonsense"))
        assertEquals(AiMode.ADAPT, AiMode.of("ADAPT"))
    }

    @Test
    fun `the generator sends the prompt on standard input and returns what is printed`() {
        // `sort` exists on every platform, reads its input and prints it back.
        assumeTrue(io.github.purgator.gitops.core.util.Cli.find("sort") != null)
        assertEquals("a line\nb line", DescriptionGenerator.run("sort", "b line\na line\n", null).replace("\r\n", "\n"))
        val missing = assertThrows(IOException::class.java) { DescriptionGenerator.run("no-such-tool-xyz --flag", "prompt", null) }
        assertTrue(missing.message!!.contains("no-such-tool-xyz"))
        assertThrows(IOException::class.java) { DescriptionGenerator.run("  ", "prompt", null) }
        if (!SystemInfo.isWindows) {
            val failed = assertThrows(IOException::class.java) { DescriptionGenerator.run("sort --no-such-option", "x", null) }
            assertTrue(failed.message!!.contains("failed"))
        }
    }
}

class StageRowsTest {
    private fun job(name: String, stage: String, state: CheckState = CheckState.SUCCESS) = PipelineJob(name, name, state, stage)

    @Test
    fun `puts jobs under the stages the service reports`() {
        val stages = listOf(PipelineStage("build", "Build", CheckState.SUCCESS), PipelineStage("deploy", "Deploy", CheckState.FAILURE))
        val rows = StageRows.build(stages, listOf(job("Publish", "Deploy", CheckState.FAILURE), job("Compile", "Build"), job("Test", "Build")))
        assertEquals(listOf("Build", "Compile", "Test", "Deploy", "Publish"), rows.map { (it as? PipelineStage)?.name ?: (it as PipelineJob).name })
    }

    @Test
    fun `deduces stages from the jobs when the service reports none`() {
        val rows = StageRows.build(emptyList(), listOf(job("lint", "test"), job("unit", "test", CheckState.FAILURE), job("image", "build"), job("notify", "")))
        assertEquals(listOf("test", "lint", "unit", "build", "image", "notify"), rows.map { (it as? PipelineStage)?.name ?: (it as PipelineJob).name })
        assertEquals(CheckState.FAILURE, (rows[0] as PipelineStage).state)
        assertEquals(CheckState.SUCCESS, (rows[3] as PipelineStage).state)
    }

    @Test
    fun `a run without stages is a plain list of jobs`() {
        val jobs = listOf(job("a", ""), job("b", ""))
        assertEquals(jobs, StageRows.build(emptyList(), jobs))
    }
}
