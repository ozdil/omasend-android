package io.omarchy.omasend.account

import android.util.Log
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.omasend_core.AccountHttp
import uniffi.omasend_core.HttpReply
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * HTTP for the core's account protocol (callback interface, called on the core's calling
 * thread, which is always an IO thread). A dedicated client, separate from the LAN-only
 * OmaSendClient: HTTPS only, no cookies, no redirects, bounded time and size.
 * Status 0 tells the core "no network".
 */
class AccountHttpClient : AccountHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun request(method: String, url: String, body: String): HttpReply {
        if (!AccountLogic.isAllowedUrl(url)) {
            Log.w(TAG, "refusing non-https account request")
            return HttpReply(0u, "")
        }
        val m = method.uppercase()
        val req = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .method(m, if (m == "GET" || m == "HEAD") null else body.toRequestBody(JSON))
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad account request: ${e.message}")
            return HttpReply(0u, "")
        }
        return try {
            client.newCall(req).execute().use { resp ->
                // A plain-http redirect target is never followed (followRedirects = false).
                val text = readBounded(resp.body.byteStream())
                HttpReply(resp.code.coerceIn(0, 65535).toUShort(), text)
            }
        } catch (e: Exception) {
            Log.i(TAG, "account server unreachable: ${e.javaClass.simpleName}")
            HttpReply(0u, "")
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY) throw java.io.IOException("account reply too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "OmaAccountHttp"
        private const val MAX_BODY = 1 shl 20
        private val JSON = "application/json".toMediaType()
    }
}
