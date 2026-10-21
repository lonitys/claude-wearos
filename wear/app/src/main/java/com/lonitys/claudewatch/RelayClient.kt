package com.lonitys.claudewatch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Blocking HTTP client for the relay. Call from a background thread. */
class RelayClient(private val baseUrl: String, private val token: String) {

    fun state(): JSONObject = get("/api/state")

    /** Long-polls for events newer than [since]; returns {"events": [...], "last": n}. */
    fun events(since: Long, waitSeconds: Int = 50): JSONObject =
        get("/api/events?since=$since&wait=$waitSeconds")

    fun decide(id: String, decision: String, message: String = "") {
        post("/api/permission/$id/decision", JSONObject().put("decision", decision).put("message", message))
    }

    fun reply(sessionId: String, text: String) {
        post("/api/reply", JSONObject().put("session_id", sessionId).put("text", text))
    }

    private fun get(path: String) = execute(Request.Builder().url(baseUrl + path).get())

    private fun post(path: String, body: JSONObject) =
        execute(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(JSON)))

    private fun execute(builder: Request.Builder): JSONObject {
        val request = builder.header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Long polls hold the request open for up to 60 s on the relay.
            .readTimeout(80, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
