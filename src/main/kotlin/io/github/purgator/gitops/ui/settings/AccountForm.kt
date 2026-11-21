package io.github.purgator.gitops.ui.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.purgator.gitops.core.accounts.Account
import io.github.purgator.gitops.core.accounts.AccountStore
import io.github.purgator.gitops.core.accounts.AuthKind
import io.github.purgator.gitops.core.accounts.CredentialService
import io.github.purgator.gitops.core.http.JdkHttpTransport
import io.github.purgator.gitops.core.model.User
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.util.Cli
import io.github.purgator.gitops.core.util.GitUrl
import io.github.purgator.gitops.core.util.runAsync
import io.github.purgator.gitops.core.util.userMessage
import io.github.purgator.gitops.ui.Html
import java.awt.Dimension
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The account form shared by the setup wizard and the settings. It offers every way to sign in the
 * provider supports, explains which token scopes are needed and can verify the result on the spot.
 */
class AccountForm(private val provider: HostingProvider, private val existing: Account?, suggestedServer: String?) {
    private val server = JBTextField(existing?.serverUrl ?: suggestedServer ?: provider.defaultServerUrl)
    private val name = JBTextField(existing?.name.orEmpty())
    private val username = JBTextField(existing?.username.orEmpty())
    private val token = JBPasswordField()
    private val useToken = JBRadioButton("Personal access token")
    private val useCli = JBRadioButton(provider.cliLogin?.let { "${it.displayName} (${it.tool})" } ?: "Command line login")
    private val useGit = JBRadioButton("Git credential manager (reuse what git already uses)")
    private val result = JBLabel(" ")
    private val testButton = JButton("Test Connection")
    private val cliAvailable = provider.cliLogin?.let { Cli.find(it.tool) != null } ?: false
    private val showServer = provider.supportsCustomServer || provider.requiresOrganization

    val component: DialogPanel

    init {
        ButtonGroup().apply { add(useToken); add(useCli); add(useGit) }
        when (existing?.authKind) {
            AuthKind.CLI -> useCli.isSelected = true
            AuthKind.GIT_CREDENTIAL -> useGit.isSelected = true
            AuthKind.TOKEN -> useToken.isSelected = true
            // New account: the vendor CLI is the least effort when it is installed.
            null -> if (cliAvailable) useCli.isSelected = true else useToken.isSelected = true
        }
        name.emptyText.text = "Optional, e.g. Work"
        token.emptyText.text = if (existing?.authKind == AuthKind.TOKEN) "Unchanged" else "Paste the token here"
        listOf(useToken, useCli, useGit).forEach { it.addActionListener { syncAuth() } }
        testButton.addActionListener { test { } }

        component = panel {
            if (showServer) {
                row(if (provider.requiresOrganization) "Organization URL:" else "Server URL:") {
                    cell(server).align(AlignX.FILL)
                        .comment(if (provider.requiresOrganization) "For example https://dev.azure.com/your-organization" else "Keep the default unless you use a self-hosted server")
                }
            }
            buttonsGroup("Sign in with:") {
                if (provider.cliLogin != null) {
                    row {
                        cell(useCli).comment(
                            if (cliAvailable) "Detected on this machine. ${provider.cliLogin!!.loginHint}" else "Not installed (${provider.cliLogin!!.tool} is not on PATH).",
                        )
                    }
                }
                row { cell(useToken) }
                indent {
                    if (provider.tokenNeedsUsername) row("User name:") { cell(username).align(AlignX.FILL) }
                    row("Token:") {
                        cell(token).align(AlignX.FILL).resizableColumn()
                        link("Create a token…") { BrowserUtil.browse(provider.tokenCreationUrl(serverUrl())) }
                    }
                    row { comment(provider.tokenScopesHint) }
                }
                row { cell(useGit).comment("Works when you already push and pull over HTTPS with a credential helper.") }
            }
            row("Account name:") { cell(name).align(AlignX.FILL) }
            row {
                cell(testButton)
                cell(result)
            }
        }
        component.preferredSize = Dimension(JBUI.scale(560), component.preferredSize.height)
        syncAuth()
    }

    private fun syncAuth() {
        useCli.isEnabled = cliAvailable || useCli.isSelected
        token.isEnabled = useToken.isSelected
        username.isEnabled = useToken.isSelected
    }

