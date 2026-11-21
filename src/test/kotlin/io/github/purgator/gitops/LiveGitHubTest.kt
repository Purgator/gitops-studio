package io.github.purgator.gitops

import com.intellij.testFramework.junit5.TestApplication
import io.github.purgator.gitops.core.accounts.AuthKind
import io.github.purgator.gitops.core.accounts.CredentialService
import io.github.purgator.gitops.core.http.JdkHttpTransport
import io.github.purgator.gitops.core.model.IssueFilter
import io.github.purgator.gitops.core.model.PrFilter
import io.github.purgator.gitops.core.model.RepoRef
import io.github.purgator.gitops.core.modules.ModuleRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Read-only round trip against the real GitHub API through the production transport and the GitHub CLI
 * login. Opt-in: set `GITOPS_LIVE=owner/repo` (a repository with pull requests and Actions) to run it.
 */
@TestApplication
@EnabledIfEnvironmentVariable(named = "GITOPS_LIVE", matches = ".+/.+")
class LiveGitHubTest {
    @Test
    fun `reads pull requests, runs, jobs and issues`() {
        val (owner, name) = System.getenv("GITOPS_LIVE").split('/', limit = 2)
        val provider = ModuleRegistry.get().providerOf("github")!!
        val secrets = CredentialService.get().draftSource(provider, "https://github.com", AuthKind.CLI, "", null)
        val client = provider.createClient("https://github.com", JdkHttpTransport, secrets)
        val repo = RepoRef("github", "https://github.com", owner, name)

        println("user: " + client.currentUser().login.isNotBlank())
        val info = client.repoInfo(repo)
        println("default branch: ${info.defaultBranch}, strategies: ${info.mergeStrategies}")
        assertTrue(info.defaultBranch.isNotBlank())

        val merged = client.listPullRequests(repo, PrFilter.MERGED)
        val open = client.listPullRequests(repo, PrFilter.OPEN)
        println("pull requests: ${open.size} open, ${merged.size} merged")
        (open + merged).firstOrNull()?.let { first ->
            val full = client.getPullRequest(repo, first.id)
            println("#${full.id} ${full.state} ${full.sourceBranch} -> ${full.targetBranch}, reviewers=${full.reviewers.size}, merge=${full.mergeStatus}")
            println("checks: " + client.listChecks(repo, full).map { it.state }.groupingBy { it }.eachCount())
            println("threads: " + client.listComments(repo, full).size)
        }

        val runs = client.listPipelineRuns(repo, null)
        println("runs: " + runs.take(5).map { "${it.name}=${it.state}" })
        runs.firstOrNull()?.let { run ->
            val jobs = client.listJobs(repo, run)
            println("jobs: " + jobs.map { "${it.name}=${it.state}" })
            jobs.firstOrNull { it.state.finished }?.let { println("log chars: " + client.jobLog(repo, run, it).length) }
        }
        println("workflows: " + client.listPipelineDefinitions(repo).map { it.name })
        println("issues: " + client.listIssues(repo, IssueFilter.OPEN, "").size)
        println("labels: " + client.listLabels(repo).size)
        println("repositories: " + (client.listRepositories("").size > 0))
    }
}
