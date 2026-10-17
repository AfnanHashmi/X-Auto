package com.clipcut.domain.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The key goes only in the Authorization header. A reasoning model can take well past the default read timeout. */
class OkHttpLlmHttp(base: OkHttpClient) : LlmHttp {
    private val client = base.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun post(url: String, key: String, json: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }
}
