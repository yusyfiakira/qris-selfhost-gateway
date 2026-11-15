package id.qrisgateway.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PaymentNotificationService : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val pollScheduler = Executors.newSingleThreadScheduledExecutor()
    private var pollFuture: ScheduledFuture<*>? = null
    private val recentFingerprints = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        promoteForeground()
        HeartbeatWorker.schedule(applicationContext)
        startBackgroundPolling()
        DeliveryLogStore.append(applicationContext, "🟢 Listener terhubung dan siap memantau notifikasi.")
    }

    override fun onListenerDisconnected() {
        stopBackgroundPolling()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val body = if (bigText.length > text.length) bigText else text
        val combined = "$title $body".trim()

        if (combined.isBlank()) return

        val config = ConfigStore.load(applicationContext)
        if (config.allowedPackages.isNotEmpty() && !config.allowedPackages.contains(pkg.lowercase())) {
            return
        }

        if (!matchesTextFilter(combined, config)) {
            return
        }

        // Deduplikasi notifikasi identik yang terpicu ulang dalam jendela 3 detik
        val now = System.currentTimeMillis()
        val fingerprint = "$pkg|$combined"
        val lastSeen = recentFingerprints[fingerprint]
        if (lastSeen != null && (now - lastSeen) < 3000L) {
            return
        }
        recentFingerprints[fingerprint] = now
        if (recentFingerprints.size > 100) {
            recentFingerprints.entries.removeIf { (now - it.value) > 15_000L }
        }

        executor.execute {
            when (val res = WebhookClient.sendMutation(config, pkg, combined)) {
                is SendResult.Delivered -> {
                    if (res.outcome == "MATCHED_PAID" && !res.invoiceId.isNullOrBlank()) {
                        PaymentAlertHelper.notifyPaid(
                            context = applicationContext,
                            invoiceId = res.invoiceId,
                            orderId = res.invoiceId,
                            amount = res.amount ?: 0L,
                            ttsEnabled = config.ttsSoundboxEnabled
                        )
                    }
                    val badge = when (res.outcome) {
                        "MATCHED_PAID" -> "✅ LUNAS (${res.invoiceId ?: "-"} | Rp ${res.amount ?: 0})"
                        "DUPLICATE_PAID" -> "✅ SUDAH LUNAS (${res.invoiceId ?: "-"})"
                        else -> "⚪ DIABAIKAN ($pkg | Rp ${res.amount ?: 0})"
                    }
                    DeliveryLogStore.append(applicationContext, badge)
                    notifyBroadcastUpdate()
                }
                is SendResult.Rejected -> {
                    DeliveryLogStore.append(
                        applicationContext,
                        "❌ DITOLAK ${res.code} ($pkg): ${res.message}"
                    )
                    notifyBroadcastUpdate()
                }
                is SendResult.TransientFailure -> {
                    DeliveryLogStore.append(
                        applicationContext,
                        "🔁 JARINGAN PUTUS (${res.reason}), masuk antrean retry..."
                    )
                    RetryWorker.enqueue(applicationContext, pkg, combined)
                    notifyBroadcastUpdate()
                }
            }
        }
    }

    private fun matchesTextFilter(text: String, config: ListenerConfig): Boolean {
        val lower = text.lowercase()

        // Selalu tolak mutasi keluar bawaan
        val outgoingKeywords = listOf(
            "pembayaran ke",
            "transfer keluar",
            "pembayaran berhasil ke",
            "anda mengirim",
            "kamu mengirim",
            "berhasil bayar ke"
        )
        if (outgoingKeywords.any { lower.contains(it) }) return false

        // Terapkan exclude keywords custom bila ada
        if (config.excludeKeywords.any { lower.contains(it) }) return false

        return when (config.filterMode) {
            FilterMode.STANDARD_RP -> {
                lower.contains("rp") || lower.contains("idr")
            }
            FilterMode.ANY_NUMBER -> {
                Regex("\\d+").containsMatchIn(lower)
            }
            FilterMode.CUSTOM -> {
                val hasNumber = Regex("\\d+").containsMatchIn(lower)
                if (!hasNumber) return false
                if (config.includeKeywords.isEmpty()) return true
                config.includeKeywords.any { lower.contains(it) }
            }
        }
    }

    private fun notifyBroadcastUpdate() {
        sendBroadcast(Intent(ACTION_LOG_UPDATED).setPackage(packageName))
    }

    private fun promoteForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QRIS Listener Service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("QRIS Listener Aktif")
            .setContentText("Memantau notifikasi pembayaran masuk secara otomatis")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startBackgroundPolling() {
        stopBackgroundPolling()
        pollFuture = pollScheduler.scheduleWithFixedDelay({
            try {
                val cfg = ConfigStore.load(applicationContext)
                if (cfg.apiKey.isBlank() || cfg.webhookUrl.isBlank()) return@scheduleWithFixedDelay
                val data = WebhookClient.fetchDashboard(cfg, "", null, null)
                if (data.ok) {
                    PaymentAlertHelper.checkAndNotifyNewPaid(
                        context = applicationContext,
                        items = data.items,
                        ttsEnabled = cfg.ttsSoundboxEnabled
                    )
                    notifyBroadcastUpdate()
                }
            } catch (_: Exception) {
            }
        }, 10L, 10L, TimeUnit.SECONDS)
    }

    private fun stopBackgroundPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
    }

    override fun onDestroy() {
        stopBackgroundPolling()
        pollScheduler.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOG_UPDATED = "id.qrisgateway.listener.LOG_UPDATED"
        private const val CHANNEL_ID = "qris_listener_fg"
        private const val NOTIF_ID = 1001
    }
}
