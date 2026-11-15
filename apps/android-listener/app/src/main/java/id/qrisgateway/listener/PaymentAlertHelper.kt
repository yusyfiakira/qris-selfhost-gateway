package id.qrisgateway.listener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

object PaymentAlertHelper {
    private const val CHANNEL_ID = "qris_payment_paid_alert"
    private const val PREF_NAME = "qris_alerted_invoices"
    private const val KEY_IDS = "alerted_ids_csv"
    private const val MAX_TRACKED_IDS = 150

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idrFormat = NumberFormat.getInstance(Locale("id", "ID"))

    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    fun checkAndNotifyNewPaid(context: Context, items: List<InvoiceItem>, ttsEnabled: Boolean) {
        val nowMs = System.currentTimeMillis()
        for (item in items) {
            if (!item.status.equals("PAID", ignoreCase = true)) continue
            val paidAt = item.paidAt ?: continue
            val paidMs = runCatching { Instant.parse(paidAt).toEpochMilli() }.getOrNull() ?: continue
            // Hanya bunyikan notifikasi untuk transaksi yang lunas dalam 15 menit terakhir
            if (nowMs - paidMs > 15 * 60_000L) continue
            notifyPaid(
                context = context,
                invoiceId = item.id,
                orderId = item.externalOrderId.ifBlank { item.id },
                amount = item.totalAmount,
                ttsEnabled = ttsEnabled
            )
        }
    }

    @Synchronized
    fun notifyPaid(
        context: Context,
        invoiceId: String,
        orderId: String,
        amount: Long,
        ttsEnabled: Boolean
    ) {
        if (invoiceId.isBlank()) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_IDS, "").orEmpty()
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableList()

        if (existing.contains(invoiceId)) return

        existing.add(invoiceId)
        val trimmed = if (existing.size > MAX_TRACKED_IDS) {
            existing.takeLast(MAX_TRACKED_IDS)
        } else {
            existing
        }
        prefs.edit().putString(KEY_IDS, trimmed.joinToString(",")).apply()

        postNotification(appCtx, invoiceId, orderId, amount)
        if (ttsEnabled) {
            speakAmount(appCtx, amount)
        }
    }

    private fun postNotification(context: Context, invoiceId: String, orderId: String, amount: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pembayaran Masuk",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi saat pembayaran QRIS berhasil diterima"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            invoiceId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formatted = idrFormat.format(amount)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pembayaran Masuk +Rp $formatted")
            .setContentText("Pesanan $orderId telah lunas")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(invoiceId.hashCode(), notification)
    }

    private fun speakAmount(context: Context, amount: Long) {
        val phrase = "Pembayaran masuk, $amount rupiah"
        mainHandler.post {
            val current = tts
            if (current != null && ttsReady) {
                current.speak(phrase, TextToSpeech.QUEUE_ADD, null, "paid_${System.currentTimeMillis()}")
                return@post
            }
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    created?.language = Locale("id", "ID")
                    ttsReady = true
                    created?.speak(phrase, TextToSpeech.QUEUE_ADD, null, "paid_${System.currentTimeMillis()}")
                }
            }
            tts = created
        }
    }
}
