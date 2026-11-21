package io.github.purgator.gitops.core.http

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/** Production transport: the JDK HTTP client wired to the IDE's proxy and certificate settings. */
object JdkHttpTransport : HttpTransport {
    private val log = logger<JdkHttpTransport>()
    private const val MAX_REDIRECTS = 5
    private val redirects = setOf(301, 302, 303, 307, 308)

    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
        runCatching { builder.proxy(JdkProxyProvider.getInstance().proxySelector) }
            .onFailure { log.debug("IDE proxy settings unavailable", it) }
        runCatching { builder.sslContext(CertificateManager.getInstance().sslContext) }
            .onFailure { log.debug("IDE certificate store unavailable", it) }
        builder.build()
    }

    private val userAgent: String by lazy {
        val ide = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("IDE")
        "GitOpsStudio ($ide)"
    }

    override fun execute(request: HttpRequestSpec): HttpResponse {
        var current = request
        repeat(MAX_REDIRECTS + 1) {
            val response = send(current)
            val location = response.header("Location")
            if (response.status !in redirects || location == null) return response
            val origin = URI.create(current.url)
            val target = origin.resolve(location)
            val sameHost = target.host.equals(origin.host, ignoreCase = true)
            // Credentials must never follow a redirect to another host (signed blob URLs reject them anyway).
            val headers = if (sameHost) current.headers else current.headers.filterKeys { !it.equals("Authorization", true) }
            val keepMethod = response.status == 307 || response.status == 308
            current = HttpRequestSpec(
                method = if (keepMethod) current.method else "GET",
                url = target.toString(),
                headers = headers,
                body = if (keepMethod) current.body else null,
            )
        }
        throw IOException("Too many redirects for ${request.url}")
    }

    private fun send(spec: HttpRequestSpec): HttpResponse {
        val uri = URI.create(spec.url)
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", userAgent)
        spec.headers.forEach { (name, value) -> builder.header(name, value) }
        val publisher = spec.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(spec.method, publisher)
        try {
            val response = client.send(builder.build(), BodyHandlers.ofString())
            val headers = response.headers().map().mapValues { it.value.firstOrNull().orEmpty() }
            return HttpResponse(response.statusCode(), response.body().orEmpty(), headers)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Request cancelled", e)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IOException("The server did not answer in time (${uri.host}).", e)
        } catch (e: java.net.ConnectException) {
            throw IOException("Cannot reach ${uri.host}. Check your connection or proxy settings.", e)
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            throw IOException("Cannot resolve ${uri.host}. Check your connection or proxy settings.", e)
        }
    }
}
