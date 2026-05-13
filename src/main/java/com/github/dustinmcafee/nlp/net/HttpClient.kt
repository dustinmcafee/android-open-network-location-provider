package com.github.dustinmcafee.nlp.net

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTPS POST wrapper for the Apple WPS endpoint.
 *
 * Uses Android's built-in HttpURLConnection (JDK API, no external dep) so we
 * don't need to pull okhttp/OkHTTP3 into Phase 2's Soong deps.
 *
 * Headers default to the values verified against acheong08/apple-
 * corelocation-experiments lib/wloc.go on 2026-04-28 — they impersonate
 * macOS locationd. Apple's endpoint does not require auth.
 */
internal object HttpClient {

    private const val TAG = "NlpHttpClient"

    /** Headers iOS/macOS locationd sends. Order matters less than presence. */
    val APPLE_LOCATIONS_HEADERS: Map<String, String> = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "*/*",
        "Accept-Charset" to "utf-8",
        "Accept-Language" to "en-us",
        "User-Agent" to "locationd/2890.16.16 CFNetwork/1496.0.7 Darwin/23.5.0",
    )

    data class Response(val statusCode: Int, val body: ByteArray)

    /**
     * POST [body] to [url] with [headers]. Returns the response body bytes
     * regardless of status code; the caller decides what to do with non-2xx.
     * Throws [IOException] on transport failure.
     */
    @Throws(IOException::class)
    fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 8_000,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            for ((k, v) in headers) setRequestProperty(k, v)
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.use { it.readAllBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                Log.w(TAG, "POST $url returned HTTP $status (${responseBody.size} bytes)")
            }
            return Response(status, responseBody)
        } finally {
            conn.disconnect()
        }
    }
}
