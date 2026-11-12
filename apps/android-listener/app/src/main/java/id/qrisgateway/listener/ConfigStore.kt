package id.qrisgateway.listener

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

enum class FilterMode(val id: String) {
    STANDARD_RP("standard_rp"),
    ANY_NUMBER("any_number"),
    CUSTOM("custom");

    companion object {
        fun fromId(raw: String?): FilterMode {
            return entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD_RP
        }
    }
}

data class ListenerConfig(
    val webhookUrl: String,
    val heartbeatUrl: String,
    val secret: String,
    val apiKey: String,
    val defaultCallbackUrl: String,
    val defaultExpiryMinutes: Int,
    val deviceId: String,
    val allowedPackages: Set<String>,
    val filterMode: FilterMode,
    val includeKeywords: List<String>,
    val excludeKeywords: List<String>
)

object ConfigStore {
    private const val PREF_NAME = "qris_listener_secure_prefs"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_HEARTBEAT_URL = "heartbeat_url"
    private const val KEY_SECRET = "secret"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_CALLBACK_URL = "default_callback_url"
    private const val KEY_DEFAULT_EXPIRY = "default_expiry_minutes"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PACKAGES = "allowed_packages"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_INCLUDE_KW = "include_keywords"
    private const val KEY_EXCLUDE_KW = "exclude_keywords"

