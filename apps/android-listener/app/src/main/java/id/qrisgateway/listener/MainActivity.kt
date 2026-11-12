package id.qrisgateway.listener

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var rgFilterMode: RadioGroup
    private lateinit var rbFilterStandard: RadioButton
    private lateinit var rbFilterAnyNumber: RadioButton
    private lateinit var rbFilterCustom: RadioButton
    private lateinit var etIncludeKeywords: EditText
    private lateinit var etExcludeKeywords: EditText
    private lateinit var tvDeliveryLogs: TextView

    private var pendingExportIncludeSecret = false
    private var qrisInputFieldRef: EditText? = null
    private var qrisPreviewTextRef: TextView? = null

    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val json = ConfigStore.exportToJson(this, pendingExportIncludeSecret)
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Config berhasil diekspor!", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Gagal ekspor: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("File kosong")
                ConfigStore.importFromJson(this, text)
                loadFormFromConfig()
                HeartbeatWorker.schedule(this)
                Toast.makeText(this, "Config dari file berhasil di-import!", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Gagal import file: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }

    private val pickQrisImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            ioExecutor.execute {
                val result = runCatching {
                    val raw = QrisDecoder.decodeFromUri(this, uri)
                    val preview = QrisDecoder.inspectQris(raw)
                    preview
                }
                runOnUiThread {
                    result.onSuccess { preview ->
                        qrisInputFieldRef?.setText(preview.rawString)
                        qrisPreviewTextRef?.text = buildString {
                            append("Merchant: ${preview.merchantName}\n")
                            append("CRC16: ${if (preview.isValidCrc) "✅ Valid" else "❌ Tidak Valid"} | ")
                            append("Tipe: ${if (preview.isStatic) "✅ Statis (11)" else "⚠️ Bukan Statis"}")
                        }
                    }.onFailure { err ->
                        Toast.makeText(this, "Gagal baca QR gambar: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

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
        rgFilterMode = findViewById(R.id.rgFilterMode)
        rbFilterStandard = findViewById(R.id.rbFilterStandard)
        rbFilterAnyNumber = findViewById(R.id.rbFilterAnyNumber)
        rbFilterCustom = findViewById(R.id.rbFilterCustom)
        etIncludeKeywords = findViewById(R.id.etIncludeKeywords)
        etExcludeKeywords = findViewById(R.id.etExcludeKeywords)
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

        findViewById<Button>(R.id.btnPickApps).setOnClickListener {
            showAppPickerDialog()
        }

        findViewById<Button>(R.id.btnClearApps).setOnClickListener {
            etPackages.setText("")
            Toast.makeText(this, "Filter app dikosongkan (pantau semua app sesuai filter teks)", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveFormToConfig()
            HeartbeatWorker.schedule(this)
            Toast.makeText(this, "Konfigurasi tersimpan terenkripsi", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnExportConfig).setOnClickListener {
            saveFormToConfig()
            showExportDialog()
        }

        findViewById<Button>(R.id.btnImportJson).setOnClickListener {
            showImportChoiceDialog()
        }

        findViewById<Button>(R.id.btnChangeQris).setOnClickListener {
            saveFormToConfig()
            showChangeQrisDialog()
        }

        findViewById<Button>(R.id.btnTestPing).setOnClickListener {
            saveFormToConfig()
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

    private fun selectedFilterMode(): FilterMode {
        return when (rgFilterMode.checkedRadioButtonId) {
            R.id.rbFilterAnyNumber -> FilterMode.ANY_NUMBER
            R.id.rbFilterCustom -> FilterMode.CUSTOM
            else -> FilterMode.STANDARD_RP
        }
    }

    private fun saveFormToConfig() {
        ConfigStore.save(
            context = this,
            webhookUrl = etWebhookUrl.text.toString(),
            secret = etSecret.text.toString(),
            deviceId = etDeviceId.text.toString(),
            rawPackages = etPackages.text.toString(),
            filterMode = selectedFilterMode(),
            rawIncludeKeywords = etIncludeKeywords.text.toString(),
            rawExcludeKeywords = etExcludeKeywords.text.toString()
        )
    }

    private fun loadFormFromConfig() {
        val cfg = ConfigStore.load(this)
        etWebhookUrl.setText(cfg.webhookUrl)
        etSecret.setText(cfg.secret)
        etDeviceId.setText(cfg.deviceId)
        etPackages.setText(cfg.allowedPackages.joinToString(", "))
        when (cfg.filterMode) {
            FilterMode.STANDARD_RP -> rbFilterStandard.isChecked = true
            FilterMode.ANY_NUMBER -> rbFilterAnyNumber.isChecked = true
            FilterMode.CUSTOM -> rbFilterCustom.isChecked = true
        }
        etIncludeKeywords.setText(cfg.includeKeywords.joinToString(", "))
        etExcludeKeywords.setText(cfg.excludeKeywords.joinToString(", "))
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

    private fun showAppPickerDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val appsByPkg = linkedMapOf<String, String>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue
            val label = ri.loadLabel(pm).toString().trim().ifBlank { pkg }
            appsByPkg.putIfAbsent(pkg, label)
        }

        val sorted = appsByPkg.entries.sortedBy { it.value.lowercase() }
        if (sorted.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi terdeteksi", Toast.LENGTH_SHORT).show()
            return
        }

        val currentSelected = etPackages.text.toString()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        val labels = sorted.map { "${it.value}\n(${it.key})" }.toTypedArray()
        val packages = sorted.map { it.key }
        val checked = BooleanArray(sorted.size) { idx -> currentSelected.contains(packages[idx].lowercase()) }

        AlertDialog.Builder(this)
            .setTitle("Pilih Aplikasi Merchant")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Terapkan") { _, _ ->
                val chosen = packages.filterIndexed { idx, _ -> checked[idx] }
                etPackages.setText(chosen.joinToString(", "))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showExportDialog() {
        val cbSecret = CheckBox(this).apply {
            text = "Sertakan Webhook Secret (Rahasia — jangan bagikan ke orang lain)"
            isChecked = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(cbSecret)
        }

        AlertDialog.Builder(this)
            .setTitle("Export Konfigurasi")
            .setView(container)
            .setPositiveButton("Simpan File .json") { _, _ ->
                pendingExportIncludeSecret = cbSecret.isChecked
                exportFileLauncher.launch("qris-listener-config.json")
            }
            .setNeutralButton("Salin ke Clipboard") { _, _ ->
                val json = ConfigStore.exportToJson(this, cbSecret.isChecked)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qris-listener-config", json))
                Toast.makeText(this, "Config JSON disalin ke clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showImportChoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Import Konfigurasi")
            .setItems(arrayOf("Pilih File .json", "Tempel Teks JSON")) { _, which ->
                if (which == 0) {
                    importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                } else {
                    showImportJsonTextDialog()
                }
            }
            .show()
    }

    private fun showImportJsonTextDialog() {
        val input = EditText(this).apply {
            hint = "{\"webhookUrl\":\"...\",\"secret\":\"...\"}"
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Tempel JSON Config")
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

    private fun showChangeQrisDialog() {
        val etApiKey = EditText(this).apply {
            hint = "Masukkan API_KEY Admin (tidak disimpan di HP)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etQrisRaw = EditText(this).apply {
            hint = "String QRIS Statis (000201...) atau pilih foto QRIS"
            minLines = 3
        }
        val tvPreview = TextView(this).apply {
            text = "Pilih gambar QRIS dari Galeri atau tempel string EMVCo."
            textSize = 12f
        }
        val btnPickImg = Button(this).apply {
            text = "Pilih Gambar QRIS dari Galeri"
            setOnClickListener {
                qrisInputFieldRef = etQrisRaw
                qrisPreviewTextRef = tvPreview
                pickQrisImageLauncher.launch("image/*")
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(etApiKey)
            addView(btnPickImg)
            addView(etQrisRaw)
            addView(tvPreview)
        }

        AlertDialog.Builder(this)
            .setTitle("Admin: Ganti Kode QRIS Merchant")
            .setView(container)
            .setPositiveButton("Update ke Server") { _, _ ->
                val apiKey = etApiKey.text.toString().trim()
                val rawQris = etQrisRaw.text.toString().trim()
                val cfg = ConfigStore.load(this)
                ioExecutor.execute {
                    val res = WebhookClient.updateStaticQris(
                        webhookUrl = cfg.webhookUrl,
                        apiKey = apiKey,
                        deviceId = cfg.deviceId,
                        staticQrisString = rawQris
                    )
                    val logLine = if (res.success) {
                        "🔄 QRIS DIGANTI: ${res.merchantName ?: "-"} (${res.message})"
                    } else {
                        "❌ GAGAL GANTI QRIS: ${res.message}"
                    }
                    DeliveryLogStore.append(this, logLine)
                    runOnUiThread {
                        refreshLogs()
                        Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                    }
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
