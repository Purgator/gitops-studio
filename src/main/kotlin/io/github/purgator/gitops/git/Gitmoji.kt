package io.github.purgator.gitops.git

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import javax.swing.JList

/** [aliases] are the words, besides the gitmoji's own name, that suggest it while typing a commit message. */
data class Gitmoji(val emoji: String, val code: String, val description: String, val aliases: String = "") {
    /** `sparkles` for `:sparkles:`. */
    val name: String get() = code.trim(':')

    /** Lower-case words that bring this gitmoji up: its name first, then its aliases. */
    val keywords: List<String> get() = listOf(name) + aliases.split(' ').filter { it.isNotEmpty() }
}

object Gitmojis {
    val ALL: List<Gitmoji> = listOf(
        Gitmoji("✨", ":sparkles:", "Introduce new features", "feature feat new"),
        Gitmoji("🐛", ":bug:", "Fix a bug", "fix bugfix"),
        Gitmoji("🚑️", ":ambulance:", "Critical hotfix", "hotfix critical urgent"),
        Gitmoji("♻️", ":recycle:", "Refactor code", "refactor refactoring"),
        Gitmoji("🎨", ":art:", "Improve structure / format of the code", "format structure cleanup"),
        Gitmoji("⚡️", ":zap:", "Improve performance", "perf performance speed optimize"),
        Gitmoji("🔥", ":fire:", "Remove code or files", "remove delete"),
        Gitmoji("📝", ":memo:", "Add or update documentation", "docs documentation readme"),
        Gitmoji("✅", ":white_check_mark:", "Add, update, or pass tests", "test tests"),
        Gitmoji("🔖", ":bookmark:", "Release / version tags", "release version tag"),
        Gitmoji("🎉", ":tada:", "Begin a project", "init initial begin"),
        Gitmoji("🚀", ":rocket:", "Deploy stuff", "deploy deployment ship"),
        Gitmoji("💄", ":lipstick:", "Add or update the UI and style files", "ui style css design"),
        Gitmoji("🔒️", ":lock:", "Fix security or privacy issues", "security secure"),
        Gitmoji("🔐", ":closed_lock_with_key:", "Add or update secrets", "secret secrets"),
        Gitmoji("🚨", ":rotating_light:", "Fix compiler / linter warnings", "lint linter warning warnings"),
        Gitmoji("🚧", ":construction:", "Work in progress", "wip progress"),
        Gitmoji("💚", ":green_heart:", "Fix CI build", "ci"),
        Gitmoji("👷", ":construction_worker:", "Add or update CI build system", "ci build pipeline workflow"),
        Gitmoji("⬇️", ":arrow_down:", "Downgrade dependencies", "downgrade"),
        Gitmoji("⬆️", ":arrow_up:", "Upgrade dependencies", "upgrade bump"),
        Gitmoji("📌", ":pushpin:", "Pin dependencies to specific versions", "pin"),
        Gitmoji("➕", ":heavy_plus_sign:", "Add a dependency", "dependency deps"),
        Gitmoji("➖", ":heavy_minus_sign:", "Remove a dependency", "dependency deps"),
        Gitmoji("🔧", ":wrench:", "Add or update configuration files", "config configuration settings chore"),
        Gitmoji("🔨", ":hammer:", "Add or update development scripts", "script scripts"),
        Gitmoji("🌐", ":globe_with_meridians:", "Internationalization and localization", "i18n l10n translation locale"),
        Gitmoji("✏️", ":pencil2:", "Fix typos", "typo"),
        Gitmoji("💩", ":poop:", "Write bad code that needs to be improved", "bad hack"),
        Gitmoji("⏪️", ":rewind:", "Revert changes", "revert undo"),
        Gitmoji("🔀", ":twisted_rightwards_arrows:", "Merge branches", "merge"),
        Gitmoji("📦️", ":package:", "Add or update compiled files or packages", "dist bundle"),
        Gitmoji("👽️", ":alien:", "Update code due to external API changes", "api external"),
        Gitmoji("🚚", ":truck:", "Move or rename resources", "move rename"),
        Gitmoji("📄", ":page_facing_up:", "Add or update license", "license"),
        Gitmoji("💥", ":boom:", "Introduce breaking changes", "breaking"),
        Gitmoji("🍱", ":bento:", "Add or update assets", "assets asset"),
        Gitmoji("♿️", ":wheelchair:", "Improve accessibility", "a11y accessibility"),
        Gitmoji("💡", ":bulb:", "Add or update comments in source code", "comment comments"),
        Gitmoji("💬", ":speech_balloon:", "Add or update text and literals", "text literal wording"),
        Gitmoji("🗃️", ":card_file_box:", "Perform database related changes", "database db migration sql"),
        Gitmoji("🔊", ":loud_sound:", "Add or update logs", "log logs logging"),
        Gitmoji("🔇", ":mute:", "Remove logs", "log logs"),
        Gitmoji("👥", ":busts_in_silhouette:", "Add or update contributors", "contributor contributors"),
        Gitmoji("🚸", ":children_crossing:", "Improve user experience / usability", "ux usability"),
        Gitmoji("🏗️", ":building_construction:", "Make architectural changes", "architecture"),
        Gitmoji("📱", ":iphone:", "Work on responsive design", "responsive mobile"),
        Gitmoji("🤡", ":clown_face:", "Mock things", "mock mocks"),
        Gitmoji("🙈", ":see_no_evil:", "Add or update a .gitignore file", "gitignore ignore"),
        Gitmoji("📸", ":camera_flash:", "Add or update snapshots", "snapshot snapshots"),
        Gitmoji("⚗️", ":alembic:", "Perform experiments", "experiment"),
        Gitmoji("🔍️", ":mag:", "Improve SEO", "seo"),
        Gitmoji("🏷️", ":label:", "Add or update types", "type types typing"),
        Gitmoji("🌱", ":seedling:", "Add or update seed files", "seed"),
        Gitmoji("🚩", ":triangular_flag_on_post:", "Add, update, or remove feature flags", "flag flags"),
        Gitmoji("🥅", ":goal_net:", "Catch errors", "error errors catch"),
        Gitmoji("💫", ":dizzy:", "Add or update animations and transitions", "animation transition"),
        Gitmoji("🗑️", ":wastebasket:", "Deprecate code that needs to be cleaned up", "deprecate deprecated"),
        Gitmoji("🛂", ":passport_control:", "Work on authorization, roles and permissions", "auth authorization permission roles"),
        Gitmoji("🩹", ":adhesive_bandage:", "Simple fix for a non-critical issue", "fix patch"),
        Gitmoji("🧐", ":monocle_face:", "Data exploration / inspection", "inspect explore data"),
        Gitmoji("⚰️", ":coffin:", "Remove dead code", "dead"),
        Gitmoji("🧪", ":test_tube:", "Add a failing test", "test failing"),
        Gitmoji("👔", ":necktie:", "Add or update business logic", "business logic"),
        Gitmoji("🩺", ":stethoscope:", "Add or update healthcheck", "health healthcheck"),
        Gitmoji("🧱", ":bricks:", "Infrastructure related changes", "infra infrastructure"),
        Gitmoji("🧑‍💻", ":technologist:", "Improve developer experience", "dx devex"),
        Gitmoji("💸", ":money_with_wings:", "Add sponsorships or money related infrastructure", "sponsor money"),
        Gitmoji("🧵", ":thread:", "Add or update code related to multithreading or concurrency", "concurrency threading async"),
        Gitmoji("🦺", ":safety_vest:", "Add or update code related to validation", "validation validate"),
        Gitmoji("✈️", ":airplane:", "Improve offline support", "offline"),
    )

