package com.gamebooster.app.data

import com.gamebooster.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val code: Int = 0) : Exception(message)

class SupabaseApi(private val store: SessionStore) {
    private val base get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anon get() = BuildConfig.SUPABASE_ANON_KEY
    val configured get() = base.startsWith("https://") && anon.isNotBlank()

    private fun http(method: String, url: String, body: String? = null,
                     headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000; c.readTimeout = 10000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    private fun requireConfigured() {
        if (!configured) throw ApiException("Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
    }

    private fun parseSession(text: String, fallbackEmail: String): Session {
        val j = JSONObject(text)
        val user = j.optJSONObject("user")
        return Session(j.getString("access_token"), j.getString("refresh_token"),
            user?.optString("id") ?: "", user?.optString("email") ?: fallbackEmail)
    }

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("email", email).put("password", password).toString()
        val (code, text) = http("POST", "$base/auth/v1/token?grant_type=password", body, mapOf("apikey" to anon))
        if (code !in 200..299) throw ApiException(errMsg(text, "Sign-in failed"), 0)
        parseSession(text, email).also { store.save(it) }
    }

    /** Returns a session if sign-up auto-confirms, or null if email confirmation is required. */
    suspend fun signUp(email: String, password: String): Session? = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("email", email).put("password", password).toString()
        val (code, text) = http("POST", "$base/auth/v1/signup", body, mapOf("apikey" to anon))
        if (code !in 200..299) throw ApiException(errMsg(text, "Sign-up failed"), 0)
        if (JSONObject(text).has("access_token")) parseSession(text, email).also { store.save(it) } else null
    }

    private fun refresh(): Session {
        val old = store.load() ?: throw ApiException("Not signed in", 401)
        val body = JSONObject().put("refresh_token", old.refreshToken).toString()
        val (code, text) = http("POST", "$base/auth/v1/token?grant_type=refresh_token", body, mapOf("apikey" to anon))
        if (code !in 200..299) { store.clearSession(); throw ApiException("Session expired. Please sign in again.", 401) }
        return parseSession(text, old.email).also { store.save(it) }
    }

    private fun authed(method: String, url: String, body: String? = null): String {
        var s = store.load() ?: throw ApiException("Not signed in", 401)
        for (attempt in 0..1) {
            val (code, text) = http(method, url, body, mapOf(
                "apikey" to anon, "Authorization" to "Bearer ${s.accessToken}", "Prefer" to "return=minimal"))
            if (code == 401 && attempt == 0) { s = refresh(); continue }
            if (code !in 200..299) throw ApiException(errMsg(text, "Request failed ($code)"), code)
            return text
        }
        throw ApiException("Unauthorized", 401)
    }

    suspend fun servers(): List<Server> = withContext(Dispatchers.IO) {
        requireConfigured()
        val arr = JSONArray(authed("GET", "$base/rest/v1/servers?enabled=eq.true&select=*"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Server(o.getString("id"), o.getString("name"), o.optString("location"), o.getString("host"),
                o.optInt("wg_port", 51820), o.getString("wg_public_key"), o.getString("api_url"),
                o.optInt("probe_port", 443))
        }
    }

    suspend fun games(): List<GameProfile> = withContext(Dispatchers.IO) {
        requireConfigured()
        val arr = JSONArray(authed("GET", "$base/rest/v1/game_profiles?select=*"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GameProfile(o.getString("id"), o.getString("package_name"), o.getString("display_name"),
                o.getString("probe_host"), o.optInt("probe_port", 443),
                if (o.isNull("preferred_server_id")) null else o.optString("preferred_server_id"))
        }
    }

    suspend fun logConnection(serverId: String, gamePackage: String, success: Boolean) =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = store.load()?.userId ?: return@runCatching
                val body = JSONObject().put("user_id", uid).put("server_id", serverId)
                    .put("game_package", gamePackage).put("success", success).toString()
                authed("POST", "$base/rest/v1/connection_events", body)
            }
            Unit
        }

    /** Ask the gateway agent to authorise this device's public key and assign a tunnel address. */
    suspend fun register(server: Server, publicKey: String): Registration = withContext(Dispatchers.IO) {
        if (!server.apiUrl.startsWith("https://")) throw ApiException("Server API must use HTTPS.")
        var s = store.load() ?: throw ApiException("Not signed in", 401)
        val body = JSONObject().put("public_key", publicKey).toString()
        for (attempt in 0..1) {
            val (code, text) = http("POST", server.apiUrl.trimEnd('/') + "/register", body,
                mapOf("Authorization" to "Bearer ${s.accessToken}"))
            if (code == 401 && attempt == 0) { s = refresh(); continue }
            if (code !in 200..299) throw ApiException(errMsg(text, "Gateway rejected registration ($code)"), 0)
            val j = JSONObject(text)
            return@withContext Registration(j.getString("address"), j.optString("dns", "1.1.1.1"))
        }
        throw ApiException("Unauthorized", 401)
    }

    private fun errMsg(text: String, fallback: String): String = runCatching {
        val j = JSONObject(text)
        j.optString("msg").ifBlank { j.optString("error_description") }
            .ifBlank { j.optString("message") }.ifBlank { j.optString("error") }
    }.getOrDefault("").ifBlank { fallback }
}
