package io.github.purgator.gitops.ui.pr

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.util.Cli
import io.github.purgator.gitops.git.GitSupport
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** What the description generator does with the text it gets back. */
enum class AiMode(val label: String) {
    APPEND("Append the result to the description"),
    REPLACE("Replace the description with the result"),
    ADAPT("Rewrite the current description (keeps a template, fills and rephrases it)");

    override fun toString(): String = label

    companion object {
        fun of(name: String): AiMode = entries.firstOrNull { it.name == name } ?: REPLACE
    }
}

/** The texts of a pull request that are built from templates: merge message, commit list, generator prompt. */
object PullRequestText {
    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")
    private const val MAX_PROMPT_PART = 20_000

    /** Replaces `{name}` by its value; unknown names stay as typed. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: it.value }

    /**
     * The merge commit message for [pr], or null when [template] is empty (the hosting service then writes its own).
     * Placeholders: `{title}`, `{id}`, `{source}`, `{target}`, `{author}`, `{description}`.
     */
    fun mergeMessage(template: String, pr: PullRequest): String? {
        if (template.isBlank()) return null
        val values = mapOf(
            "title" to pr.title,
            "id" to pr.id,
            "source" to pr.sourceBranch,
            "target" to pr.targetBranch,
            "author" to pr.author.label,
            "description" to pr.description,
        )
        return fill(template.replace("\\n", "\n"), values).trim().ifEmpty { null }
    }

    /** One bullet per commit, oldest first. */
    fun commitList(commits: List<GitSupport.Commit>): String =
        commits.reversed().joinToString("\n") { "- ${it.subject}" }

    /** [description] followed by [block], separated by one blank line. */
    fun append(description: String, block: String): String = when {
        block.isBlank() -> description
        description.isBlank() -> block.trim()
        else -> description.trimEnd() + "\n\n" + block.trim()
    }

    /**
     * The prompt sent to the generator. Placeholders: `{title}`, `{source}`, `{target}`, `{commits}`,
     * `{files}`, `{description}`. In [AiMode.ADAPT] the current description is always part of it.
     */
    fun prompt(template: String, mode: AiMode, values: Map<String, String>): String {
        val bounded = values.mapValues { (_, value) -> if (value.length > MAX_PROMPT_PART) value.take(MAX_PROMPT_PART) + "\n…" else value }
        val prompt = fill(template, bounded).trim()
        if (mode != AiMode.ADAPT || "{description}" in template) return prompt
        return prompt + "\n\nHere is the current description. Keep its structure and headings, fill in what is empty, " +
            "rephrase or complete the rest, and reply with the full description only:\n\n" + bounded["description"].orEmpty()
    }

    /** The description once the generator answered with [result]. */
    fun applyResult(mode: AiMode, description: String, result: String): String = when (mode) {
        AiMode.APPEND -> append(description, result)
        AiMode.REPLACE, AiMode.ADAPT -> result.trim().ifEmpty { description }
    }
}

/**
 * Runs the description generator: any command that reads a prompt on its standard input and prints
 * the answer, such as `claude -p` or `ollama run llama3`. Blocking; never call from the UI thread.
 */
object DescriptionGenerator {
    private const val TIMEOUT_MS = 180_000

    @Throws(IOException::class)
    fun run(command: String, prompt: String, workingDirectory: File?): String {
        val parts = ParametersListUtil.parse(command.trim())
        if (parts.isEmpty()) throw IOException("No generator command is configured.")
        val executable = Cli.find(parts[0]) ?: throw IOException("\"${parts[0]}\" was not found on the PATH.")
        // Scripts (.cmd, .bat) are not programs on Windows; they go through the command interpreter.
        val script = SystemInfo.isWindows && (executable.endsWith(".cmd", true) || executable.endsWith(".bat", true))
        val commandLine = (if (script) GeneralCommandLine("cmd.exe", "/c", executable) else GeneralCommandLine(executable))
            .withParameters(parts.drop(1))
            .withCharset(StandardCharsets.UTF_8)
        if (workingDirectory != null && workingDirectory.isDirectory) commandLine.withWorkDirectory(workingDirectory)
        val output = try {
            val process = commandLine.createProcess()
            process.outputStream.use { it.write(prompt.toByteArray(StandardCharsets.UTF_8)) }
            CapturingProcessHandler(process, StandardCharsets.UTF_8, commandLine.commandLineString).runProcess(TIMEOUT_MS, true)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not run ${parts[0]}: ${e.message}", e)
        }
        if (output.isTimeout) throw IOException("${parts[0]} did not answer within ${TIMEOUT_MS / 1000} seconds.")
        if (output.exitCode != 0) {
            throw IOException("${parts[0]} failed (${output.exitCode}): " + output.stderr.ifBlank { output.stdout }.trim().take(400))
        }
        return output.stdout.trim().ifEmpty { throw IOException("${parts[0]} printed nothing.") }
    }
}
