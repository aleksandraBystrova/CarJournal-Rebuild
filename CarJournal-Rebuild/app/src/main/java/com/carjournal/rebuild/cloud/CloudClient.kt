package com.carjournal.rebuild.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Public, client-safe Supabase settings. Never embed secret/service_role keys in an APK.
 * HTTPS + Supabase Auth JWT + RLS protect the data; a publishable key alone grants no row access.
 */
internal object CloudConfig {
    const val URL = "https://fxlycwxkgavqmjiguyke.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_KBYfOsAUO8RP_vd1WRwb_Q_iV_o80ip"
}

class CloudClient(context: Context) {
    private val prefs = context.getSharedPreferences("carjournal_supabase_session", Context.MODE_PRIVATE)

    val signedIn: Boolean get() = !prefs.getString("access_token", null).isNullOrBlank()
    val userId: String get() = prefs.getString("user_id", "") ?: ""

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = call("/auth/v1/token?grant_type=password", "POST", body.toByteArray(), false)
        val session = JSONObject(String(response, StandardCharsets.UTF_8))
        val newUser = session.getJSONObject("user").getString("id")
        val oldUser = prefs.getString("bound_user_id", null)
        require(oldUser == null || oldUser == newUser) {
            "Этот телефон уже связан с другой мастерской. Нельзя смешивать её записи с новым аккаунтом."
        }
        saveSession(session, newUser)
        prefs.edit().putString("bound_user_id", newUser).apply()
    }

    fun signOut() {
        // Don't delete Room data on sign-out. A future sign-in with the same user recovers it.
        prefs.edit().remove("access_token").remove("refresh_token")
            .remove("expires_at").remove("user_id").apply()
    }

    private fun saveSession(json: JSONObject, user: String) {
        val secs = json.optLong("expires_in", 3600L)
        prefs.edit()
            .putString("access_token", json.getString("access_token"))
            .putString("refresh_token", json.getString("refresh_token"))
            .putString("user_id", user)
            .putLong("expires_at", System.currentTimeMillis() + secs * 1000L)
            .apply()
    }

    private fun getToken(forceRefresh: Boolean = false): String {
        val access = prefs.getString("access_token", null)
            ?: error("Требуется войти в аккаунт мастерской")
        if (!forceRefresh && System.currentTimeMillis() + 90_000L < prefs.getLong("expires_at", 0)) return access
        val refresh = prefs.getString("refresh_token", null) ?: error("Требуется повторный вход")
        val body = JSONObject().put("refresh_token", refresh).toString().toByteArray()
        val response = call("/auth/v1/token?grant_type=refresh_token", "POST", body, false)
        val json = JSONObject(String(response, StandardCharsets.UTF_8))
        saveSession(json, userId)
        return json.getString("access_token")
    }

    /** All caller paths below are fixed internal paths / UUIDs, never arbitrary external URLs. */
    suspend fun request(
        path: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String = "application/json",
        prefer: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        var token = getToken()
        try {
            call(path, method, body, true, token, contentType, prefer)
        } catch (e: CloudHttpException) {
            if (e.status != 401) throw e
            token = getToken(forceRefresh = true)
            call(path, method, body, true, token, contentType, prefer)
        }
    }

    suspend fun list(table: String): List<JSONObject> {
        require(table in setOf("vehicles", "repairs", "photos"))
        val result = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val bytes = request("/rest/v1/$table?select=*&limit=1000&offset=$offset")
            val arr = JSONArray(String(bytes, StandardCharsets.UTF_8))
            for (i in 0 until arr.length()) result += arr.getJSONObject(i)
            if (arr.length() < 1000) return result
            offset += 1000
        }
    }

    suspend fun upsert(table: String, json: JSONObject) {
        require(table in setOf("vehicles", "repairs", "photos"))
        request("/rest/v1/$table?on_conflict=id", "POST", JSONArray().put(json).toString().toByteArray(),
            prefer = "resolution=merge-duplicates,return=minimal")
    }

    suspend fun softDelete(table: String, uuid: String) {
        require(table in setOf("vehicles", "repairs", "photos"))
        require(UUID_REGEX.matches(uuid))
        val body = JSONObject().put("deleted_at", java.time.Instant.now().toString()).toString().toByteArray()
        request("/rest/v1/$table?id=eq.$uuid", "PATCH", body)
    }

    suspend fun uploadPhoto(storagePath: String, bytes: ByteArray) {
        require(storagePath.startsWith("$userId/") && storagePath.matches(STORAGE_PATH))
        request("/storage/v1/object/car-photos/$storagePath", "POST", bytes,
            contentType = "application/octet-stream", prefer = null)
    }

    suspend fun downloadPhoto(storagePath: String): ByteArray {
        require(storagePath.startsWith("$userId/") && storagePath.matches(STORAGE_PATH))
        return request("/storage/v1/object/authenticated/car-photos/$storagePath")
    }

    private fun call(
        path: String, method: String, body: ByteArray?, authenticated: Boolean,
        token: String = "", contentType: String = "application/json", prefer: String? = null
    ): ByteArray {
        val conn = (URL(CloudConfig.URL + path).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("apikey", CloudConfig.PUBLISHABLE_KEY)
            conn.setRequestProperty("Accept", "application/json")
            if (path.startsWith("/storage/v1/object/") && method == "POST") {
                conn.setRequestProperty("x-upsert", "true")
            }
            if (authenticated) conn.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                val message = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw CloudHttpException(status, "Ошибка Supabase ($status): ${message.take(450)}")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        val UUID_REGEX = Regex("[a-fA-F0-9-]{36}")
        val STORAGE_PATH = Regex("[a-fA-F0-9-]{36}/[a-fA-F0-9-]{36}/[a-fA-F0-9-]{36}\\.jpg")
    }
}

class CloudHttpException(val status: Int, message: String) : Exception(message)