    private const val DEFAULT_PACKAGES =
        "com.shopeepay.merchant.id, com.gojek.gopaymerchant, com.gojek.resto, id.dana, com.bca.merchantbca, id.co.bankmandiri.livinmerchant, id.co.bri.merchant"
    private const val DEFAULT_EXCLUDE_KW = "promo, cashback, diskon, voucher"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(context: Context): ListenerConfig {
        val p = prefs(context)
        val webhookUrl = p.getString(KEY_WEBHOOK_URL, "") ?: ""
        val heartbeatUrl = p.getString(KEY_HEARTBEAT_URL, deriveHeartbeatUrl(webhookUrl)) ?: ""
        val secret = p.getString(KEY_SECRET, "") ?: ""
        val apiKey = p.getString(KEY_API_KEY, "") ?: ""
        val defaultCallbackUrl = p.getString(KEY_CALLBACK_URL, "") ?: ""
        val defaultExpiryMinutes = p.getInt(KEY_DEFAULT_EXPIRY, 15).coerceIn(1, 1440)
        val defaultDevice = "android-${Build.MODEL.replace(" ", "-").lowercase()}"
        val deviceId = (p.getString(KEY_DEVICE_ID, defaultDevice) ?: defaultDevice).ifBlank { defaultDevice }
        val rawPackages = p.getString(KEY_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        val filterMode = FilterMode.fromId(p.getString(KEY_FILTER_MODE, FilterMode.STANDARD_RP.id))
        val rawInclude = p.getString(KEY_INCLUDE_KW, "") ?: ""
        val rawExclude = p.getString(KEY_EXCLUDE_KW, DEFAULT_EXCLUDE_KW) ?: DEFAULT_EXCLUDE_KW

        return ListenerConfig(
            webhookUrl = webhookUrl.trim(),
            heartbeatUrl = heartbeatUrl.trim(),
            secret = secret.trim(),
            apiKey = apiKey.trim(),
            defaultCallbackUrl = defaultCallbackUrl.trim(),
            defaultExpiryMinutes = defaultExpiryMinutes,
            deviceId = deviceId.trim(),
            allowedPackages = parseCsvSet(rawPackages),
            filterMode = filterMode,
            includeKeywords = parseCsvList(rawInclude),
            excludeKeywords = parseCsvList(rawExclude)
        )
    }

    fun save(
        context: Context,
        webhookUrl: String,
        secret: String,
        deviceId: String,
        rawPackages: String,
        filterMode: FilterMode = FilterMode.STANDARD_RP,
        rawIncludeKeywords: String = "",
        rawExcludeKeywords: String = DEFAULT_EXCLUDE_KW,
        apiKey: String? = null,
        defaultCallbackUrl: String? = null,
        defaultExpiryMinutes: Int? = null,
        explicitHeartbeatUrl: String? = null
    ) {
        val cleanWebhook = webhookUrl.trim()
        val cleanHeartbeat = explicitHeartbeatUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: deriveHeartbeatUrl(cleanWebhook)

        val editor = prefs(context).edit()
            .putString(KEY_WEBHOOK_URL, cleanWebhook)
            .putString(KEY_HEARTBEAT_URL, cleanHeartbeat)
            .putString(KEY_SECRET, secret.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_PACKAGES, rawPackages.trim())
            .putString(KEY_FILTER_MODE, filterMode.id)
            .putString(KEY_INCLUDE_KW, rawIncludeKeywords.trim())
            .putString(KEY_EXCLUDE_KW, rawExcludeKeywords.trim())

        if (apiKey != null) editor.putString(KEY_API_KEY, apiKey.trim())
        if (defaultCallbackUrl != null) editor.putString(KEY_CALLBACK_URL, defaultCallbackUrl.trim())
        if (defaultExpiryMinutes != null) {
            editor.putInt(KEY_DEFAULT_EXPIRY, defaultExpiryMinutes.coerceIn(1, 1440))
        }
        editor.apply()
    }

    fun exportToJson(context: Context, includeSecret: Boolean): String {
        val cfg = load(context)
        val obj = JSONObject().apply {
            put("version", 3)
            put("webhookUrl", cfg.webhookUrl)
            put("heartbeatUrl", cfg.heartbeatUrl)
            put("deviceId", cfg.deviceId)
            put("defaultCallbackUrl", cfg.defaultCallbackUrl)
            put("defaultExpiryMinutes", cfg.defaultExpiryMinutes)
            put("allowedPackages", JSONArray(cfg.allowedPackages.toList()))
            put("filterMode", cfg.filterMode.id)
            put("includeKeywords", JSONArray(cfg.includeKeywords))
            put("excludeKeywords", JSONArray(cfg.excludeKeywords))
            if (includeSecret) {
                put("secret", cfg.secret)
                if (cfg.apiKey.isNotBlank()) put("apiKey", cfg.apiKey)
            }
        }
        return obj.toString(2)
    }

    fun importFromJson(context: Context, jsonText: String) {
        val obj = JSONObject(jsonText)
        val current = load(context)

        val webhookUrl = obj.optString("webhookUrl", current.webhookUrl)
        val heartbeatUrl = obj.optString("heartbeatUrl", "")
        val secret = obj.optString("secret", "").ifBlank { current.secret }
        val apiKey = obj.optString("apiKey", "").ifBlank { current.apiKey }
        val defaultCallbackUrl = obj.optString("defaultCallbackUrl", current.defaultCallbackUrl)
        val defaultExpiry = obj.optInt("defaultExpiryMinutes", current.defaultExpiryMinutes)
        val deviceId = obj.optString("deviceId", "").ifBlank { current.deviceId }

        val pkgArr = obj.optJSONArray("allowedPackages") ?: obj.optJSONArray("recommendedPackages")
        val rawPackages = if (pkgArr != null) {
            (0 until pkgArr.length()).joinToString(", ") { pkgArr.getString(it) }
        } else {
            obj.optString("allowedPackagesCsv", current.allowedPackages.joinToString(", "))
        }

        val filterMode = FilterMode.fromId(obj.optString("filterMode", current.filterMode.id))

        val incArr = obj.optJSONArray("includeKeywords")
        val rawInclude = if (incArr != null) {
            (0 until incArr.length()).joinToString(", ") { incArr.getString(it) }
        } else {
            obj.optString("includeKeywordsCsv", current.includeKeywords.joinToString(", "))
        }

        val excArr = obj.optJSONArray("excludeKeywords")
        val rawExclude = if (excArr != null) {
            (0 until excArr.length()).joinToString(", ") { excArr.getString(it) }
        } else {
            obj.optString("excludeKeywordsCsv", current.excludeKeywords.joinToString(", "))
        }

        save(
            context = context,
            webhookUrl = webhookUrl,
            secret = secret,
            deviceId = deviceId,
            rawPackages = rawPackages,
            filterMode = filterMode,
            rawIncludeKeywords = rawInclude,
            rawExcludeKeywords = rawExclude,
            apiKey = apiKey,
            defaultCallbackUrl = defaultCallbackUrl,
            defaultExpiryMinutes = defaultExpiry,
            explicitHeartbeatUrl = heartbeatUrl
        )
    }

    fun deriveBaseUrl(webhookUrl: String): String {
        val clean = webhookUrl.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        return if (clean.contains("/v1/webhook/mutation")) {
            clean.substringBefore("/v1/webhook/mutation").trimEnd('/')
        } else {
            clean
        }
    }

    private fun parseCsvSet(raw: String): Set<String> {
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun parseCsvList(raw: String): List<String> {
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    private fun deriveHeartbeatUrl(webhookUrl: String): String {
        val base = deriveBaseUrl(webhookUrl)
        return if (base.isBlank()) "" else "$base/v1/device/heartbeat"
    }
}
