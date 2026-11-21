package io.github.purgator.gitops.core.accounts

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.purgator.gitops.core.provider.HostingProvider
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.provider.SecretSource
import io.github.purgator.gitops.core.util.Cli
import io.github.purgator.gitops.core.util.GitUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class CredentialException(message: String) : IOException(message)

/**
 * Turns an account into a usable credential. Tokens typed by the user live in the IDE password safe;
 * CLI and git-credential logins are asked to the tool on demand and cached in memory for a short while.
 */
@Service(Service.Level.APP)
class CredentialService {
    private class Cached(val secret: Secret, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun storeToken(account: Account, token: String) {
        PasswordSafe.instance.set(attributes(account.id), Credentials(account.username, token))
    }

    fun forgetToken(account: Account) {
        PasswordSafe.instance.set(attributes(account.id), null)
        cache.remove(account.id)
    }

    fun invalidate(accountId: String) {
        cache.remove(accountId)
    }

    fun source(account: Account, provider: HostingProvider): SecretSource = object : SecretSource {
        override fun get(): Secret {
            cache[account.id]?.let { cached ->
                if (account.authKind == AuthKind.TOKEN || System.currentTimeMillis() - cached.at < EXTERNAL_TTL_MS) return cached.secret
            }
            val secret = resolve(provider, account.serverUrl, account.authKind, account.username) {
                PasswordSafe.instance.getPassword(attributes(account.id))
            }
            cache[account.id] = Cached(secret, System.currentTimeMillis())
            return secret
        }

        // A stored token cannot heal itself; external logins may have been refreshed since.
        override fun invalidate(): Boolean {
            val hadCache = cache.remove(account.id) != null
            return hadCache && account.authKind != AuthKind.TOKEN
        }
    }

    /** One-shot source for credentials that are not saved yet ("Test connection" in the setup). */
    fun draftSource(provider: HostingProvider, serverUrl: String, kind: AuthKind, username: String, token: String?): SecretSource =
        object : SecretSource {
            private val secret by lazy { resolve(provider, serverUrl, kind, username) { token } }
            override fun get(): Secret = secret
            override fun invalidate(): Boolean = false
        }

    fun storedToken(account: Account): String? = PasswordSafe.instance.getPassword(attributes(account.id))

    @Throws(IOException::class)
    private fun resolve(provider: HostingProvider, serverUrl: String, kind: AuthKind, username: String, token: () -> String?): Secret =
        when (kind) {
            AuthKind.TOKEN -> {
                val value = token()?.trim()
                if (value.isNullOrEmpty()) throw CredentialException("No token is stored for this account. Edit the account and paste a token.")
                Secret(username, value)
            }
            AuthKind.CLI -> fromCli(provider, serverUrl)
            AuthKind.GIT_CREDENTIAL -> fromGitCredential(provider, serverUrl)
        }

    private fun fromCli(provider: HostingProvider, serverUrl: String): Secret {
        val login = provider.cliLogin ?: throw CredentialException("${provider.displayName} has no command line login.")
        val executable = Cli.find(login.tool)
            ?: throw CredentialException("${login.displayName} (${login.tool}) was not found on PATH. Install it or use a token instead.")
        val result = Cli.run(executable, login.tokenArgs(serverUrl), timeoutMs = 30_000)
        val token = result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        if (!result.ok || token.isNullOrEmpty()) {
            val reason = result.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            throw CredentialException("${login.displayName} is not signed in. ${login.loginHint}" + if (reason.isEmpty()) "" else " ($reason)")
        }
        return Secret("", token)
    }

    private fun fromGitCredential(provider: HostingProvider, serverUrl: String): Secret {
        val git = Cli.find("git") ?: throw CredentialException("git was not found on PATH.")
        val url = GitUrl.normalizeServerUrl(serverUrl)
        val host = url.substringAfter("://").substringBefore('/')
        val path = url.substringAfter("://").substringAfter('/', "")
        val input = buildString {
            append("protocol=").append(url.substringBefore("://")).append('\n')
            append("host=").append(host).append('\n')
            if (path.isNotEmpty()) append("path=").append(path).append('\n')
            append('\n')
        }
        // Never let git pop up a prompt: we only want what is already stored.
        val env = mapOf("GIT_TERMINAL_PROMPT" to "0", "GCM_INTERACTIVE" to "never", "GIT_ASKPASS" to "", "SSH_ASKPASS" to "")
        val result = Cli.run(git, listOf("credential", "fill"), stdin = input, env = env, timeoutMs = 20_000)
        val values = result.stdout.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        val password = values["password"]
        if (!result.ok || password.isNullOrEmpty()) {
            throw CredentialException(
                "Git has no stored credential for $host. Push or fetch once from a terminal to sign in, or use a token instead.",
            )
        }
        return Secret(values["username"].orEmpty(), password)
    }

    private fun attributes(accountId: String) = CredentialAttributes(generateServiceName("GitOps Studio", accountId))

    companion object {
        /** CLI and git credential tokens are short-lived (about an hour for Azure); re-ask well before that. */
        private const val EXTERNAL_TTL_MS = 20 * 60 * 1000L

        fun get(): CredentialService = service()
    }
}
