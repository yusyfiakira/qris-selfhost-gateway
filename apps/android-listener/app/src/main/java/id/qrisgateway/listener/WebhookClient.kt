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

data class QrisUpdateResult(
    val success: Boolean,
    val message: String,
    val merchantName: String? = null
)

data class InvoiceItem(
    val id: String,
    val externalOrderId: String,
    val totalAmount: Long,
    val status: String,
    val createdAt: String,
    val paidAt: String?,
    val callbackStatus: String,
    val callbackHttpCode: Int?
)

data class DashboardData(
    val ok: Boolean,
    val error: String? = null,
    val merchantName: String = "-",
    val merchantCity: String = "-",
    val nmid: String = "-",
    val qrisSource: String = "-",
    val totalPaidAmount: Long = 0L,
    val totalPaidCount: Int = 0,
    val todayPaidAmount: Long = 0L,
    val todayPaidCount: Int = 0,
    val callbackUrl: String = "",
    val defaultExpiryMinutes: Int = 15,
    val invoices: List<InvoiceItem> = emptyList()
)

data class QuickInvoiceResult(
    val success: Boolean,
    val message: String,
    val invoiceId: String = "",
    val totalAmount: Long = 0L,
    val qrString: String = "",
    val expiresAt: String = ""
)

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
            put("appVersion", "0.3.0")
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

    fun updateStaticQris(
        webhookUrl: String,
        apiKey: String,
        deviceId: String,
        staticQrisString: String
    ): QrisUpdateResult {
        val baseUrl = ConfigStore.deriveBaseUrl(webhookUrl)
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return QrisUpdateResult(false, "Webhook URL atau API Key belum diisi")
        }

        val payload = JSONObject().apply {
            put("staticQrisString", staticQrisString.trim())
            put("deviceId", deviceId)
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/config/qris")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .put(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(request).execute().use { res ->
                val rawBody = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!res.isSuccessful) {
                    val msg = json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${res.code}"
                    return QrisUpdateResult(false, msg)
                }
                val merchant = json?.optJSONObject("metadata")?.optString("merchantName", "")
                QrisUpdateResult(
                    success = true,
                    message = json?.optString("message", "QRIS diperbarui") ?: "QRIS diperbarui",
                    merchantName = merchant?.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            QrisUpdateResult(false, e.message ?: "Network error")
        }
    }

    fun fetchDashboard(
        config: ListenerConfig,
        statusFilter: String = "",
        minAmount: Long? = null,
        maxAmount: Long? = null
    ): DashboardData {
        val baseUrl = ConfigStore.deriveBaseUrl(config.webhookUrl)
        if (baseUrl.isBlank() || config.apiKey.isBlank()) {
            return DashboardData(ok = false, error = "Isi Webhook URL dan API Key di tab Setting untuk memuat data transaksi.")
        }

        val query = buildString {
            append("?limit=50")
            if (statusFilter.isNotBlank()) append("&status=$statusFilter")
            if (minAmount != null && minAmount > 0) append("&minAmount=$minAmount")
            if (maxAmount != null && maxAmount > 0) append("&maxAmount=$maxAmount")
        }

        return try {
            val invReq = Request.Builder()
                .url("$baseUrl/v1/invoices$query")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            val invBody = httpClient.newCall(invReq).execute().use { res ->
                if (!res.isSuccessful) {
                    return DashboardData(ok = false, error = "HTTP ${res.code} saat memuat transaksi")
                }
                res.body?.string().orEmpty()
            }
            val invJson = JSONObject(invBody)
            val summary = invJson.optJSONObject("summary")
            val arr = invJson.optJSONArray("invoices")
            val items = mutableListOf<InvoiceItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(
                        InvoiceItem(
                            id = o.optString("id"),
                            externalOrderId = o.optString("externalOrderId"),
                            totalAmount = o.optLong("totalAmount"),
                            status = o.optString("status"),
                            createdAt = o.optString("createdAt"),
                            paidAt = o.optString("paidAt", "").takeIf { it.isNotBlank() },
                            callbackStatus = o.optString("callbackStatus", "NONE"),
                            callbackHttpCode = if (o.isNull("callbackHttpCode")) null else o.optInt("callbackHttpCode")
                        )
                    )
                }
            }

            var merchantName = "-"
            var merchantCity = "-"
            var nmid = "-"
            var qrisSource = "-"
            runCatching {
                val qReq = Request.Builder()
                    .url("$baseUrl/v1/config/qris")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .get()
                    .build()
                httpClient.newCall(qReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val qJson = JSONObject(res.body?.string().orEmpty())
                        qrisSource = qJson.optString("source", "-")
                        val meta = qJson.optJSONObject("metadata")
                        if (meta != null) {
                            merchantName = meta.optString("merchantName", "-")
                            merchantCity = meta.optString("merchantCity", "-")
                            nmid = meta.optString("nmid", "-")
                        }
                    }
                }
            }

            var callbackUrl = config.defaultCallbackUrl
            var defaultExpiry = config.defaultExpiryMinutes
            runCatching {
                val cReq = Request.Builder()
                    .url("$baseUrl/v1/config/callback")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .get()
                    .build()
                httpClient.newCall(cReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val cJson = JSONObject(res.body?.string().orEmpty())
                        callbackUrl = cJson.optString("callbackUrl", callbackUrl)
                        defaultExpiry = cJson.optInt("defaultExpiryMinutes", defaultExpiry)
                    }
                }
            }

            DashboardData(
                ok = true,
                merchantName = merchantName,
                merchantCity = merchantCity,
                nmid = nmid,
                qrisSource = qrisSource,
                totalPaidAmount = summary?.optLong("totalPaidAmount", 0L) ?: 0L,
                totalPaidCount = summary?.optInt("totalPaidCount", 0) ?: 0,
                todayPaidAmount = summary?.optLong("todayPaidAmount", 0L) ?: 0L,
                todayPaidCount = summary?.optInt("todayPaidCount", 0) ?: 0,
                callbackUrl = callbackUrl,
                defaultExpiryMinutes = defaultExpiry,
                invoices = items
            )
        } catch (e: Exception) {
            DashboardData(ok = false, error = e.message ?: "Gagal terhubung ke server")
        }
    }

    fun saveCallbackConfig(
        config: ListenerConfig,
        callbackUrl: String,
        defaultExpiryMinutes: Int
    ): Pair<Boolean, String> {
        val baseUrl = ConfigStore.deriveBaseUrl(config.webhookUrl)
        if (baseUrl.isBlank() || config.apiKey.isBlank()) {
            return false to "Webhook URL atau API Key belum diisi"
        }

        val payload = JSONObject().apply {
            put("callbackUrl", callbackUrl.trim())
            put("defaultExpiryMinutes", defaultExpiryMinutes.coerceIn(1, 1440))
        }

        val req = Request.Builder()
            .url("$baseUrl/v1/config/callback")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .put(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (res.isSuccessful) {
                    true to "Webhook Callback & Durasi QRIS tersimpan di Worker"
                } else {
                    false to (json?.optString("message") ?: "HTTP ${res.code}")
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        }
    }

    fun testCallback(config: ListenerConfig, callbackUrl: String): Pair<Boolean, String> {
        val baseUrl = ConfigStore.deriveBaseUrl(config.webhookUrl)
        if (baseUrl.isBlank() || config.apiKey.isBlank()) {
            return false to "Webhook URL atau API Key belum diisi"
        }

        val payload = JSONObject().apply {
            put("callbackUrl", callbackUrl.trim())
        }
        val req = Request.Builder()
            .url("$baseUrl/v1/config/callback/test")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull()
                val ok = json?.optBoolean("success", false) ?: false
                val code = json?.optInt("httpCode", 0) ?: 0
                val err = json?.optString("error", "")
                if (ok) true to "Callback toko merespons HTTP $code"
                else false to "Callback gagal (HTTP $code ${err.orEmpty()})"
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        }
    }

    fun retryInvoiceCallback(config: ListenerConfig, invoiceId: String): Pair<Boolean, String> {
        val baseUrl = ConfigStore.deriveBaseUrl(config.webhookUrl)
        if (baseUrl.isBlank() || config.apiKey.isBlank()) {
            return false to "API Key belum diisi"
        }
        val req = Request.Builder()
            .url("$baseUrl/v1/invoices/$invoiceId/retry-callback")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post("{}".toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull()
                val ok = json?.optBoolean("success", false) ?: false
                val status = json?.optString("callbackStatus", "FAILED") ?: "FAILED"
                ok to "Status callback: $status"
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        }
    }

    fun createQuickInvoice(
        config: ListenerConfig,
        baseAmount: Long,
        expiryMinutes: Int
    ): QuickInvoiceResult {
        val baseUrl = ConfigStore.deriveBaseUrl(config.webhookUrl)
        if (baseUrl.isBlank() || config.apiKey.isBlank()) {
            return QuickInvoiceResult(false, "Isi Webhook URL & API Key di tab Setting")
        }
        val payload = JSONObject().apply {
            put("externalOrderId", "POS-${System.currentTimeMillis()}")
            put("baseAmount", baseAmount)
            put("expiryMinutes", expiryMinutes.coerceIn(1, 1440))
        }
        val req = Request.Builder()
            .url("$baseUrl/v1/invoices")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull()
                if (!res.isSuccessful || json?.optBoolean("success") != true) {
                    return QuickInvoiceResult(false, json?.optString("message") ?: "HTTP ${res.code}")
                }
                val d = json.getJSONObject("data")
                QuickInvoiceResult(
                    success = true,
                    message = "QRIS siap discan",
                    invoiceId = d.optString("id"),
                    totalAmount = d.optLong("totalAmount"),
                    qrString = d.optString("qrString"),
                    expiresAt = d.optString("expiresAt")
                )
            }
        } catch (e: Exception) {
            QuickInvoiceResult(false, e.message ?: "Network error")
        }
    }
}
