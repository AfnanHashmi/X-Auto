package com.clipcut.data.x

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class TweetResult(val id: String, val text: String)
data class XUser(val id: String, val username: String)

class XApiException(message: String) : IOException(message)

private const val UPLOAD_BASE = "https://upload.twitter.com/1.1/media/upload.json"
private const val API_BASE = "https://api.twitter.com/2"
private const val CHUNK_SIZE = 4 * 1024 * 1024 // 4MB, under X's 5MB-per-chunk recommendation.
private const val STATUS_POLL_MS = 1000L
private const val MAX_STATUS_POLLS = 60

/** One X account's worth of API access -- construct one per account (media, main) with its own [signer]. */
class XApiClient(
    private val okHttpClient: OkHttpClient,
    private val signer: OAuth1aSigner,
) {
    /** Chunked video upload (INIT/APPEND/FINALIZE/STATUS). Returns the resulting media_id_string. */
    suspend fun uploadVideo(file: File, mimeType: String = "video/mp4"): String = withContext(Dispatchers.IO) {
        val mediaId = init(file.length(), mimeType)
        append(mediaId, file)
        finalize(mediaId)
        awaitProcessed(mediaId)
        mediaId
    }

    private fun init(totalBytes: Long, mimeType: String): String {
        val params = mapOf(
            "command" to "INIT",
            "total_bytes" to totalBytes.toString(),
            "media_type" to mimeType,
            "media_category" to "tweet_video",
        )
        val body = formBody(params)
        val response = execute(signedFormRequest("POST", UPLOAD_BASE, params, body))
        return JSONObject(response).getString("media_id_string")
    }

    private fun append(mediaId: String, file: File) {
        file.inputStream().use { input ->
            var segmentIndex = 0
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = buffer.copyOf(read)
                // OAuth 1.0a (RFC 5849 3.4.1.3.1) only folds body params into the signature base
                // string for application/x-www-form-urlencoded bodies -- for multipart/form-data
                // they're excluded, so X would sign without them and reject ours as mismatched.
                // Query params are always signed, so command/media_id/segment_index go there and
                // the multipart body carries only the binary chunk.
                val signedParams = mapOf(
                    "command" to "APPEND",
                    "media_id" to mediaId,
                    "segment_index" to segmentIndex.toString(),
                )
                val url = UPLOAD_BASE.toHttpUrl().newBuilder()
                    .addQueryParameter("command", "APPEND")
                    .addQueryParameter("media_id", mediaId)
                    .addQueryParameter("segment_index", segmentIndex.toString())
                    .build()
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("media", "chunk", chunk.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                execute(
                    Request.Builder().url(url)
                        .header("Authorization", signer.authorizationHeader("POST", UPLOAD_BASE, signedParams))
                        .post(body)
                        .build(),
                )
                segmentIndex++
            }
        }
    }

    private fun finalize(mediaId: String) {
        val params = mapOf("command" to "FINALIZE", "media_id" to mediaId)
        execute(signedFormRequest("POST", UPLOAD_BASE, params, formBody(params)))
    }

    private suspend fun awaitProcessed(mediaId: String) {
        // Bounded: a stuck "pending"/"in_progress" state would otherwise poll X forever.
        repeat(MAX_STATUS_POLLS) {
            val params = mapOf("command" to "STATUS", "media_id" to mediaId)
            val url = UPLOAD_BASE.toHttpUrl().newBuilder()
                .addQueryParameter("command", "STATUS")
                .addQueryParameter("media_id", mediaId)
                .build()
            val response = execute(
                Request.Builder().url(url)
                    .header("Authorization", signer.authorizationHeader("GET", UPLOAD_BASE, params))
                    .get()
                    .build(),
            )
            val processingInfo = JSONObject(response).optJSONObject("processing_info") ?: return // images: no processing step.
            when (processingInfo.getString("state")) {
                "succeeded" -> return
                "failed" -> throw XApiException("Media processing failed: $processingInfo")
                else -> kotlinx.coroutines.delay(
                    (processingInfo.optInt("check_after_secs", 1) * 1000L).coerceAtLeast(STATUS_POLL_MS),
                )
            }
        }
        throw XApiException("Media $mediaId still processing after $MAX_STATUS_POLLS status checks")
    }

    /** Posts a tweet, optionally attaching uploaded media and/or quote-tweeting another tweet. */
    suspend fun createTweet(text: String, mediaIds: List<String>? = null, quoteTweetId: String? = null): TweetResult =
        withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("text", text)
                if (!mediaIds.isNullOrEmpty()) {
                    // Android's org.json JSONObject.put() does NOT auto-convert a Kotlin List to
                    // a JSON array -- it stores the object as-is and serialization falls back to
                    // List.toString(), sending the string "[id]" instead of the array [id]. X
                    // rejected exactly that ("string found, array expected") on the first real
                    // upload. JSONArray(Collection) builds a genuine JSON array.
                    put("media", JSONObject().put("media_ids", JSONArray(mediaIds)))
                }
                if (quoteTweetId != null) put("quote_tweet_id", quoteTweetId)
            }
            // A JSON body isn't form-urlencoded, so it's excluded from the OAuth1.0a signature --
            // only oauth_* params are signed here (no query string on this endpoint).
            val url = "$API_BASE/tweets"
            val request = Request.Builder().url(url)
                .header("Authorization", signer.authorizationHeader("POST", url))
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = JSONObject(execute(request)).getJSONObject("data")
            TweetResult(id = response.getString("id"), text = response.getString("text"))
        }

    /** The authenticated user's own id + @handle (needed for [getRecentTweets] and attribution). */
    suspend fun getMe(): XUser = withContext(Dispatchers.IO) {
        val url = "$API_BASE/users/me"
        val request = Request.Builder().url(url)
            .header("Authorization", signer.authorizationHeader("GET", url))
            .get()
            .build()
        val data = JSONObject(execute(request)).getJSONObject("data")
        XUser(id = data.getString("id"), username = data.getString("username"))
    }

    suspend fun getRecentTweets(userId: String, maxResults: Int = 5): List<TweetResult> = withContext(Dispatchers.IO) {
        val baseUrl = "$API_BASE/users/$userId/tweets"
        val signedParams = mapOf("max_results" to maxResults.toString())
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("max_results", maxResults.toString()).build()
        val request = Request.Builder().url(url)
            .header("Authorization", signer.authorizationHeader("GET", baseUrl, signedParams))
            .get()
            .build()
        val data = JSONObject(execute(request)).optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).map { i ->
            val tweet = data.getJSONObject(i)
            TweetResult(id = tweet.getString("id"), text = tweet.getString("text"))
        }
    }

    private fun formBody(params: Map<String, String>) =
        params.entries.fold(okhttp3.FormBody.Builder()) { b, (k, v) -> b.add(k, v) }.build()

    private fun signedFormRequest(method: String, url: String, signedParams: Map<String, String>, body: okhttp3.RequestBody) =
        Request.Builder().url(url)
            .header("Authorization", signer.authorizationHeader(method, url, signedParams))
            .method(method, body)
            .build()

    private fun execute(request: Request): String = okHttpClient.newCall(request).execute().use { response ->
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw XApiException("${request.method} ${request.url} failed: HTTP ${response.code}: $bodyText")
        bodyText
    }
}
