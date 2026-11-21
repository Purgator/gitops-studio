package io.github.purgator.gitops.ui.toolwindow

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.modules.ModuleRegistry
import io.github.purgator.gitops.ui.GitOpsIcons
import io.github.purgator.gitops.ui.actions.CloneFromCloudDialog
import io.github.purgator.gitops.ui.settings.GitOpsConfigurable
import io.github.purgator.gitops.ui.setup.SetupWizard
import java.awt.BorderLayout
import javax.swing.JPanel

/** Shown instead of empty lists while nothing is connected: says exactly what is missing and offers the fix. */
class WelcomePanel(private val project: Project) : JPanel(BorderLayout()) {
    init {
        val context = RepoContext.get(project)
        val registry = ModuleRegistry.get()
        val pending = context.targets().filter { it.account == null }
        val unknown = context.unknownRemotes()

        val content = panel {
            row {
                icon(GitOpsIcons.Logo)
                label("GitOps Studio").bold()
            }
            row { comment("Pull requests, pipelines and work items of your cloud repositories, without leaving the IDE.") }
            separator()
            when {
                !context.hasGitRepository() -> {
                    row { label("This project is not a git repository yet.") }
                    row {
                        button("Clone from Cloud…") { CloneFromCloudDialog.open(project) }
                        button("Set Up…") { SetupWizard.show(project) }
                    }
                }
                pending.isNotEmpty() -> {
                    for (repo in pending) {
                        row {
                            icon(GitOpsIcons.provider(repo.provider.id))
                            label("${repo.ref.fullName} is hosted on ${repo.provider.displayName}, but no account is connected.")
                        }
                    }
                    row { button("Connect an Account…") { SetupWizard.show(project) } }
                }
                unknown.isNotEmpty() -> {
                    for (remote in unknown) {
                        val bean = remote.suggestedModule?.let { registry.bean(it) }
                        row {
                            if (bean != null) {
                                label("${remote.host} looks like ${bean.name}. Enable the ${bean.name} module to work with it.")
                            } else {
                                label("${remote.host} is not recognised. If it is a self-hosted server, connect an account with its URL.")
                            }
                        }
                    }
                    row { button("Set Up…") { SetupWizard.show(project) } }
                }
                else -> {
                    row { label("No cloud module is enabled yet.") }
                    row { button("Set Up…") { SetupWizard.show(project) } }
                }
            }
            row {
                link("Open settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, GitOpsConfigurable::class.java) }
            }
        }
        content.border = JBUI.Borders.empty(16, 20)
        add(ScrollPaneFactory.createScrollPane(content, true), BorderLayout.CENTER)
    }
}
