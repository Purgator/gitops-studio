package io.github.purgator.gitops.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil
import com.intellij.vcsUtil.VcsUtil
import git4idea.commands.GitCommand
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import java.awt.Color
import java.awt.Font
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Who last changed a line: the commit, its author, its date and its subject. */
class BlameLine(val hash: String, val author: String, val time: Instant?, val summary: String)

object Blame {
    private val HEADER = Regex("^([0-9a-f]{40,64}) \\d+ (\\d+)(?: \\d+)?$")
    private const val MAX_SUBJECT = 80

    /**
     * Reads the output of `git blame --porcelain`. The result has one entry per line of the file, in
     * order; a line git said nothing about stays null.
     */
    fun parse(output: List<String>): List<BlameLine?> {
        class Commit(val hash: String) {
            var author = ""
            var time: Instant? = null
            var summary = ""
            val line by lazy { BlameLine(hash, author, time, summary) }
        }

        val commits = HashMap<String, Commit>()
        val owners = ArrayList<Commit?>()
        var current: Commit? = null
        for (raw in output) {
            if (raw.startsWith("\t")) continue // the content of the line
            val header = HEADER.matchEntire(raw)
            if (header != null) {
                val commit = commits.getOrPut(header.groupValues[1]) { Commit(header.groupValues[1]) }
                val index = header.groupValues[2].toInt() - 1
                while (owners.size <= index) owners.add(null)
                owners[index] = commit
                current = commit
                continue
            }
            // Details follow the first header of each commit only.
            val commit = current ?: continue
            when {
                raw.startsWith("author ") -> commit.author = raw.removePrefix("author ").trim()
                raw.startsWith("author-time ") -> commit.time = raw.removePrefix("author-time ").trim().toLongOrNull()?.let(Instant::ofEpochSecond)
                raw.startsWith("summary ") -> commit.summary = raw.removePrefix("summary ").trim()
            }
        }
        return owners.map { it?.line }
    }

    private val PLACEHOLDER = Regex("\\{(\\w+)(?::([^}]*))?\\}")
    private const val DEFAULT_DATE = "yyyy-MM-dd"

    /**
     * Fills [format] for [line]. `{author}`, `{subject}`, `{hash}` (short), `{ago}` (3 days ago),
     * `{date}` (2026-11-25) and `{date:dd/MM/yyyy HH:mm}` with any date pattern. Unknown placeholders stay as typed.
     */
    fun text(line: BlameLine, format: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        PLACEHOLDER.replace(format) { match ->
            when (match.groupValues[1]) {
                "author" -> line.author
                "subject" -> line.summary.let { if (it.length > MAX_SUBJECT) it.take(MAX_SUBJECT - 1).trimEnd() + "…" else it }
                "hash" -> line.hash.take(8)
                "ago" -> line.time?.let { ago(it, now) }.orEmpty()
                "date" -> line.time?.let { time ->
                    val pattern = match.groupValues[2].ifBlank { DEFAULT_DATE }
                    val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }.getOrElse { DateTimeFormatter.ofPattern(DEFAULT_DATE) }
                    runCatching { formatter.format(time.atZone(zone)) }.getOrDefault("")
                }.orEmpty()
                else -> match.value
            }
        }.trim()

    fun ago(time: Instant, now: Instant = Instant.now()): String {
        val elapsed = Duration.between(time, now)
        val minutes = elapsed.toMinutes()
        val days = elapsed.toDays()
        fun count(n: Long, unit: String) = "$n $unit" + (if (n == 1L) "" else "s") + " ago"
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> count(minutes, "minute")
            elapsed.toHours() < 24 -> count(elapsed.toHours(), "hour")
            days == 1L -> "yesterday"
            days < 30 -> count(days, "day")
            days < 365 -> count(days / 30, "month")
            else -> count(days / 365, "year")
        }
    }

    fun isEnabled(): Boolean = GitOpsSettings.get().values.inlineBlame && ModuleRegistry.get().isEnabled(ModuleRegistry.GIT)

    /** Call after the setting changed: the annotation appears or disappears at once. */
    fun repaintEditors() {
        EditorFactory.getInstance().allEditors.forEach { it.contentComponent.repaint() }
    }
}

/**
 * Blame of the files open in the editor. A file is blamed once per commit of the repository, in the
 * background and as committed in `HEAD`; lines changed since then have no blame.
 */
