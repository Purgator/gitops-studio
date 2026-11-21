package io.github.purgator.gitops.core.util

/** A git remote URL split into its parts, whatever its flavour (https, ssh://, scp-like). */
data class GitUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    /** Path without leading slash and without the `.git` suffix. */
    val path: String,
    val user: String?,
) {
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }
    val isSsh: Boolean get() = scheme == "ssh"

    /** `https://host[:port]` — ports of ssh remotes are dropped since they never match the web port. */
    val webBase: String
        get() = if (isSsh || port == null) "https://$host" else "$scheme://$host:$port"

    companion object {
        private val URL = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:([^@/]+)@)?([^/:]+)(?::(\d+))?/(.+)$""")
        private val SCP = Regex("""^(?:([^@/\s]+)@)?([^/:\s]+):(?!/)(.+)$""")

        fun parse(raw: String): GitUrl? {
            val url = raw.trim()
            if (url.isEmpty()) return null
            URL.matchEntire(url)?.let { m ->
                val (rawScheme, user, host, port, path) = m.destructured
                val scheme = rawScheme.lowercase().let { if (it == "git+ssh") "ssh" else it }
                if (scheme !in setOf("http", "https", "ssh", "git")) return null
                return GitUrl(
                    scheme = scheme,
                    host = host.lowercase(),
                    port = port.toIntOrNull(),
                    path = cleanPath(path),
                    user = user.substringBefore(':').ifEmpty { null },
                )
            }
            SCP.matchEntire(url)?.let { m ->
                val (user, host, path) = m.destructured
                return GitUrl("ssh", host.lowercase(), null, cleanPath(path), user.ifEmpty { null })
            }
            return null
        }

        private fun cleanPath(path: String): String =
            path.substringBefore('?').trim('/').removeSuffix(".git").trimEnd('/')

        /** Host of a server URL typed by a user (`gitlab.acme.io`, `https://gitlab.acme.io/`, …). */
        fun hostOf(serverUrl: String): String =
            serverUrl.trim().substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':').lowercase()

        fun normalizeServerUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if ("://" in trimmed) trimmed else "https://$trimmed"
        }
    }
}
