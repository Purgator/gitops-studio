package io.github.purgator.gitops.core.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Runs short-lived command line tools (`gh`, `az`, `git credential`). Never call from the UI thread. */
object Cli {
    class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /** Full path of [tool] on `PATH`, or null. Handles the `.exe` / `.cmd` / `.bat` variants on Windows. */
    fun find(tool: String): String? {
        if (File(tool).isAbsolute) return tool.takeIf { File(it).isFile }
        val names = if (SystemInfo.isWindows) listOf("$tool.exe", "$tool.cmd", "$tool.bat", tool) else listOf(tool)
        val directories = EnvironmentUtil.getValue("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        return directories.firstNotNullOfOrNull { directory ->
            names.map { File(directory, it) }.firstOrNull { it.isFile }?.absolutePath
        }
    }

    @Throws(IOException::class)
    fun run(
        executable: String,
        args: List<String>,
        stdin: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20_000,
    ): Result {
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(env)
        try {
            val process = commandLine.createProcess()
            process.outputStream.use { out ->
                if (stdin != null) out.write(stdin.toByteArray(StandardCharsets.UTF_8))
            }
            val output = CapturingProcessHandler(process, StandardCharsets.UTF_8, commandLine.commandLineString)
                .runProcess(timeoutMs, true)
            return Result(output.exitCode, output.stdout, output.stderr, output.isTimeout)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not run ${File(executable).name}: ${e.message}", e)
        }
    }
}
