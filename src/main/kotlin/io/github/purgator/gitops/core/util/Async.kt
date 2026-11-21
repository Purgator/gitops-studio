package io.github.purgator.gitops.core.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.purgator.gitops.core.provider.UnsupportedOperation
import java.awt.Component

private val LOG = Logger.getInstance("GitOpsStudio")

/** Human readable message for anything a remote call can throw. */
fun Throwable.userMessage(): String = when (this) {
    is UnsupportedOperation -> "This hosting service does not support this operation."
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

/**
 * Runs [work] on a pooled thread and delivers the outcome on the UI thread, honouring the modality of
 * [owner] so it also works from inside dialogs. Nothing is delivered once [isAlive] turns false.
 */
fun <T> runAsync(
    owner: Component?,
    work: () -> T,
    onError: (Throwable) -> Unit = {},
    isAlive: () -> Boolean = { true },
    onSuccess: (T) -> Unit,
) {
    val app = ApplicationManager.getApplication()
    val modality = if (owner != null) ModalityState.stateForComponent(owner) else ModalityState.defaultModalityState()
    app.executeOnPooledThread {
        val result = try {
            Result.success(work())
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            LOG.info("Background operation failed: ${e.message}")
            LOG.debug(e)
            Result.failure(e)
        }
        app.invokeLater({
            if (!isAlive()) return@invokeLater
            result.fold(onSuccess, onError)
        }, modality)
    }
}

/** Runs [work] under a background progress indicator in the status bar. */
fun <T> Project.runWithProgress(title: String, work: (ProgressIndicator) -> T, onError: (Throwable) -> Unit = { notifyError(title, it) }, onSuccess: (T) -> Unit = {}) {
    val project = this
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
        private var result: Result<T>? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            result = try {
                Result.success(work(indicator))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                LOG.info("$title failed: ${e.message}")
                LOG.debug(e)
                Result.failure(e)
            }
        }

        override fun onFinished() {
            if (project.isDisposed) return
            result?.fold(onSuccess, onError)
        }
    })
}

object Notify {
    private const val GROUP = "GitOps Studio"

    fun info(project: Project?, title: String, content: String = "", vararg actions: Pair<String, () -> Unit>) =
        show(project, title, content, NotificationType.INFORMATION, actions)

    fun warn(project: Project?, title: String, content: String = "", vararg actions: Pair<String, () -> Unit>) =
        show(project, title, content, NotificationType.WARNING, actions)

    fun error(project: Project?, title: String, content: String = "", vararg actions: Pair<String, () -> Unit>) =
        show(project, title, content, NotificationType.ERROR, actions)

    private fun show(project: Project?, title: String, content: String, type: NotificationType, actions: Array<out Pair<String, () -> Unit>>) {
        val notification: Notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content, type)
        for ((text, handler) in actions) {
            notification.addAction(NotificationAction.createSimpleExpiring(text) { handler() })
        }
        notification.notify(project)
    }
}

fun Project.notifyError(title: String, error: Throwable) = Notify.error(this, title, error.userMessage())
