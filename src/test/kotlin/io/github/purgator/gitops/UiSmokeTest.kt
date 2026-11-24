package io.github.purgator.gitops

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import git4idea.branch.GitBranchesCollection
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.model.PrState
import io.github.purgator.gitops.core.model.PullRequest
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.ui.actions.CloneFromCloudDialog
import io.github.purgator.gitops.ui.issues.IssuesPanel
import io.github.purgator.gitops.ui.issues.NewIssueDialog
import io.github.purgator.gitops.ui.pipelines.PipelinesPanel
import io.github.purgator.gitops.ui.pipelines.RunPipelineDialog
import io.github.purgator.gitops.ui.pr.CreatePullRequestPanel
import io.github.purgator.gitops.ui.pr.EditPullRequestDialog
import io.github.purgator.gitops.ui.pr.MergeDialog
import io.github.purgator.gitops.ui.pr.PullRequestDetailsPanel
import io.github.purgator.gitops.ui.pr.PullRequestsPanel
import io.github.purgator.gitops.ui.pr.ReviewerChooserDialog
import io.github.purgator.gitops.ui.settings.AccountDialog
import io.github.purgator.gitops.ui.setup.SetupWizard
import io.github.purgator.gitops.ui.toolwindow.WelcomePanel
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * Builds every dialog and panel for every provider in a headless IDE. Layout mistakes (a bad UI DSL
 * call, a component added twice, a null in a renderer) only blow up when the component is created,
 * so creating them all is a cheap and effective regression net.
 */
@TestApplication
class UiSmokeTest {
    // The default project is enough to parent dialogs, and unlike a real one it opens in every IDE
    // (Rider, for instance, refuses a project without a solution).
    private val project: Project get() = ProjectManager.getInstance().defaultProject

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block)

    /** A git repository on a feature branch with one remote, without touching the disk. */
    private fun fakeRepository(url: String): Pair<GitRepository, GitRemote> {
        val remote = GitRemote("origin", listOf(url), listOf(url), emptyList(), emptyList())
        val root = LightVirtualFile("repo")
        val repo = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GitRepository::class.java)) { proxy, method, args ->
            when (method.name) {
                "getRoot" -> root
                "getProject" -> project
                "getRemotes" -> listOf(remote)
                "getBranches" -> GitBranchesCollection(emptyMap(), emptyMap(), emptyList())
                "getCurrentBranchName" -> "feature/12-login"
                "toString" -> "FakeRepository"
                "hashCode" -> 42
                "equals" -> proxy === args?.firstOrNull()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as GitRepository
        return repo to remote
    }

    private val remotes = mapOf(
        "github" to "https://github.com/acme/web.git",
        "azure" to "https://dev.azure.com/acme/Proj/_git/web",
        "gitlab" to "https://gitlab.com/acme/team/web.git",
        "bitbucket" to "https://bitbucket.org/acme/web.git",
        "gitea" to "https://codeberg.org/acme/web.git",
    )

    private fun targets(): List<RepoTarget> = remotes.map { (id, url) ->
        val provider = ModuleRegistry.get().providerOf(id)!!
        val (repo, remote) = fakeRepository(url)
        val ref = provider.parseRemote(io.github.purgator.gitops.core.util.GitUrl.parse(url)!!, emptyList())
        assertNotNull(ref, "remote of $id")
        RepoTarget(repo, remote, url, ref!!, provider)
    }

    /** Headless dialogs skip their content, so the center panel is built explicitly: that is where layouts live. */
    private fun check(dialog: DialogWrapper) {
        try {
            val factory = generateSequence<Class<*>>(dialog.javaClass) { it.superclass }
                .firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == "createCenterPanel" && it.parameterCount == 0 } }
            factory.isAccessible = true
            val center = factory.invoke(dialog) as JComponent
            center.setSize(center.preferredSize)
            center.doLayout()
            assertTrue(center.preferredSize.width > 0, dialog.title)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun check(component: JComponent) {
        assertTrue(component.componentCount > 0)
        component.doLayout()
        if (component is com.intellij.openapi.Disposable) Disposer.dispose(component)
    }

    private val pullRequest = PullRequest(
        id = "7", title = "Add login", description = "**Body**", state = PrState.OPEN, author = User("u1", "Ann"),
        sourceBranch = "feature/12-login", targetBranch = "main", webUrl = "https://example.com/pr/7",
    )

    @Test
    fun `pull request dialogs build for every provider`() = onEdt {
        for (target in targets()) {
            check(CreatePullRequestPanel(project, target, onHide = {}, onDone = {}))
            check(MergeDialog(project, target, pullRequest))
            check(ReviewerChooserDialog(project, target, listOf(User("u2", "Bob"))))
            check(RunPipelineDialog(project, target))
            check(NewIssueDialog(project, target))
        }
        check(EditPullRequestDialog(project, pullRequest))
    }

    @Test
    fun `account form builds for every provider, new and existing`() = onEdt {
        for (id in remotes.keys) {
            val provider = ModuleRegistry.get().providerOf(id)!!
            check(AccountDialog(project, provider, null, null))
            val existing = Account().apply { providerId = id; serverUrl = provider.defaultServerUrl; auth = "GIT_CREDENTIAL" }
            check(AccountDialog(project, provider, existing, null))
        }
    }

    @Test
    fun `setup wizard and clone dialog build`() = onEdt {
        check(SetupWizard(project))
        check(SetupWizard(null))
        check(CloneFromCloudDialog(project, listOf(Account().apply { providerId = "github"; serverUrl = "https://github.com" })))
    }

    @Test
    fun `tool window panels build without a repository`() = onEdt {
        check(WelcomePanel(project))
        check(PullRequestsPanel(project))
        check(PipelinesPanel(project))
        check(IssuesPanel(project))
        val details = PullRequestDetailsPanel(project)
        details.show(pullRequest)
        details.show(null)
        check(details)
    }
}
