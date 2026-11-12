package id.qrisgateway.listener

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class ListenerConfig(
    val webhookUrl: String,
    val heartbeatUrl: String,
    val secret: String,
    val deviceId: String,
    val allowedPackages: Set<String>
)

object ConfigStore {
    private const val PREF_NAME = "qris_listener_secure_prefs"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_HEARTBEAT_URL = "heartbeat_url"
    private const val KEY_SECRET = "secret"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PACKAGES = "allowed_packages"

    private const val DEFAULT_PACKAGES =
        "com.shopee.partner.id, com.gobiz.app, id.dana, com.bca.mybca.omni.android, id.co.bankmandiri.livinmerchant, id.co.bri.brimerchant"

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
        val defaultDevice = "android-${Build.MODEL.replace(" ", "-").lowercase()}"
        val deviceId = (p.getString(KEY_DEVICE_ID, defaultDevice) ?: defaultDevice).ifBlank { defaultDevice }
        val rawPackages = p.getString(KEY_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        return ListenerConfig(
            webhookUrl = webhookUrl.trim(),
            heartbeatUrl = heartbeatUrl.trim(),
            secret = secret.trim(),
            deviceId = deviceId.trim(),
            allowedPackages = parsePackages(rawPackages)
        )
    }

    fun save(
        context: Context,
        webhookUrl: String,
        secret: String,
        deviceId: String,
        rawPackages: String,
        explicitHeartbeatUrl: String? = null
    ) {
        val cleanWebhook = webhookUrl.trim()
        val cleanHeartbeat = explicitHeartbeatUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: deriveHeartbeatUrl(cleanWebhook)

        prefs(context).edit()
            .putString(KEY_WEBHOOK_URL, cleanWebhook)
            .putString(KEY_HEARTBEAT_URL, cleanHeartbeat)
            .putString(KEY_SECRET, secret.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_PACKAGES, rawPackages.trim())
            .apply()
    }

    fun importFromJson(context: Context, jsonText: String) {
        val obj = JSONObject(jsonText)
        val webhookUrl = obj.getString("webhookUrl")
        val heartbeatUrl = obj.optString("heartbeatUrl", "")
        val secret = obj.getString("secret")
        val current = load(context)
        val arr = obj.optJSONArray("recommendedPackages")
        val pkgs = if (arr != null && arr.length() > 0) {
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        } else {
            current.allowedPackages.joinToString(", ")
        }
        save(context, webhookUrl, secret, current.deviceId, pkgs, heartbeatUrl)
    }

    fun parsePackages(raw: String): Set<String> {
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun deriveHeartbeatUrl(webhookUrl: String): String {
        if (webhookUrl.isBlank()) return ""
        return if (webhookUrl.contains("/v1/webhook/mutation")) {
            webhookUrl.replace("/v1/webhook/mutation", "/v1/device/heartbeat")
        } else {
            ""
        }
    }
}
