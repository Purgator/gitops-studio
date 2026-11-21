package io.github.purgator.gitops.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import io.github.purgator.gitops.core.RepoContext
import io.github.purgator.gitops.core.RepoTarget
import io.github.purgator.gitops.core.provider.HostingClient
import io.github.purgator.gitops.core.util.Notify
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Base of the tool window tabs: knows the current repository and how to talk to it off the UI thread. */
abstract class TargetPanel(protected val project: Project) : JPanel(BorderLayout()), Disposable {
    @Volatile
    protected var disposed = false

    protected val target: RepoTarget? get() = RepoContext.get(project).current()

    /** Reloads from the server. [silent] reloads keep the selection and show no error popups. */
    abstract fun reload(silent: Boolean = false)

    override fun dispose() {
        disposed = true
    }

    /** Runs [work] against the current repository in the background, then [onSuccess] on the UI thread. */
    protected fun <T> remote(
        work: (RepoTarget, HostingClient) -> T,
        onError: (Throwable) -> Unit = { Notify.error(project, "GitOps Studio", it.userMessage()) },
        onSuccess: (T) -> Unit,
    ) {
        val current = target ?: return
        runAsync(this, { work(current, current.client()) }, onError, { !disposed && !project.isDisposed }, onSuccess)
    }

    /** Runs a mutation, reports the outcome and refreshes every view. */
    protected fun mutate(done: String, work: (RepoTarget, HostingClient) -> Unit) {
        remote(work, onError = { Notify.error(project, "Operation failed", it.userMessage()) }) {
            Notify.info(project, done)
            io.github.purgator.gitops.ui.GitOps.refresh(project)
        }
    }

    protected fun toolbar(place: String, group: ActionGroup, horizontal: Boolean = true, owner: JComponent = this): ActionToolbar =
        ActionManager.getInstance().createActionToolbar(place, group, horizontal).also { it.targetComponent = owner }
}