@Service(Service.Level.PROJECT)
class BlameService(private val project: Project) : Disposable {
    private class Entry(val revision: String) {
        /** Null while git is still working. */
        @Volatile
        var lines: List<BlameLine?>? = null
    }

    private val cache = ConcurrentHashMap<VirtualFile, Entry>()

    init {
        // The annotation follows the caret: both the line it left and the one it reached need painting.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = event.editor
                if (editor.project != project || event.oldPosition.line == event.newPosition.line || !Blame.isEnabled()) return
                repaintLine(editor, event.oldPosition.line)
                repaintLine(editor, event.newPosition.line)
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                cache.remove(file)
            }
        })
    }

    /** Blame of [line] (0-based, as shown in the editor), or null when it is unknown, not ready yet or the line is not committed. */
    fun lineInfo(file: VirtualFile, line: Int): BlameLine? {
        val lines = blame(file) ?: return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val tracker = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(document)
        val committedLine = when {
            tracker != null && tracker.isOperational() -> if (tracker.isLineModified(line)) return null else tracker.transferLineToVcs(line, false)
            // Without a tracker the lines only match the commit when the file was not touched.
            FileStatusManager.getInstance(project).getStatus(file) == FileStatus.NOT_CHANGED &&
                !FileDocumentManager.getInstance().isDocumentUnsaved(document) -> line
            else -> return null
        }
        return lines.getOrNull(committedLine)
    }

    private fun blame(file: VirtualFile): List<BlameLine?>? {
        if (!file.isInLocalFileSystem || file.isDirectory || file.length > MAX_FILE_SIZE) return null
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
        val revision = repo.currentRevision ?: return null
        val known = cache[file]
        if (known != null && known.revision == revision) return known.lines

        val entry = Entry(revision)
        cache[file] = entry
        val path = VfsUtilCore.getRelativePath(file, repo.root) ?: return null
        ApplicationManager.getApplication().executeOnPooledThread {
            // An untracked or brand new file has no blame: remember that too, until the next commit.
            entry.lines = runCatching { Blame.parse(GitSupport.run(project, repo, GitCommand.BLAME, "--porcelain", revision, "--", path)) }
                .getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@invokeLater
                EditorFactory.getInstance().editors(document, project).forEach { repaintLine(it, it.caretModel.logicalPosition.line) }
            }, project.disposed)
        }
        return null
    }

    private fun repaintLine(editor: Editor, line: Int) {
        if (editor.isDisposed) return
        val component = editor.contentComponent
        component.repaint(0, editor.logicalPositionToXY(LogicalPosition(line, 0)).y, component.width, editor.lineHeight)
    }

    override fun dispose() {
        cache.clear()
    }

    companion object {
        private const val MAX_FILE_SIZE = 2_000_000L

        fun get(project: Project): BlameService = project.service()
    }
}

/** Paints, at the end of the line the caret is on, who last changed it and when. */
class InlineBlamePainter : EditorLinePainter(), DumbAware {
    private val attributes = TextAttributes(JBColor(Color(0x8C8C8C), Color(0x6F737A)), null, null, null, Font.ITALIC)

    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        if (!Blame.isEnabled() || project.isDisposed) return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (editor.caretModel.logicalPosition.line != lineNumber || editor.virtualFile != file) return null
        val info = BlameService.get(project).lineInfo(file, lineNumber) ?: return null
        return listOf(LineExtensionInfo("      " + Blame.text(info, GitOpsSettings.get().values.inlineBlameFormat), attributes))
    }
}

class ToggleInlineBlameAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project != null && ModuleRegistry.get().isEnabled(ModuleRegistry.GIT)
    }

    override fun isSelected(e: AnActionEvent): Boolean = GitOpsSettings.get().values.inlineBlame

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        GitOpsSettings.get().values.inlineBlame = state
        Blame.repaintEditors()
    }
}

/** Opens, in the Git log, the commit that last changed the line the caret is on. */
class ShowLineCommitAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val tracked = project != null && file != null && editor != null && ModuleRegistry.get().isEnabled(ModuleRegistry.GIT) &&
            GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) != null
        e.presentation.isVisible = tracked
        e.presentation.isEnabled = tracked && blamed(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val info = blamed(e) ?: return
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return
        VcsLogNavigationUtil.jumpToRevisionAsync(project, repo.root, HashImpl.build(info.hash), VcsUtil.getFilePath(file))
    }

    private fun blamed(e: AnActionEvent): BlameLine? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return BlameService.get(project).lineInfo(file, editor.caretModel.logicalPosition.line)
    }
}
