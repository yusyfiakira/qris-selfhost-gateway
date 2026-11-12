package id.qrisgateway.listener

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SendResult {
    data class Delivered(val status: String, val outcome: String, val invoiceId: String?, val amount: Long?) : SendResult()
    data class Rejected(val code: Int, val message: String) : SendResult()
    data class TransientFailure(val reason: String) : SendResult()
}

object WebhookClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun sendMutation(
        config: ListenerConfig,
        packageName: String,
        messageText: String
    ): SendResult {
        if (config.webhookUrl.isBlank() || config.secret.isBlank()) {
            return SendResult.Rejected(0, "Webhook URL atau Secret belum diatur")
        }

        val payload = JSONObject().apply {
            put("deviceId", config.deviceId)
            put("packageName", packageName)
            put("message", messageText)
        }

        val request = Request.Builder()
            .url(config.webhookUrl)
            .addHeader("x-gateway-secret", config.secret)
            .addHeader("x-device-id", config.deviceId)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(request).execute().use { res ->
                val rawBody = res.body?.string().orEmpty()
                if (res.code == 401 || res.code == 403 || res.code == 400) {
                    return SendResult.Rejected(res.code, rawBody.ifBlank { "HTTP ${res.code}" })
                }
                if (!res.isSuccessful) {
                    return SendResult.TransientFailure("HTTP ${res.code}")
                }
                val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
                SendResult.Delivered(
                    status = parsed?.optString("status", "ok") ?: "ok",
                    outcome = parsed?.optString("outcome", "OK") ?: "OK",
                    invoiceId = parsed?.optString("invoiceId", "")?.takeIf { it.isNotBlank() },
                    amount = parsed?.optLong("amount", -1L)?.takeIf { it > 0 }
                )
            }
        } catch (e: Exception) {
            SendResult.TransientFailure(e.message ?: "Network error")
        }
    }

    fun sendHeartbeat(
        config: ListenerConfig,
        batteryPct: Int,
        networkType: String
    ): Boolean {
        if (config.heartbeatUrl.isBlank() || config.secret.isBlank()) return false

        val payload = JSONObject().apply {
            put("deviceId", config.deviceId)
            put("appVersion", "0.1.0")
            put("batteryPct", batteryPct)
            put("networkType", networkType)
        }

        val request = Request.Builder()
            .url(config.heartbeatUrl)
            .addHeader("x-gateway-secret", config.secret)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
