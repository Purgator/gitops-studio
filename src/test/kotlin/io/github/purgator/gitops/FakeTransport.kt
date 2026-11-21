package io.github.purgator.gitops

import io.github.purgator.gitops.core.http.HttpRequestSpec
import io.github.purgator.gitops.core.http.HttpResponse
import io.github.purgator.gitops.core.http.HttpTransport
import io.github.purgator.gitops.core.provider.Secret
import io.github.purgator.gitops.core.provider.SecretSource

/** Scripted transport: answers by method + URL fragment and records every request. */
class FakeTransport : HttpTransport {
    private class Route(val method: String, val urlPart: String, val status: Int, val body: String)

    val requests = mutableListOf<HttpRequestSpec>()
    private val routes = mutableListOf<Route>()

    /** Later registrations win, so a test can override a default route. */
    fun on(method: String, urlPart: String, body: String, status: Int = 200): FakeTransport {
        routes.add(0, Route(method, urlPart, status, body))
        return this
    }

    override fun execute(request: HttpRequestSpec): HttpResponse {
        requests += request
        val route = routes.firstOrNull { it.method == request.method && it.urlPart in request.url }
            ?: return HttpResponse(404, """{"message":"no route for ${request.method} ${request.url}"}""")
        return HttpResponse(route.status, route.body)
    }

    fun last(method: String, urlPart: String): HttpRequestSpec =
        requests.last { it.method == method && urlPart in it.url }

    fun count(method: String, urlPart: String): Int = requests.count { it.method == method && urlPart in it.url }
}

class FixedSecret(private val token: String = "t0ken", private val username: String = "") : SecretSource {
    var invalidations = 0
    override fun get(): Secret = Secret(username, token)
    override fun invalidate(): Boolean {
        invalidations++
        return false
    }
}
