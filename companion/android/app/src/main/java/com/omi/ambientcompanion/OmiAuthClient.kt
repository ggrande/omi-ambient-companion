package com.omi.ambientcompanion

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import org.json.JSONArray

class OmiAuthClient(private val context: Context) {
    private val prefs = AppPrefs(context)
    private val secureStore = SecureStore(context)
    private val audit = AuditLog(context)

    fun buildSignInIntent(provider: String = "google"): Intent {
        val state = UUID.randomUUID().toString()
        prefs.omiAuthState = state
        val url = "${BuildConfig.OMI_API_BASE_URL}v1/auth/authorize" +
            "?provider=${enc(provider)}" +
            "&redirect_uri=${enc(BuildConfig.OMI_AUTH_REDIRECT_URI)}" +
            "&state=${enc(state)}"
        audit.record("omi_auth_started", mapOf("provider" to provider))
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    fun handleCallback(uri: Uri): Boolean {
        val expectedState = prefs.omiAuthState
        val returnedState = uri.getQueryParameter("state").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        if (expectedState.isBlank() || returnedState != expectedState) {
            audit.record("omi_auth_rejected", mapOf("reason" to "state_mismatch"))
            return false
        }
        if (code.isBlank()) {
            audit.record("omi_auth_rejected", mapOf("reason" to "missing_code"))
            return false
        }
        val tokenResponse = exchangeCode(code)
        if (tokenResponse.status !in 200..299) {
            audit.record("omi_auth_rejected", mapOf("reason" to "token_http_${tokenResponse.status}", "body" to tokenResponse.body.take(200)))
            return false
        }
        val tokenJson = JSONObject(tokenResponse.body)
        val firebaseResponse = signInWithFirebase(tokenJson)
        if (firebaseResponse.status !in 200..299) {
            audit.record("omi_auth_rejected", mapOf("reason" to "firebase_http_${firebaseResponse.status}", "body" to firebaseResponse.body.take(200)))
            return false
        }
        storeFirebaseSession(JSONObject(firebaseResponse.body))
        prefs.omiAuthState = ""
        audit.record("omi_auth_completed", mapOf("uid" to prefs.omiAuthUid, "email" to prefs.omiAuthEmail))
        return true
    }

    fun signOut() {
        secureStore.putSecret(ID_TOKEN_KEY, "")
        secureStore.putSecret(REFRESH_TOKEN_KEY, "")
        prefs.omiAuthUid = ""
        prefs.omiAuthEmail = ""
        prefs.omiTokenExpiresAtMs = 0
        audit.record("omi_auth_signed_out")
    }

    fun isSignedIn(): Boolean = prefs.omiAuthUid.isNotBlank() && secureStore.getSecret(REFRESH_TOKEN_KEY).isNotBlank()

    fun getFreshIdToken(): String {
        val cached = secureStore.getSecret(ID_TOKEN_KEY)
        if (cached.isNotBlank() && prefs.omiTokenExpiresAtMs > System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MS) {
            return cached
        }
        val refreshToken = secureStore.getSecret(REFRESH_TOKEN_KEY)
        if (refreshToken.isBlank()) return ""
        val body = "grant_type=refresh_token&refresh_token=${enc(refreshToken)}"
        val response = request(
            method = "POST",
            url = "${FIREBASE_REFRESH_URL}?key=${BuildConfig.OMI_FIREBASE_API_KEY}",
            body = body,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        if (response.status !in 200..299) {
            audit.record("omi_auth_refresh_failed", mapOf("status" to response.status, "body" to response.body.take(200)))
            return ""
        }
        val json = JSONObject(response.body)
        secureStore.putSecret(ID_TOKEN_KEY, json.optString("id_token"))
        secureStore.putSecret(REFRESH_TOKEN_KEY, json.optString("refresh_token", refreshToken))
        prefs.omiAuthUid = json.optString("user_id", prefs.omiAuthUid)
        val expiresIn = json.optLong("expires_in", 3600)
        prefs.omiTokenExpiresAtMs = System.currentTimeMillis() + (expiresIn * 1000)
        return secureStore.getSecret(ID_TOKEN_KEY)
    }

    fun uploadAudioFile(meta: SpoolMetadata, chunks: Sequence<ByteArray>): OmiAudioSyncResult {
        val token = getFreshIdToken()
        if (token.isBlank()) {
            audit.record("omi_audio_sync_skipped", mapOf("reason" to "missing_omi_auth"))
            return OmiAudioSyncResult(false, "none", meta.sessionId, status = "missing_omi_auth")
        }
        val bytes = lengthPrefixedBytes(chunks)
        if (bytes.isEmpty()) return OmiAudioSyncResult(false, "none", meta.sessionId, status = "empty_audio")
        val filename = AmbientSyncFilenames.omiPcm16Bin(meta)
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "X-App-Platform" to "android-ambient-companion",
        )
        val v2Url = "${BuildConfig.OMI_API_BASE_URL}v2/sync-local-files"
        val v2 = requestMultipart(v2Url, filename, bytes, headers)
        if (v2.status == 202) {
            return pollSyncJob(v2Url, v2.body, headers, meta)
        }
        if (v2.status in 200..299) {
            val result = parseSyncResult(meta, "v2", v2.status, v2.body)
            recordUploadResult(result)
            return result
        }
        if (v2.status == 404 || v2.status == 405) {
            val v1 = requestMultipart("${BuildConfig.OMI_API_BASE_URL}v1/sync-local-files", filename, bytes, headers)
            if (v1.status in 200..299) {
                val result = parseSyncResult(meta, "v1", v1.status, v1.body)
                recordUploadResult(result)
                return result
            }
            audit.record("omi_audio_sync_failed", mapOf("endpoint" to "v1", "status" to v1.status, "body" to v1.body.take(200)))
            return OmiAudioSyncResult(false, "v1", meta.sessionId, status = "http_${v1.status}", httpStatus = v1.status, body = v1.body.take(240))
        }
        audit.record("omi_audio_sync_failed", mapOf("endpoint" to "v2", "status" to v2.status, "body" to v2.body.take(200)))
        return OmiAudioSyncResult(false, "v2", meta.sessionId, status = "http_${v2.status}", httpStatus = v2.status, body = v2.body.take(240))
    }

    fun uploadFallbackSegments(segments: List<FallbackSegment>): Boolean {
        val token = getFreshIdToken()
        if (token.isBlank()) {
            audit.record("omi_fallback_sync_skipped", mapOf("reason" to "missing_omi_auth", "count" to segments.size))
            return false
        }
        val candidates = segments
            .filter { it.text.isNotBlank() && (!it.rawAudioAvailable || it.source == FallbackSource.LOCAL_STT) }
            .sortedBy { it.start }
            .take(500)
        val usable = if (prefs.junkFilterEnabled) {
            candidates.filter { ConversationQualityFilter.evaluate(it).allow }
        } else {
            candidates
        }
        val rejected = candidates.size - usable.size
        if (rejected > 0) {
            audit.record("omi_fallback_segments_junk_filtered", mapOf("rejected" to rejected, "candidates" to candidates.size))
        }
        if (usable.isEmpty()) return false

        val startedAt = usable.first().start
        val finishedAt = usable.maxOf { it.end }
        val arr = JSONArray()
        usable.forEach { segment ->
            val start = Duration.between(startedAt, segment.start).toMillis().coerceAtLeast(0).toDouble() / 1000.0
            val rawEnd = Duration.between(startedAt, segment.end).toMillis().coerceAtLeast(0).toDouble() / 1000.0
            val end = rawEnd.coerceAtLeast(start + 0.2)
            arr.put(
                JSONObject()
                    .put("text", "[fallback:${segment.apiSource()} health:${segment.healthState.name}] ${segment.text.trim()}")
                    .put("speaker", "SPEAKER_00")
                    .put("speaker_id", 0)
                    .put("is_user", false)
                    .put("start", start)
                    .put("end", end),
            )
        }
        val body = JSONObject()
            .put("transcript_segments", arr)
            .put("source", "phone")
            .put("started_at", startedAt.toString())
            .put("finished_at", finishedAt.toString())
            .put("language", "en")
            .toString()
        val response = request(
            method = "POST",
            url = "${BuildConfig.OMI_API_BASE_URL}v1/dev/user/conversations/from-segments",
            body = body,
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
        )
        if (response.status in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val conversationId = json?.optString("id").orEmpty()
            val discarded = json?.optBoolean("discarded", false) ?: false
            prefs.lastOmiSyncTrace = "Fallback conversation: ${conversationId.take(8).ifBlank { "unknown" }} discarded=$discarded"
            audit.record(
                "omi_fallback_segments_uploaded",
                mapOf("count" to usable.size, "conversation_id" to conversationId, "discarded" to discarded),
            )
            return true
        }
        audit.record("omi_fallback_segments_failed", mapOf("status" to response.status, "body" to response.body.take(240), "count" to usable.size))
        return false
    }

    fun refreshSpeechProfileStatus(): Boolean? {
        val token = getFreshIdToken()
        if (token.isBlank()) {
            audit.record("omi_speech_profile_check_skipped", mapOf("reason" to "missing_omi_auth"))
            return null
        }
        val response = request(
            method = "GET",
            url = "${BuildConfig.OMI_API_BASE_URL}v3/speech-profile",
            body = null,
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        if (response.status !in 200..299) {
            audit.record("omi_speech_profile_check_failed", mapOf("status" to response.status, "body" to response.body.take(180)))
            return null
        }
        val hasProfile = runCatching { JSONObject(response.body).optBoolean("has_profile", false) }.getOrDefault(false)
        prefs.omiHasSpeechProfile = hasProfile
        prefs.omiSpeechProfileCheckedAtMs = System.currentTimeMillis()
        audit.record("omi_speech_profile_checked", mapOf("has_profile" to hasProfile))
        return hasProfile
    }

    fun traceSyncResult(result: OmiAudioSyncResult, phase: String = "immediate") {
        if (!result.success) return
        val token = getFreshIdToken()
        if (token.isBlank()) {
            audit.record("omi_sync_trace_skipped", mapOf("reason" to "missing_omi_auth", "session_id" to result.sessionId))
            return
        }
        val headers = mapOf("Authorization" to "Bearer $token")
        val ids = result.conversationIds()
        if (ids.isNotEmpty()) {
            ids.take(6).forEach { id ->
                val response = request(
                    method = "GET",
                    url = "${BuildConfig.OMI_API_BASE_URL}v1/dev/user/conversations/${encPath(id)}?include_transcript=true",
                    body = null,
                    headers = headers,
                )
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                val segmentCount = json?.optJSONArray("transcript_segments")?.length() ?: -1
                val title = json?.optJSONObject("structured")?.optString("title").orEmpty()
                val source = json?.optString("source").orEmpty()
                val discarded = json?.optBoolean("discarded", false) ?: false
                audit.record(
                    "omi_conversation_trace",
                    mapOf(
                        "phase" to phase,
                        "conversation_id" to id,
                        "http_status" to response.status,
                        "title" to title.take(80),
                        "source" to source,
                        "discarded" to discarded,
                        "transcript_segments" to segmentCount,
                    ),
                )
                prefs.lastOmiSyncTrace = "Trace $phase: ${ids.size} id(s), $id status ${response.status}, discarded=$discarded, segments $segmentCount"
            }
            return
        }

        val recent = request(
            method = "GET",
            url = "${BuildConfig.OMI_API_BASE_URL}v1/conversations?include_discarded=true&limit=10&offset=0",
            body = null,
            headers = headers,
        )
        val memories = parseConversationList(recent.body)
        val first = memories.optJSONObject(0)
        val firstId = first?.optString("id").orEmpty()
        val firstDiscarded = first?.optBoolean("discarded", false) ?: false
        audit.record(
            "omi_recent_conversations_trace",
            mapOf(
                "phase" to phase,
                "http_status" to recent.status,
                "count" to memories.length(),
                "first_id" to firstId,
                "first_discarded" to firstDiscarded,
                "body" to recent.body.take(180),
            ),
        )
        prefs.lastOmiSyncTrace = "Trace $phase: no ids returned; recent ${recent.status}, count ${memories.length()}, first=${firstId.take(8).ifBlank { "none" }} discarded=$firstDiscarded"
    }

    private fun pollSyncJob(baseUrl: String, body: String, headers: Map<String, String>, meta: SpoolMetadata): OmiAudioSyncResult {
        val start = runCatching { JSONObject(body) }.getOrNull()
        val jobId = start?.optString("job_id").orEmpty()
        if (jobId.isBlank()) {
            audit.record("omi_audio_sync_failed", mapOf("endpoint" to "v2", "status" to 202, "body" to body.take(200)))
            return OmiAudioSyncResult(false, "v2", meta.sessionId, status = "missing_job_id", httpStatus = 202, body = body.take(240))
        }
        var pollAfterMs = start?.optLong("poll_after_ms", 3000L)?.coerceIn(1000L, 10_000L) ?: 3000L
        audit.record("omi_audio_sync_job_started", mapOf("endpoint" to "v2", "session_id" to meta.sessionId, "job_id" to jobId))
        repeat(MAX_SYNC_JOB_POLLS) {
            Thread.sleep(pollAfterMs)
            val poll = request("GET", "$baseUrl/$jobId", null, headers)
            if (poll.status !in 200..299) {
                audit.record(
                    "omi_audio_sync_poll_failed",
                    mapOf("endpoint" to "v2", "session_id" to meta.sessionId, "job_id" to jobId, "status" to poll.status),
                )
                return@repeat
            }
            val json = runCatching { JSONObject(poll.body) }.getOrNull() ?: return@repeat
            val status = json.optString("status")
            if (status == "completed" || status == "partial_failure") {
                val result = parseSyncResult(meta, "v2", poll.status, poll.body, jobId)
                recordUploadResult(result)
                return result
            }
            if (status == "failed") {
                audit.record(
                    "omi_audio_sync_failed",
                    mapOf("endpoint" to "v2", "session_id" to meta.sessionId, "job_id" to jobId, "status" to status, "body" to poll.body.take(200)),
                )
                return parseSyncResult(meta, "v2", poll.status, poll.body, jobId).copy(success = false)
            }
            pollAfterMs = 3000L
        }
        audit.record("omi_audio_sync_failed", mapOf("endpoint" to "v2", "session_id" to meta.sessionId, "job_id" to jobId, "reason" to "poll_timeout"))
        return OmiAudioSyncResult(false, "v2", meta.sessionId, jobId = jobId, status = "poll_timeout")
    }

    private fun parseSyncResult(
        meta: SpoolMetadata,
        endpoint: String,
        httpStatus: Int,
        body: String,
        jobIdOverride: String = "",
    ): OmiAudioSyncResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val status = json?.optString("status").orEmpty().ifBlank { if (httpStatus in 200..299) "completed" else "http_$httpStatus" }
        return OmiAudioSyncResult(
            success = httpStatus in 200..299 && status != "failed",
            endpoint = endpoint,
            sessionId = meta.sessionId,
            jobId = jobIdOverride.ifBlank { json?.optString("job_id").orEmpty() },
            status = status,
            newConversationIds = jsonArrayStrings(json?.optJSONArray("new_memories")),
            updatedConversationIds = jsonArrayStrings(json?.optJSONArray("updated_memories")),
            successfulSegments = json?.optInt("successful_segments", 0) ?: 0,
            failedSegments = json?.optInt("failed_segments", 0) ?: 0,
            httpStatus = httpStatus,
            body = body.take(240),
        )
    }

    private fun recordUploadResult(result: OmiAudioSyncResult) {
        prefs.lastOmiSyncTrace = result.summary()
        audit.record(
            "omi_audio_sync_uploaded",
            mapOf(
                "endpoint" to result.endpoint,
                "session_id" to result.sessionId,
                "job_id" to result.jobId,
                "status" to result.status,
                "successful_segments" to result.successfulSegments,
                "failed_segments" to result.failedSegments,
                "new_memories" to result.newConversationIds.joinToString(","),
                "updated_memories" to result.updatedConversationIds.joinToString(","),
            ),
        )
    }

    private fun jsonArrayStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun parseConversationList(body: String): JSONArray {
        return runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body).optJSONArray("memories") }.getOrNull()
            ?: JSONArray()
    }

    private fun exchangeCode(code: String): HttpResponse {
        val body = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to BuildConfig.OMI_AUTH_REDIRECT_URI,
            "use_custom_token" to "true",
        ).joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }
        return request(
            method = "POST",
            url = "${BuildConfig.OMI_API_BASE_URL}v1/auth/token",
            body = body,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
    }

    private fun signInWithFirebase(tokenJson: JSONObject): HttpResponse {
        // Match the production Omi app's default auth path: use the provider
        // OAuth credentials first. Custom tokens are useful for dev builds and
        // alternate bundle IDs, but they must be exchanged against the exact
        // Firebase project that minted them. If that project differs from the
        // public mobile Firebase config, Firebase returns CREDENTIAL_MISMATCH.
        val providerResponse = signInWithProviderCredential(tokenJson)
        if (providerResponse.status in 200..299) return providerResponse

        audit.record(
            "omi_auth_provider_credential_failed",
            mapOf("status" to providerResponse.status, "body" to providerResponse.body.take(200)),
        )

        val customToken = tokenJson.optString("custom_token")
        if (customToken.isNotBlank()) return signInWithCustomToken(customToken)

        return providerResponse
    }

    private fun signInWithCustomToken(customToken: String): HttpResponse {
        val body = JSONObject()
            .put("token", customToken)
            .put("returnSecureToken", true)
            .toString()
        return request(
            method = "POST",
            url = "${FIREBASE_SIGN_IN_CUSTOM_URL}?key=${BuildConfig.OMI_FIREBASE_API_KEY}",
            body = body,
            headers = mapOf("Content-Type" to "application/json"),
        )
    }

    private fun signInWithProviderCredential(tokenJson: JSONObject): HttpResponse {
        val provider = tokenJson.optString("provider")
        val providerId = tokenJson.optString("provider_id", if (provider == "apple") "apple.com" else "google.com")
        val postBody = buildString {
            append("id_token=").append(enc(tokenJson.optString("id_token")))
            append("&providerId=").append(enc(providerId))
            val accessToken = tokenJson.optString("access_token")
            if (accessToken.isNotBlank()) append("&access_token=").append(enc(accessToken))
        }
        val body = JSONObject()
            .put("postBody", postBody)
            .put("requestUri", "http://localhost")
            .put("returnIdpCredential", true)
            .put("returnSecureToken", true)
            .toString()
        return request(
            method = "POST",
            url = "${FIREBASE_SIGN_IN_IDP_URL}?key=${BuildConfig.OMI_FIREBASE_API_KEY}",
            body = body,
            headers = mapOf("Content-Type" to "application/json"),
        )
    }

    private fun storeFirebaseSession(json: JSONObject) {
        val idToken = json.optString("idToken")
        val refreshToken = json.optString("refreshToken")
        val uid = json.optString("localId")
        val email = json.optString("email")
        secureStore.putSecret(ID_TOKEN_KEY, idToken)
        secureStore.putSecret(REFRESH_TOKEN_KEY, refreshToken)
        prefs.omiAuthUid = uid
        prefs.omiAuthEmail = email
        if (uid.isNotBlank()) prefs.omiUserId = uid
        val expiresIn = json.optLong("expiresIn", 3600)
        prefs.omiTokenExpiresAtMs = System.currentTimeMillis() + (expiresIn * 1000)
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            HttpResponse(status, stream?.bufferedReader()?.readText().orEmpty())
        } catch (e: Throwable) {
            HttpResponse(599, e.toString())
        }
    }

    private fun requestMultipart(url: String, filename: String, bytes: ByteArray, headers: Map<String, String>): HttpResponse {
        return try {
            val boundary = "omiAmbient${UUID.randomUUID().toString().replace("-", "")}"
            val body = ByteArrayOutputStream()
            body.write("--$boundary\r\n".toByteArray())
            body.write("Content-Disposition: form-data; name=\"files\"; filename=\"$filename\"\r\n".toByteArray())
            body.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            body.write(bytes)
            body.write("\r\n--$boundary--\r\n".toByteArray())
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            HttpResponse(status, stream?.bufferedReader()?.readText().orEmpty())
        } catch (e: Throwable) {
            HttpResponse(599, e.toString())
        }
    }

    private fun lengthPrefixedBytes(chunks: Sequence<ByteArray>): ByteArray {
        return chunks.fold(ByteArrayOutputStream()) { out, chunk ->
            out.write(intLe(chunk.size))
            out.write(chunk)
            out
        }.toByteArray()
    }

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encPath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val ID_TOKEN_KEY = "omi_firebase_id_token"
        private const val REFRESH_TOKEN_KEY = "omi_firebase_refresh_token"
        private const val TOKEN_REFRESH_SKEW_MS = 5 * 60 * 1000L
        private const val FIREBASE_SIGN_IN_CUSTOM_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken"
        private const val FIREBASE_SIGN_IN_IDP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"
        private const val FIREBASE_REFRESH_URL = "https://securetoken.googleapis.com/v1/token"
        private const val MAX_SYNC_JOB_POLLS = 80
    }
}

data class OmiAudioSyncResult(
    val success: Boolean,
    val endpoint: String,
    val sessionId: String,
    val jobId: String = "",
    val status: String = "",
    val newConversationIds: List<String> = emptyList(),
    val updatedConversationIds: List<String> = emptyList(),
    val successfulSegments: Int = 0,
    val failedSegments: Int = 0,
    val httpStatus: Int = 0,
    val body: String = "",
) {
    fun conversationIds(): List<String> = (newConversationIds + updatedConversationIds).distinct()

    fun hasServerConversationSignal(): Boolean = conversationIds().isNotEmpty() || successfulSegments > 0

    fun summary(): String {
        val shortJob = jobId.take(8).ifBlank { "none" }
        return "job=$shortJob status=${status.ifBlank { "unknown" }} new=${newConversationIds.size} " +
            "updated=${updatedConversationIds.size} okSeg=$successfulSegments failSeg=$failedSegments"
    }
}
