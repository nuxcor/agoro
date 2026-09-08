package com.agoro.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The two calls that carry progress between a household's TVs.
 *
 * Deliberately thin. It fetches a blob and puts one back; the merge happens in
 * [ProgressSync], on the box, because merging needs both histories in hand and
 * the box is where they both are. A server that merges is a server that can
 * lose your history somewhere you cannot debug.
 *
 * Every failure is null or false and never an exception: sync is a
 * convenience, and a household that is offline, or whose worker is down, must
 * carry on watching exactly as it did before any of this existed.
 */
class ProgressSyncClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val key: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(accountId: String) =
        baseUrl.trimEnd('/') + "/v1/progress/" + accountId

    /** What the other TV last stored, or null when nothing could be read. */
    suspend fun pull(accountId: String): ProgressPayload? = withContext(Dispatchers.IO) {
        catchingExceptCancellation {
            val request = Request.Builder()
                .url(url(accountId))
                .header("Authorization", "Bearer $key")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.decodeFromString<ProgressPayload>(resp.body!!.string())
            }
        }.getOrNull()
    }

    /** Stores this box's view. False when it did not land. */
    suspend fun push(accountId: String, payload: ProgressPayload): Boolean =
        withContext(Dispatchers.IO) {
            catchingExceptCancellation {
                val body = json.encodeToString(ProgressPayload.serializer(), payload)
                    .toRequestBody(JSON_MEDIA)
                val request = Request.Builder()
                    .url(url(accountId))
                    .header("Authorization", "Bearer $key")
                    .put(body)
                    .build()
                http.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /**
     * [runCatching], minus the one throwable it must never eat — the same rule
     * ContentRepository keeps, and for the same reason: swallowing a
     * cancellation lets a coroutine carry on past being told to stop.
     */
    private inline fun <T> catchingExceptCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
