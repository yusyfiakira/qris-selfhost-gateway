package id.qrisgateway.listener

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var tvStatusBadge: TextView
    private lateinit var etWebhookUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etPackages: EditText
    private lateinit var tvDeliveryLogs: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etSecret = findViewById(R.id.etSecret)
        etDeviceId = findViewById(R.id.etDeviceId)
        etPackages = findViewById(R.id.etPackages)
        tvDeliveryLogs = findViewById(R.id.tvDeliveryLogs)

        loadFormFromConfig()
        requestPostNotificationsIfNeeded()

        findViewById<Button>(R.id.btnGrantNotification).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnDisableBatteryOpt).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ConfigStore.save(
                this,
                etWebhookUrl.text.toString(),
                etSecret.text.toString(),
                etDeviceId.text.toString(),
                etPackages.text.toString()
            )
            HeartbeatWorker.schedule(this)
            Toast.makeText(this, "Konfigurasi tersimpan terenkripsi", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnImportJson).setOnClickListener {
            showImportJsonDialog()
        }

        findViewById<Button>(R.id.btnTestPing).setOnClickListener {
            ConfigStore.save(
                this,
                etWebhookUrl.text.toString(),
                etSecret.text.toString(),
                etDeviceId.text.toString(),
                etPackages.text.toString()
            )
            val cfg = ConfigStore.load(this)
            ioExecutor.execute {
                val hbOk = WebhookClient.sendHeartbeat(
                    cfg,
                    HeartbeatWorker.readBatteryPct(this),
                    HeartbeatWorker.readNetworkType(this)
                )
                val res = WebhookClient.sendMutation(cfg, packageName, "Tes koneksi Rp 99.999")
                val msg = when (res) {
                    is SendResult.Delivered -> "🧪 TES OK (${res.outcome}) | Heartbeat: ${if (hbOk) "OK" else "Skip"}"
                    is SendResult.Rejected -> "🧪 TES DITOLAK (${res.code}): ${res.message}"
                    is SendResult.TransientFailure -> "🧪 TES GAGAL: ${res.reason}"
                }
                DeliveryLogStore.append(this, msg)
                runOnUiThread { refreshLogs() }
            }
        }

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            DeliveryLogStore.clear(this)
            refreshLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PaymentNotificationService.ACTION_LOG_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        refreshStatus()
        refreshLogs()
    }

    override fun onPause() {
        unregisterReceiver(logReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadFormFromConfig() {
        val cfg = ConfigStore.load(this)
        etWebhookUrl.setText(cfg.webhookUrl)
        etSecret.setText(cfg.secret)
        etDeviceId.setText(cfg.deviceId)
        etPackages.setText(cfg.allowedPackages.joinToString(", "))
    }

    private fun refreshLogs() {
        tvDeliveryLogs.text = DeliveryLogStore.readAll(this)
    }

    private fun refreshStatus() {
        val notifOk = isNotificationServiceEnabled()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        tvStatusBadge.text = buildString {
            append(if (notifOk) "🟢 Akses Notif: AKTIF" else "🔴 Akses Notif: BELUM AKTIF")
            append("   |   ")
            append(if (batteryOk) "🟢 Baterai: BEBAS" else "🟡 Baterai: Dibatasi")
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, PaymentNotificationService::class.java).flattenToString()
        return flat.split(":").any { it.equals(cn, ignoreCase = true) }
    }

    private fun showImportJsonDialog() {
        val input = EditText(this).apply {
            hint = "{\"webhookUrl\":\"...\",\"secret\":\"...\"}"
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Tempel JSON dari /v1/setup/android-config")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                runCatching {
                    ConfigStore.importFromJson(this, input.text.toString())
                    loadFormFromConfig()
                    HeartbeatWorker.schedule(this)
                    Toast.makeText(this, "Config JSON berhasil di-import!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Format JSON tidak valid: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
