package io.github.purgator.gitops.git

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import javax.swing.JList

data class Gitmoji(val emoji: String, val code: String, val description: String)

object Gitmojis {
    val ALL: List<Gitmoji> = listOf(
        Gitmoji("✨", ":sparkles:", "Introduce new features"),
        Gitmoji("🐛", ":bug:", "Fix a bug"),
        Gitmoji("🚑️", ":ambulance:", "Critical hotfix"),
        Gitmoji("♻️", ":recycle:", "Refactor code"),
        Gitmoji("🎨", ":art:", "Improve structure / format of the code"),
        Gitmoji("⚡️", ":zap:", "Improve performance"),
        Gitmoji("🔥", ":fire:", "Remove code or files"),
        Gitmoji("📝", ":memo:", "Add or update documentation"),
        Gitmoji("✅", ":white_check_mark:", "Add, update, or pass tests"),
        Gitmoji("🔖", ":bookmark:", "Release / version tags"),
        Gitmoji("🎉", ":tada:", "Begin a project"),
        Gitmoji("🚀", ":rocket:", "Deploy stuff"),
        Gitmoji("💄", ":lipstick:", "Add or update the UI and style files"),
        Gitmoji("🔒️", ":lock:", "Fix security or privacy issues"),
        Gitmoji("🔐", ":closed_lock_with_key:", "Add or update secrets"),
        Gitmoji("🚨", ":rotating_light:", "Fix compiler / linter warnings"),
        Gitmoji("🚧", ":construction:", "Work in progress"),
        Gitmoji("💚", ":green_heart:", "Fix CI build"),
        Gitmoji("👷", ":construction_worker:", "Add or update CI build system"),
        Gitmoji("⬇️", ":arrow_down:", "Downgrade dependencies"),
        Gitmoji("⬆️", ":arrow_up:", "Upgrade dependencies"),
        Gitmoji("📌", ":pushpin:", "Pin dependencies to specific versions"),
        Gitmoji("➕", ":heavy_plus_sign:", "Add a dependency"),
        Gitmoji("➖", ":heavy_minus_sign:", "Remove a dependency"),
        Gitmoji("🔧", ":wrench:", "Add or update configuration files"),
        Gitmoji("🔨", ":hammer:", "Add or update development scripts"),
        Gitmoji("🌐", ":globe_with_meridians:", "Internationalization and localization"),
        Gitmoji("✏️", ":pencil2:", "Fix typos"),
        Gitmoji("💩", ":poop:", "Write bad code that needs to be improved"),
        Gitmoji("⏪️", ":rewind:", "Revert changes"),
        Gitmoji("🔀", ":twisted_rightwards_arrows:", "Merge branches"),
        Gitmoji("📦️", ":package:", "Add or update compiled files or packages"),
        Gitmoji("👽️", ":alien:", "Update code due to external API changes"),
        Gitmoji("🚚", ":truck:", "Move or rename resources"),
        Gitmoji("📄", ":page_facing_up:", "Add or update license"),
        Gitmoji("💥", ":boom:", "Introduce breaking changes"),
        Gitmoji("🍱", ":bento:", "Add or update assets"),
        Gitmoji("♿️", ":wheelchair:", "Improve accessibility"),
        Gitmoji("💡", ":bulb:", "Add or update comments in source code"),
        Gitmoji("💬", ":speech_balloon:", "Add or update text and literals"),
        Gitmoji("🗃️", ":card_file_box:", "Perform database related changes"),
        Gitmoji("🔊", ":loud_sound:", "Add or update logs"),
        Gitmoji("🔇", ":mute:", "Remove logs"),
        Gitmoji("👥", ":busts_in_silhouette:", "Add or update contributors"),
        Gitmoji("🚸", ":children_crossing:", "Improve user experience / usability"),
        Gitmoji("🏗️", ":building_construction:", "Make architectural changes"),
        Gitmoji("📱", ":iphone:", "Work on responsive design"),
        Gitmoji("🤡", ":clown_face:", "Mock things"),
        Gitmoji("🙈", ":see_no_evil:", "Add or update a .gitignore file"),
        Gitmoji("📸", ":camera_flash:", "Add or update snapshots"),
        Gitmoji("⚗️", ":alembic:", "Perform experiments"),
        Gitmoji("🔍️", ":mag:", "Improve SEO"),
        Gitmoji("🏷️", ":label:", "Add or update types"),
        Gitmoji("🌱", ":seedling:", "Add or update seed files"),
        Gitmoji("🚩", ":triangular_flag_on_post:", "Add, update, or remove feature flags"),
        Gitmoji("🥅", ":goal_net:", "Catch errors"),
        Gitmoji("💫", ":dizzy:", "Add or update animations and transitions"),
        Gitmoji("🗑️", ":wastebasket:", "Deprecate code that needs to be cleaned up"),
        Gitmoji("🛂", ":passport_control:", "Work on authorization, roles and permissions"),
        Gitmoji("🩹", ":adhesive_bandage:", "Simple fix for a non-critical issue"),
        Gitmoji("🧐", ":monocle_face:", "Data exploration / inspection"),
        Gitmoji("⚰️", ":coffin:", "Remove dead code"),
        Gitmoji("🧪", ":test_tube:", "Add a failing test"),
        Gitmoji("👔", ":necktie:", "Add or update business logic"),
        Gitmoji("🩺", ":stethoscope:", "Add or update healthcheck"),
        Gitmoji("🧱", ":bricks:", "Infrastructure related changes"),
        Gitmoji("🧑‍💻", ":technologist:", "Improve developer experience"),
        Gitmoji("💸", ":money_with_wings:", "Add sponsorships or money related infrastructure"),
        Gitmoji("🧵", ":thread:", "Add or update code related to multithreading or concurrency"),
        Gitmoji("🦺", ":safety_vest:", "Add or update code related to validation"),
        Gitmoji("✈️", ":airplane:", "Improve offline support"),
    )

    /** Puts [gitmoji] at the start of [message], replacing a gitmoji (emoji or code) already there. */
    fun apply(message: String, gitmoji: Gitmoji, asCode: Boolean): String {
        val prefix = if (asCode) gitmoji.code else gitmoji.emoji
        return prefix + " " + strip(message)
    }

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
            .setNamerForFiltering { "${it.code} ${it.description}" }
            .setRenderer(object : ColoredListCellRenderer<Gitmoji>() {
                override fun customizeCellRenderer(list: JList<out Gitmoji>, value: Gitmoji?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    value ?: return
                    append(value.emoji + "  ")
                    append(value.description)
                    append("  ${value.code}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { chosen ->
                WriteCommandAction.runWriteCommandAction(project, "Insert Gitmoji", null, {
                    document.setText(Gitmojis.apply(document.text, chosen, asCode))
                })
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}
