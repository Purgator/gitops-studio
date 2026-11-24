package io.github.purgator.gitops

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.git.AheadBehindWidget
import io.github.purgator.gitops.git.GitmojiCompletionContributor
import io.github.purgator.gitops.git.InlineBlamePainter
import io.github.purgator.gitops.ui.settings.AzureDevOpsConfigurable
import io.github.purgator.gitops.ui.settings.BitbucketConfigurable
import io.github.purgator.gitops.ui.settings.GitHubConfigurable
import io.github.purgator.gitops.ui.settings.GitLabConfigurable
import io.github.purgator.gitops.ui.settings.GitModuleConfigurable
import io.github.purgator.gitops.ui.settings.GitOpsConfigurable
import io.github.purgator.gitops.ui.settings.GiteaConfigurable
import io.github.purgator.gitops.ui.statusbar.PullRequestWidget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Boots the IDE headless with the plugin installed and checks that everything `plugin.xml` declares really loads. */
@TestApplication
class PluginWiringTest {
    @Test
    fun `every declared module loads and exposes its provider`() {
        val registry = ModuleRegistry.get()
        assertEquals(listOf("git", "github", "azure", "gitlab", "bitbucket", "gitea"), registry.beans.map { it.id })
        for (bean in registry.beans) {
            assertNotNull(bean.instance, bean.id)
            assertNotNull(bean.loadIcon(), "icon of ${bean.id}")
            assertTrue(bean.description.isNotBlank())
            if (bean.isCloud) assertEquals(bean.id, registry.providerOf(bean.id)?.id) else assertEquals(null, bean.instance.provider)
        }
    }

    @Test
    fun `only enabled modules contribute providers`() {
        val registry = ModuleRegistry.get()
        val before = GitOpsSettings.get().values.enabledModules.toSet()
        try {
            registry.setEnabled(setOf("git"))
            assertTrue(registry.providers().isEmpty())
            registry.setEnabled(setOf("git", "azure", "github"))
            assertEquals(listOf("github", "azure"), registry.providers().map { it.id })
            registry.setEnabled("github", false)
            assertEquals(listOf("azure"), registry.providers().map { it.id })
        } finally {
            registry.setEnabled(before)
        }
    }

    @Test
    fun `actions, widget and notification group are registered`() {
        val actions = ActionManager.getInstance()
        val ids = listOf(
            "CreatePullRequest", "OpenBranchPullRequest", "ShowToolWindow", "Refresh", "Clone", "Sync", "CleanUpBranches",
            "SetupWizard", "Settings", "OpenFile", "CopyFileLink", "Gitmoji", "ToggleInlineBlame", "ShowLineCommit",
            "MainGroup", "GitMenuGroup", "FileGroup",
        )
        for (id in ids) assertNotNull(actions.getAction("GitOpsStudio.$id"), id)
        // The context menus get one "GitOps Studio" submenu, not loose entries.
        val menu = actions.getAction("GitOpsStudio.FileGroup") as ActionGroup
        assertTrue(menu.isPopup)
        assertEquals("GitOps Studio", menu.templatePresentation.text)
        assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == PullRequestWidget.ID })
        assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == AheadBehindWidget.ID })
        assertTrue(EditorLinePainter.EP_NAME.extensionList.any { it is InlineBlamePainter })
        assertTrue(CompletionContributor.forLanguage(PlainTextLanguage.INSTANCE).any { it is GitmojiCompletionContributor })
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup("GitOps Studio"))
    }

    @Test
    fun `settings pages build, reset and report no pending change`() {
        ApplicationManager.getApplication().invokeAndWait {
            val pages = listOf(
                GitOpsConfigurable(), GitModuleConfigurable(), GitHubConfigurable(), AzureDevOpsConfigurable(),
                GitLabConfigurable(), BitbucketConfigurable(), GiteaConfigurable(),
            )
            for (page in pages) {
                assertNotNull(page.createComponent(), page.displayName)
                page.reset()
                assertFalse(page.isModified, page.displayName)
                page.disposeUIResources()
            }
        }
    }

    @Test
    fun `settings and accounts survive serialisation`() {
        val state = GitOpsSettings.State().apply {
            enabledModules = mutableListOf("git", "azure")
            autoRefreshMinutes = 9
        }
        val settings = GitOpsSettings().apply { loadState(state) }
        settings.provider("azure").defaultReviewers = "Ann, Bob"
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), GitOpsSettings.State::class.java)
        assertEquals(listOf("git", "azure"), restored.enabledModules)
        assertEquals(9, restored.autoRefreshMinutes)
        assertEquals("Ann, Bob", restored.providers.single { it.providerId == "azure" }.defaultReviewers)

        val accounts = AccountStore.State().apply {
            accounts += Account().apply { providerId = "azure"; serverUrl = "https://dev.azure.com/acme"; auth = "CLI" }
        }
        val back = XmlSerializer.deserialize(XmlSerializer.serialize(accounts), AccountStore.State::class.java)
        assertEquals("https://dev.azure.com/acme", back.accounts.single().serverUrl)
        assertEquals("CLI", back.accounts.single().auth)
    }
}
