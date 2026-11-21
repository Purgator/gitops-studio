package io.github.purgator.gitops.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import javax.swing.Icon

/** Project-level events that keep the views in sync without them knowing each other. */
interface GitOpsListener {
    /** Remote data changed (a pull request was created, merged, …): reload what you show. */
    fun refreshRequested() {}

    /** Bring a pull request into view in the tool window. */
    fun showPullRequest(id: String) {}

    companion object {
        @JvmField
        val TOPIC: Topic<GitOpsListener> = Topic.create("GitOps Studio events", GitOpsListener::class.java)
    }
}

object GitOps {
    const val TOOL_WINDOW_ID = "GitOps Studio"

    fun refresh(project: Project) {
        if (!project.isDisposed) project.messageBus.syncPublisher(GitOpsListener.TOPIC).refreshRequested()
    }

    fun showPullRequest(project: Project, id: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            toolWindow.contentManager.contents.firstOrNull()?.let { toolWindow.contentManager.setSelectedContent(it) }
            project.messageBus.syncPublisher(GitOpsListener.TOPIC).showPullRequest(id)
        }
    }

    fun showToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }
}

/** Compact way to declare the many small toolbar actions of the views. */
fun simpleAction(
    text: String,
    icon: Icon? = null,
    description: String? = null,
    enabled: () -> Boolean = { true },
    visible: () -> Boolean = { true },
    showText: Boolean = false,
    perform: (AnActionEvent) -> Unit,
): DumbAwareAction = object : DumbAwareAction(text, description ?: text, icon) {
    override fun actionPerformed(e: AnActionEvent) = perform(e)

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = visible()
        if (showText) e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        e.presentation.isEnabled = e.presentation.isVisible && enabled()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

