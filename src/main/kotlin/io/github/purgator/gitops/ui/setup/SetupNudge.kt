package io.github.purgator.gitops.ui.setup

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import git4idea.repo.GitRepositoryManager
import io.github.purgator.gitops.core.ModuleGuess
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.core.util.Notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One gentle invitation to the setup wizard, the first time a project with a recognisable remote is
 * opened. It never comes back once the wizard was opened or the user declined.
 */
class SetupNudge : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = GitOpsSettings.get().values
        if (settings.setupCompleted) return
        // Git repositories are discovered a little after the project opens.
        delay(8_000)
        if (project.isDisposed || settings.setupCompleted) return
        val registry = ModuleRegistry.get()
        val detected = GitRepositoryManager.getInstance(project).repositories
            .flatMap { it.remotes }.mapNotNull { it.firstUrl }.mapNotNull(GitUrl::parse)
            .mapNotNull(ModuleGuess::forRemote).distinct()
            .mapNotNull { registry.bean(it) }
        if (detected.isEmpty()) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || settings.setupCompleted) return@withContext
            Notify.info(
                project,
                "GitOps Studio",
                "This project is hosted on ${detected.joinToString(" and ") { it.name }}. Connect it to create pull requests and follow pipelines from the IDE.",
                "Set Up…" to { SetupWizard.show(project) },
                "Not Now" to { settings.setupCompleted = true },
            )
        }
    }
}
