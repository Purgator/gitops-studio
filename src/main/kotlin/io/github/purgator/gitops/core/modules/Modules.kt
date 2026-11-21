package io.github.purgator.gitops.core.modules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.RequiredElement
import com.intellij.openapi.util.IconLoader
import com.intellij.serviceContainer.BaseKeyedLazyInstance
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.settings.GitOpsSettings
import javax.swing.Icon

/**
 * A feature set the user can switch on or off. Cloud modules expose a [HostingProvider];
 * the Git module only contributes local actions and exposes none.
 */
interface GitOpsModule {
    val provider: HostingProvider? get() = null
}

/**
 * Declaration of a module in `plugin.xml`. Name, description and icon are plain attributes so that
 * settings and the setup wizard can list a module without loading its implementation class.
 */
class ModuleBean : BaseKeyedLazyInstance<GitOpsModule>() {
    @Attribute("id")
    @RequiredElement
    @JvmField
    var id: String = ""

    @Attribute("name")
    @RequiredElement
    @JvmField
    var name: String = ""

    @Attribute("description")
    @JvmField
    var description: String = ""

    @Attribute("icon")
    @JvmField
    var icon: String = ""

    /** `cloud` for hosting services, `local` otherwise. */
    @Attribute("kind")
    @JvmField
    var kind: String = "cloud"

    @Attribute("implementationClass")
    @RequiredElement
    @JvmField
    var implementationClass: String = ""

    override fun getImplementationClassName(): String = implementationClass

    val isCloud: Boolean get() = kind == "cloud"

    fun loadIcon(): Icon? =
        if (icon.isBlank()) null else IconLoader.findIcon(icon, ModuleBean::class.java.classLoader)
}

fun interface ModuleListener {
    fun modulesChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<ModuleListener> = Topic.create("GitOps Studio modules", ModuleListener::class.java)
    }
}

/** Single source of truth for which modules are on. Only enabled modules are ever instantiated. */
@Service(Service.Level.APP)
class ModuleRegistry {
    private val settings get() = GitOpsSettings.get().values

    val beans: List<ModuleBean> get() = EP.extensionList

    fun bean(id: String): ModuleBean? = beans.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = id in settings.enabledModules

    fun setEnabled(id: String, enabled: Boolean) {
        if (isEnabled(id) == enabled) return
        if (enabled) settings.enabledModules.add(id) else settings.enabledModules.remove(id)
        fireChanged()
    }

    fun setEnabled(ids: Set<String>) {
        val next = beans.map { it.id }.filter { it in ids }
        if (next.toSet() == settings.enabledModules.toSet()) return
        settings.enabledModules = next.toMutableList()
        fireChanged()
    }

    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(ModuleListener.TOPIC).modulesChanged()
    }

    /** Providers of the enabled cloud modules, in declaration order. */
    fun providers(): List<HostingProvider> =
        beans.filter { isEnabled(it.id) }.mapNotNull { runCatching { it.instance.provider }.getOrNull() }

    fun provider(id: String): HostingProvider? = providers().firstOrNull { it.id == id }

    /** Loads the provider of a module even when it is disabled (settings and setup need it to describe the module). */
    fun providerOf(moduleId: String): HostingProvider? = bean(moduleId)?.let { runCatching { it.instance.provider }.getOrNull() }

    companion object {
        val EP: ExtensionPointName<ModuleBean> = ExtensionPointName.create("io.github.purgator.gitops.module")

        const val GIT = "git"

        fun get(): ModuleRegistry = service()
    }
}
