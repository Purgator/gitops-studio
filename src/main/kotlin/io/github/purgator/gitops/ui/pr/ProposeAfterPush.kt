package io.github.purgator.gitops.ui.pr

import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.settings.GitOpsSettings
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.git.BranchRules
import java.util.concurrent.ConcurrentHashMap

/**
 * After a push from the IDE, offers to create a pull request when the branch has none yet.
 * Once per branch and per session, and never for protected branches or the default one.
 */
class ProposeAfterPush(private val project: Project) : GitPushListener {
    private val proposed = ConcurrentHashMap.newKeySet<String>()

    override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
        val settings = GitOpsSettings.get().values
        if (!settings.proposePullRequestAfterPush || project.isDisposed) return
        if (pushResult.type !in PUSHED) return
        val branch = pushResult.sourceBranch.removePrefix("refs/heads/")
        if (branch.isBlank() || branch.startsWith("refs/") || BranchRules.isProtected(branch)) return
        val target = RepoContext.get(project).targets().firstOrNull { it.git.root == repository.root }?.takeIf { it.account != null } ?: return
        val key = target.ref.fullName + "@" + branch
        if (key in proposed) return
        val terms = target.provider.terms
        runAsync(null, {
            val client = target.client()
            val isDefault = runCatching { client.repoInfo(target.ref).defaultBranch == branch }.getOrDefault(false)
            !isDefault && client.findOpenPullRequest(target.ref, branch) == null
        }, isAlive = { !project.isDisposed }) { missing ->
            if (!missing || !proposed.add(key)) return@runAsync
            Notify.info(
                project,
                "$branch was pushed",
                "It has no ${terms.pullRequest.lowercase()} yet.",
                "Create ${terms.pullRequest}…" to { CreatePullRequestPanel.open(project, target) },
                "Don't Ask Again" to { settings.proposePullRequestAfterPush = false },
            )
        }
    }

    private companion object {
        val PUSHED = setOf(GitPushRepoResult.Type.SUCCESS, GitPushRepoResult.Type.NEW_BRANCH, GitPushRepoResult.Type.FORCED)
    }
}
