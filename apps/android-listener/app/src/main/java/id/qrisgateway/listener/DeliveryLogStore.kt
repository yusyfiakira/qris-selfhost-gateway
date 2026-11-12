package id.qrisgateway.listener

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeliveryLogStore {
    private const val PREF_NAME = "qris_listener_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LINES = 40

    @Synchronized
    fun append(context: Context, entry: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = prefs.getString(KEY_LOGS, "") ?: ""
        val lines = (listOf("[$time] $entry") + current.lines().filter { it.isNotBlank() }).take(MAX_LINES)
        prefs.edit().putString(KEY_LOGS, lines.joinToString("\n")).apply()
    }

    @Synchronized
    fun readAll(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_LOGS, "") ?: ""
        return text.ifBlank { "Belum ada aktivitas notifikasi." }
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LOGS).apply()
    }
}