    private fun kind(): AuthKind = when {
        useCli.isSelected -> AuthKind.CLI
        useGit.isSelected -> AuthKind.GIT_CREDENTIAL
        else -> AuthKind.TOKEN
    }

    private fun serverUrl(): String = GitUrl.normalizeServerUrl(if (showServer) server.text else provider.defaultServerUrl)

    private fun typedToken(): String? = String(token.password).trim().ifEmpty { null }

    val preferredFocus: JComponent get() = if (showServer && server.text.trimEnd('/') == provider.defaultServerUrl.trimEnd('/') && provider.requiresOrganization) server else token

    fun validate(): ValidationInfo? {
        val url = serverUrl()
        return when {
            showServer && GitUrl.hostOf(url).isEmpty() -> ValidationInfo("Enter the server URL", server)
            provider.requiresOrganization && url.substringAfter("://").substringAfter('/', "").isBlank() && GitUrl.hostOf(url) == "dev.azure.com" ->
                ValidationInfo("Add your organization, e.g. https://dev.azure.com/your-organization", server)
            kind() == AuthKind.TOKEN && typedToken() == null && existing?.authKind != AuthKind.TOKEN -> ValidationInfo("Paste a token", token)
            else -> null
        }
    }

    fun account(): Account = (existing?.copy() ?: Account()).also {
        it.providerId = provider.id
        it.serverUrl = serverUrl()
        it.username = username.text.trim()
        it.auth = kind().name
        it.name = name.text.trim().ifEmpty { it.serverUrl.substringAfter("://").trimEnd('/') }
    }

    /** Null means "keep the stored token". */
    fun token(): String? = typedToken()

    fun save() {
        AccountStore.get().save(account(), if (kind() == AuthKind.TOKEN) token() else null)
    }

    /** Signs in with what is typed (nothing is saved) and reports who we are. */
    fun test(onDone: (Result<User>) -> Unit) {
        validate()?.let {
            show(Html.colored(Html.esc(it.message), Html.red))
            onDone(Result.failure(IllegalStateException(it.message)))
            return
        }
        val draft = account()
        val kind = kind()
        val typed = typedToken()
        testButton.isEnabled = false
        show(Html.muted("Connecting…"))
        runAsync(component, {
            // An untouched token field means "keep the stored one"; the password safe is read off the UI thread.
            val effective = typed ?: existing?.takeIf { kind == AuthKind.TOKEN }?.let { CredentialService.get().storedToken(it) }
            val secrets = CredentialService.get().draftSource(provider, draft.serverUrl, kind, draft.username, effective)
            provider.createClient(draft.serverUrl, JdkHttpTransport, secrets).currentUser()
        }, onError = { error ->
            testButton.isEnabled = true
            show(Html.colored("✖ " + Html.esc(error.userMessage()), Html.red))
            onDone(Result.failure(error))
        }) { user ->
            testButton.isEnabled = true
            show(Html.colored("✔ Signed in as " + Html.esc(user.label), Html.green))
            onDone(Result.success(user))
        }
    }

    private fun show(html: String) {
        result.text = "<html>$html</html>"
    }
}

/** Add / edit one account from the settings. OK verifies the sign-in before saving. */
class AccountDialog(project: Project?, private val provider: HostingProvider, existing: Account?, suggestedServer: String? = null) :
    DialogWrapper(project) {
    private val form = AccountForm(provider, existing, suggestedServer)
    private var failedOnce = false

    init {
        title = if (existing == null) "Connect ${provider.displayName}" else "Edit ${provider.displayName} Account"
        init()
    }

    override fun createCenterPanel(): JComponent = form.component

    override fun getPreferredFocusedComponent(): JComponent = form.preferredFocus

    override fun doValidate(): ValidationInfo? = form.validate()

    override fun doOKAction() {
        if (failedOnce) {
            form.save()
            super.doOKAction()
            return
        }
        isOKActionEnabled = false
        form.test { outcome ->
            isOKActionEnabled = true
            if (outcome.isSuccess) {
                form.save()
                super.doOKAction()
            } else {
                failedOnce = true
                setErrorText("The connection test failed. Fix the form, or press OK again to save anyway.")
            }
        }
    }
}