    /** Puts [gitmoji] at the start of [message], replacing a gitmoji (emoji or code) already there. */
    fun apply(message: String, gitmoji: Gitmoji, asCode: Boolean): String = prefix(gitmoji, asCode) + strip(message)

    /** What goes in front of the message: the gitmoji and the space that follows it. */
    fun prefix(gitmoji: Gitmoji, asCode: Boolean): String = (if (asCode) gitmoji.code else gitmoji.emoji) + " "

    /** Length of what [apply] replaces at the start of [message]: leading blanks and a gitmoji, if any. */
    fun prefixLength(message: String): Int = message.length - strip(message).length

    /**
     * The word being typed when the caret, at [offset], is still in the first word of the message:
     * `feat` for `feat|`, an empty string for an empty message, null anywhere else.
     */
    fun firstWord(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        return text.subSequence(0, offset).toString().takeIf { FIRST_WORD.matches(it) }
    }

    /** Gitmojis suggested by [word]: one of their keywords starts with it. Exact keywords come first. */
    fun matching(word: String): List<Gitmoji> {
        val typed = word.trim(':').lowercase()
        if (typed.isEmpty()) return ALL
        val (exact, partial) = ALL.filter { g -> g.keywords.any { it.startsWith(typed) } }.partition { typed in it.keywords }
        return exact + partial
    }

