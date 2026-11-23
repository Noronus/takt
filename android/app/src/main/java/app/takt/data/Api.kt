package app.takt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val status: Int = 0) : Exception(message)

/**
 * Small HTTP client tuned for bad mobile networks: short timeouts, a few quick retries
 * with backoff, every call cancellable. The UI never waits on this without a cache.
 */
class Api(private val prefs: Prefs) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    private fun url(path: String, params: Map<String, String?>): String {
        val base = prefs.server.trimEnd('/')
        if (base.isBlank()) throw ApiException("Kein Server eingestellt (Einstellungen)")
        val b = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw ApiException("Server-URL ungültig")
        params.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    /**
     * Returns body text, or null for 304. Retries network errors and 5xx up to [attempts] times.
     * Runs entirely on the IO dispatcher: reading the body is blocking network I/O.
     */
    suspend fun get(path: String, params: Map<String, String?> = emptyMap(), etag: String? = null, attempts: Int = 3): Pair<String?, String?> =
        withContext(Dispatchers.IO) { getBlocking(path, params, etag, attempts) }

    private suspend fun getBlocking(path: String, params: Map<String, String?>, etag: String?, attempts: Int): Pair<String?, String?> {
        val u = url(path, params)
        var last: Exception? = null
        repeat(attempts) { i ->
            if (i > 0) delay(if (i == 1) 400L else 1500L)
            try {
                val rb = Request.Builder().url(u)
                if (prefs.token.isNotBlank()) rb.header("Authorization", "Bearer ${prefs.token}")
                if (etag != null) rb.header("If-None-Match", etag)
                client.newCall(rb.build()).await().use { r ->
                    if (r.code == 304) return null to etag
                    val body = r.body?.string() ?: ""
                    if (r.isSuccessful) return body to r.header("ETag")
                    val msg = runCatching { json.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonObject)?.get("error")?.toString()?.trim('"') } }.getOrNull()
                    val ex = ApiException(msg ?: "Server-Fehler ${r.code}", r.code)
                    if (r.code in 400..499) throw ex
                    last = ex
                }
            } catch (e: ApiException) {
                if (e.status in 400..499) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw ApiException(friendly(last))
    }

    private fun friendly(e: Exception?): String = when (e) {
        is ApiException -> e.message ?: "Fehler"
        is java.net.SocketTimeoutException -> "Zeitüberschreitung – Netz zu langsam"
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Server nicht erreichbar (VPN an?)"
        else -> e?.message ?: "Netzwerkfehler"
    }

    suspend inline fun <reified T> getJson(path: String, params: Map<String, String?> = emptyMap(), attempts: Int = 3): T {
        val (body, _) = get(path, params, attempts = attempts)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body ?: throw ApiException("leere Antwort")) }
    }
}
