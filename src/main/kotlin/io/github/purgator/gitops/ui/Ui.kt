package io.github.purgator.gitops.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.purgator.gitops.core.model.CheckState
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.Vote
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.time.Duration
import java.time.Instant
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

object GitOpsIcons {
    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", GitOpsIcons::class.java)

    @JvmField val ToolWindow: Icon = load("toolWindow")
    @JvmField val Logo: Icon = load("gitops")
    @JvmField val Gitmoji: Icon = load("gitmoji")
    @JvmField val PrOpen: Icon = load("prOpen")
    @JvmField val PrDraft: Icon = load("prDraft")
    @JvmField val PrMerged: Icon = load("prMerged")
    @JvmField val PrClosed: Icon = load("prClosed")

    fun provider(id: String): Icon =
        IconLoader.findIcon("/icons/$id.svg", GitOpsIcons::class.java) ?: AllIcons.Vcs.Vendors.Github

    fun of(state: PrState): Icon = when (state) {
        PrState.OPEN -> PrOpen
        PrState.DRAFT -> PrDraft
        PrState.MERGED -> PrMerged
        PrState.CLOSED -> PrClosed
    }

    fun of(state: CheckState): Icon = when (state) {
        CheckState.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        CheckState.FAILURE -> AllIcons.RunConfigurations.TestFailed
        CheckState.RUNNING -> AllIcons.Actions.Execute
        CheckState.PENDING -> AllIcons.RunConfigurations.TestNotRan
        CheckState.CANCELLED -> AllIcons.RunConfigurations.TestTerminated
        CheckState.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
        CheckState.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
    }
}

/** Colours and HTML fragments shared by the detail views. */
object Html {
    val green: Color = JBColor(Color(0x2E8B57), Color(0x5FB865))
    val red: Color = JBColor(Color(0xC7333B), Color(0xE0606A))
    val orange: Color = JBColor(Color(0xB8791A), Color(0xE0A33E))
    val purple: Color = JBColor(Color(0x7B4FD0), Color(0xA98BE8))
    val gray: Color get() = UIUtil.getContextHelpForeground()

    fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    fun colored(text: String, color: Color): String = "<font color=\"${ColorUtil.toHtmlColor(color)}\">$text</font>"

    fun muted(text: String): String = colored(esc(text), gray)

    fun link(text: String, url: String): String = "<a href=\"${esc(url)}\">${esc(text)}</a>"

    fun color(state: PrState): Color = when (state) {
        PrState.OPEN -> green
        PrState.DRAFT -> gray
        PrState.MERGED -> purple
        PrState.CLOSED -> red
    }

    fun color(state: CheckState): Color = when (state) {
        CheckState.SUCCESS -> green
        CheckState.FAILURE -> red
        CheckState.RUNNING, CheckState.PENDING -> orange
        else -> gray
    }

    fun symbol(state: CheckState): String = when (state) {
        CheckState.SUCCESS -> "✔"
        CheckState.FAILURE -> "✖"
        CheckState.RUNNING -> "●"
        CheckState.PENDING -> "○"
        CheckState.CANCELLED -> "⊘"
        CheckState.SKIPPED -> "–"
        CheckState.UNKNOWN -> "?"
    }

    fun vote(vote: Vote): String = when (vote) {
        Vote.APPROVED -> colored("✔", green)
        Vote.APPROVED_WITH_SUGGESTIONS -> colored("✔", orange)
        Vote.WAITING -> colored("●", orange)
        Vote.REJECTED -> colored("✖", red)
        Vote.NONE -> colored("○", gray)
    }

    /** Markdown rendered for a Swing HTML pane; falls back to escaped text when the converter chokes. */
    fun markdown(text: String): String {
        if (text.isBlank()) return ""
        return runCatching { convertMarkdownToHtml(text) }
            .getOrElse { esc(text).replace("\n", "<br/>") }
    }

    fun page(body: String): String = "<html><body>$body</body></html>"
}

/** Read-only HTML pane following the IDE look; plain links open in the browser, others go to [onLink]. */
fun htmlPane(onLink: (String) -> Unit = {}): JEditorPane {
    val pane = JEditorPane()
    pane.editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
    pane.isEditable = false
    pane.isOpaque = false
    pane.border = JBUI.Borders.empty(8, 12)
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    pane.addHyperlinkListener { event ->
        if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
        val target = event.description ?: return@addHyperlinkListener
        if (target.startsWith("http://") || target.startsWith("https://")) BrowserUtil.browse(target) else onLink(target)
    }
    return pane
}

fun JEditorPane.showHtml(body: String) {
    text = Html.page(body)
    caretPosition = 0
}

object Format {
    fun date(instant: Instant?): String =
        if (instant == null) "" else DateFormatUtil.formatPrettyDateTime(instant.toEpochMilli())

    fun duration(start: Instant?, end: Instant?): String {
        if (start == null) return ""
        val millis = Duration.between(start, end ?: Instant.now()).toMillis().coerceAtLeast(0)
        return StringUtil.formatDuration(millis / 1000 * 1000)
    }

    /** `feature/1234-add-login` → `Add login`. */
    fun titleFromBranch(branch: String): String {
        val words = branch.substringAfterLast('/')
            .replace(Regex("^\\d+[-_]"), "")
            .replace(Regex("[-_]+"), " ")
            .trim()
        return words.replaceFirstChar { it.uppercase() }
    }

    /** Work item / issue ids hinted at by a branch name: `feature/1234-x`, `fix/AB#77`, `1234_foo`. */
    fun issueIdsFromBranch(branch: String): List<String> =
        Regex("(?:^|[/_#-])(\\d{1,7})(?=$|[-_/])").findAll(branch).map { it.groupValues[1] }.distinct().toList()

    fun slug(text: String, max: Int = 50): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(max)
            .trimEnd('-')
}

fun copyToClipboard(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))
