package io.github.purgator.gitops.core.http

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

class HttpResponse(val status: Int, val body: String, private val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The single seam between provider code and the network; replaced by a fake in tests. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequestSpec): HttpResponse
}

class HttpException(val status: Int, message: String, val responseBody: String = "") : IOException(message) {
    val unauthorized: Boolean get() = status == 401 || status == 203
    val notFound: Boolean get() = status == 404
}

fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

/** Encodes each path segment but keeps the slashes. */
fun encodePath(path: String): String = path.split('/').joinToString("/") { urlEncode(it) }

/**
 * Small JSON-over-HTTP client bound to one API base URL and one credential source.
 * A 401 invalidates the credential once and retries, which covers expired CLI / OAuth tokens.
 */
class ApiClient(
    baseUrl: String,
    private val transport: HttpTransport,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val authHeaders: () -> Map<String, String> = { emptyMap() },
    private val onUnauthorized: () -> Boolean = { false },
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    fun get(path: String, vararg query: Pair<String, Any?>): JsonElement = json("GET", path, null, query.toList())

    fun post(path: String, body: JsonElement? = null, vararg query: Pair<String, Any?>): JsonElement =
        json("POST", path, body, query.toList())

    fun put(path: String, body: JsonElement? = null, vararg query: Pair<String, Any?>): JsonElement =
        json("PUT", path, body, query.toList())

    fun patch(path: String, body: JsonElement? = null, vararg query: Pair<String, Any?>): JsonElement =
        json("PATCH", path, body, query.toList())

    fun delete(path: String, vararg query: Pair<String, Any?>): JsonElement = json("DELETE", path, null, query.toList())

    fun getText(path: String, vararg query: Pair<String, Any?>): String =
        send("GET", path, null, query.toList(), mapOf("Accept" to "text/plain, */*")).body

    fun json(method: String, path: String, body: JsonElement?, query: List<Pair<String, Any?>> = emptyList()): JsonElement =
        parseJson(send(method, path, body?.toString(), query).body)

    fun send(
        method: String,
        path: String,
        body: String?,
        query: List<Pair<String, Any?>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val url = buildUrl(path, query)
        var response = execute(method, url, body, extraHeaders)
        if (isAuthFailure(response) && onUnauthorized()) response = execute(method, url, body, extraHeaders)
        if (isAuthFailure(response)) {
            throw HttpException(response.status, "Authentication failed. Check the account's token or sign in again.", response.body)
        }
        if (response.status >= 400) {
            throw HttpException(response.status, describeError(response), response.body)
        }
        return response
    }

    fun buildUrl(path: String, query: List<Pair<String, Any?>> = emptyList()): String {
        val base = if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + "/" + path.trimStart('/')
        val params = query.filter { it.second != null }
        if (params.isEmpty()) return base
        val separator = if ('?' in base) "&" else "?"
        return base + separator + params.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v.toString()) }
    }

    private fun execute(method: String, url: String, body: String?, extraHeaders: Map<String, String>): HttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers.putAll(defaultHeaders)
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders)
        headers.putAll(authHeaders())
        return transport.execute(HttpRequestSpec(method, url, headers, body))
    }

    /** Azure DevOps answers an invalid token with `203` and an HTML sign-in page instead of `401`. */
    private fun isAuthFailure(response: HttpResponse): Boolean = response.status == 401 || response.status == 203

    private fun describeError(response: HttpResponse): String {
        val json = parseJson(response.body)
        val detail = sequenceOf(
            json.str("message"),
            json.str("error", "message"),
            json.str("error_description"),
            json["error"].string.orEmpty(),
            json.str("errorMessage"),
            json.list("errors").joinToString("; ") { it.string ?: it.str("message") },
            (json["message"] as? JsonObject)?.toString().orEmpty(),
        ).firstOrNull { it.isNotBlank() }
        val reason = when (response.status) {
            403 -> "Access denied (403)"
            404 -> "Not found (404)"
            409 -> "Conflict (409)"
            422 -> "Rejected by the server (422)"
            429 -> "Rate limit reached (429)"
            else -> "HTTP ${response.status}"
        }
        return if (detail.isNullOrBlank()) reason else "$reason: ${detail.take(400)}"
    }
}