    /** Replaces the gitmoji of the commit message in [document] and leaves the caret right after it, ready to type. */
    fun insert(project: Project, document: Document, gitmoji: Gitmoji, asCode: Boolean) {
        val prefix = prefix(gitmoji, asCode)
        WriteCommandAction.runWriteCommandAction(project, "Insert Gitmoji", null, {
            document.replaceString(0, prefixLength(document.text), prefix)
            EditorFactory.getInstance().editors(document, project).forEach { editor ->
                if (editor.caretModel.offset < prefix.length) editor.caretModel.moveToOffset(prefix.length)
            }
        })
        val editor = EditorFactory.getInstance().editors(document, project).findFirst().orElse(null) ?: return
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    private val FIRST_WORD = Regex(":?[A-Za-z0-9_+-]*")

    /** [message] without its leading gitmoji, if it has one. */
    fun strip(message: String): String {
        val trimmed = message.trimStart()
        val known = ALL.asSequence().flatMap { sequenceOf(it.emoji, it.code, it.emoji.trimEnd('️')) }
            .filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .firstOrNull { trimmed.startsWith(it) }
        if (known == null) return trimmed
        return trimmed.removePrefix(known).trimStart('️').trimStart()
    }
}

/** Button in the commit message toolbar: pick a gitmoji, it lands at the start of the message. */
class GitmojiAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val settings = GitOpsSettings.get().values
        e.presentation.isEnabledAndVisible = e.project != null &&
            ModuleRegistry.get().isEnabled(ModuleRegistry.GIT) && settings.gitmojiEnabled &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document: Document = e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) ?: return
        val asCode = GitOpsSettings.get().values.gitmojiAsCode
        JBPopupFactory.getInstance().createPopupChooserBuilder(Gitmojis.ALL)
            .setTitle("Gitmoji")
            .setNamerForFiltering { "${it.code} ${it.description} ${it.aliases}" }
            .setRenderer(object : ColoredListCellRenderer<Gitmoji>() {
                override fun customizeCellRenderer(list: JList<out Gitmoji>, value: Gitmoji?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    value ?: return
                    append(value.emoji + "  ")
                    append(value.description)
                    append("  ${value.code}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { chosen -> Gitmojis.insert(project, document, chosen, asCode) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}

/**
 * Plain text editors, such as the commit message, do not open the completion popup by themselves while
 * typing. Open it as soon as a character lands in the first word of the commit message.
 */
class GitmojiTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        val document = editor.document
        if (!(charTyped.isLetterOrDigit() || charTyped == ':' || charTyped == '_')) return Result.CONTINUE
        if (!CommitMessage.isCommitMessage(document)) return Result.CONTINUE
        val settings = GitOpsSettings.get().values
        if (!settings.gitmojiCompletion || !ModuleRegistry.get().isEnabled(ModuleRegistry.GIT)) return Result.CONTINUE
        // The typed character is not in the document yet: only the text before the caret matters.
        val before = document.charsSequence.subSequence(0, editor.caretModel.offset)
        if (Gitmojis.firstWord(before, before.length) == null) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

/** Never skip the auto-popup in the commit message: plain text normally does. */
class GitmojiCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val document = psiFile.viewProvider.document ?: return ThreeState.UNSURE
        return if (CommitMessage.isCommitMessage(document)) ThreeState.NO else ThreeState.UNSURE
    }
}

/**
 * Suggests gitmojis while the first word of a commit message is typed: `feature`, `fix`, `docs`, … or the
 * gitmoji's own name (`sparkles`). Accepting one replaces the word with the gitmoji.
 */
class GitmojiCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val settings = GitOpsSettings.get().values
        if (!settings.gitmojiCompletion || !ModuleRegistry.get().isEnabled(ModuleRegistry.GIT)) return
        val document = parameters.editor.document
        if (!CommitMessage.isCommitMessage(document)) return
        val word = Gitmojis.firstWord(document.charsSequence, parameters.offset) ?: return
        if (word.isEmpty() && parameters.isAutoPopup) return

        val matches = Gitmojis.matching(word)
        val keywords = result.withPrefixMatcher(KeywordMatcher(word))
        matches.forEachIndexed { index, gitmoji ->
            val element = LookupElementBuilder.create(gitmoji, if (settings.gitmojiAsCode) gitmoji.code else gitmoji.emoji)
                .withLookupStrings(gitmoji.keywords)
                .withPresentableText("${gitmoji.emoji}  ${gitmoji.description}")
                .withTypeText(gitmoji.code)
                .withInsertHandler { context, _ ->
                    val tail = context.tailOffset
                    val text = context.document.charsSequence
                    if (tail >= text.length || text[tail] != ' ') context.document.insertString(tail, " ")
                    context.editor.caretModel.moveToOffset(tail + 1)
                }
            // Keep the order of Gitmojis.matching: exact keywords, then the usual gitmoji order.
            keywords.addElement(PrioritizedLookupElement.withPriority(element, (matches.size - index).toDouble()))
        }
    }

    /** Matches on keywords whatever the case: commit messages usually start with a capital letter. */
    private class KeywordMatcher(typed: String) : PrefixMatcher(typed) {
        override fun prefixMatches(name: String): Boolean = name.trim(':').startsWith(prefix.trim(':'), ignoreCase = true)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = KeywordMatcher(prefix)
    }
}
