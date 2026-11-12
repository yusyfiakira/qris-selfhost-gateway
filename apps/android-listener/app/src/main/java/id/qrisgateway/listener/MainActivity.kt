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
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val idrFormat = NumberFormat.getInstance(Locale("id", "ID"))

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var navHome: Button
    private lateinit var navHistory: Button
    private lateinit var navSettings: Button
    private lateinit var navProfile: Button

    // Beranda
    private lateinit var tvHomeHeaderTitle: TextView
    private lateinit var tvHomeListenerDot: TextView
    private lateinit var tvHomeMerchantName: TextView
    private lateinit var tvHomeNmid: TextView
    private lateinit var tvHomeActiveBadge: TextView
    private lateinit var tvHomeRevenue: TextView
    private lateinit var tvHomeTodaySummary: TextView
    private lateinit var llRecentTransactions: LinearLayout

    // Riwayat
    private lateinit var tvHistoryTotalAmount: TextView
    private lateinit var tvHistoryTotalCount: TextView
    private lateinit var chipAll: Button
    private lateinit var chipPending: Button
    private lateinit var chipPaid: Button
    private lateinit var chipExpired: Button
    private lateinit var llAllTransactions: LinearLayout

    // Setting
    private lateinit var tvStatusBadge: TextView
    private lateinit var etWebhookUrl: EditText
    private lateinit var etCallbackUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etPackages: EditText
    private lateinit var rgFilterMode: RadioGroup
    private lateinit var rbFilterStandard: RadioButton
    private lateinit var rbFilterAnyNumber: RadioButton
    private lateinit var rbFilterCustom: RadioButton
    private lateinit var etIncludeKeywords: EditText
    private lateinit var etExcludeKeywords: EditText
    private lateinit var etDefaultExpiry: EditText
    private lateinit var tvDeliveryLogs: TextView

    // Profil
    private lateinit var tvProfileAvatar: TextView
    private lateinit var tvProfileMerchant: TextView
    private lateinit var tvProfileCity: TextView
    private lateinit var tvProfileNmid: TextView
    private lateinit var tvProfileSource: TextView
    private lateinit var tvProfileDeviceId: TextView
    private lateinit var tvProfileApiKey: TextView
    private lateinit var tvProfileSecret: TextView

    private var currentStatusFilter = ""
    private var filterMinAmount: Long? = null
    private var filterMaxAmount: Long? = null
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
                syncDashboard()
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
                    QrisDecoder.inspectQris(raw)
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
            syncDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadFormFromConfig()
        requestPostNotificationsIfNeeded()
        switchTab(0)

        // Bottom Nav
        navHome.setOnClickListener { switchTab(0) }
        navHistory.setOnClickListener { switchTab(1) }
        navSettings.setOnClickListener { switchTab(2) }
        navProfile.setOnClickListener { switchTab(3) }
        findViewById<Button>(R.id.fabQuickQr).setOnClickListener { showQuickQrDialog() }

        // Beranda
        findViewById<Button>(R.id.btnHomeRefresh).setOnClickListener { syncDashboard() }
        findViewById<Button>(R.id.btnQuickQrHome).setOnClickListener { showQuickQrDialog() }
        findViewById<Button>(R.id.btnGoHistory).setOnClickListener { switchTab(1) }

        // Riwayat
        findViewById<Button>(R.id.btnHistoryFilter).setOnClickListener { showHistoryFilterDialog() }
        chipAll.setOnClickListener { setStatusChip("") }
        chipPending.setOnClickListener { setStatusChip("PENDING") }
        chipPaid.setOnClickListener { setStatusChip("PAID") }
        chipExpired.setOnClickListener { setStatusChip("EXPIRED") }

        // Setting
        findViewById<Button>(R.id.btnGrantNotification).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnDisableBatteryOpt).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnPickApps).setOnClickListener { showAppPickerDialog() }
        findViewById<Button>(R.id.btnClearApps).setOnClickListener {
            etPackages.setText("")
            Toast.makeText(this, "Filter app dikosongkan", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnChangeQris).setOnClickListener {
            saveFormToConfig()
            showChangeQrisDialog()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveFormToConfig()
            HeartbeatWorker.schedule(this)
            val cfg = ConfigStore.load(this)
            if (cfg.apiKey.isNotBlank() && cfg.webhookUrl.isNotBlank()) {
                ioExecutor.execute {
                    val (ok, msg) = WebhookClient.saveCallbackConfig(
                        cfg,
                        cfg.defaultCallbackUrl,
                        cfg.defaultExpiryMinutes
                    )
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (ok) syncDashboard()
                    }
                }
            } else {
                Toast.makeText(this, "Konfigurasi lokal tersimpan terenkripsi", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }
        findViewById<Button>(R.id.btnTestCallback).setOnClickListener {
            saveFormToConfig()
            val cfg = ConfigStore.load(this)
            ioExecutor.execute {
                val (ok, msg) = WebhookClient.testCallback(cfg, cfg.defaultCallbackUrl)
                DeliveryLogStore.append(this, if (ok) "✅ $msg" else "❌ $msg")
                runOnUiThread {
                    refreshLogs()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
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
                runOnUiThread {
                    refreshLogs()
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnExportConfig).setOnClickListener {
            saveFormToConfig()
            showExportDialog()
        }
        findViewById<Button>(R.id.btnImportJson).setOnClickListener { showImportChoiceDialog() }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            DeliveryLogStore.clear(this)
            refreshLogs()
        }

        // Profil
        findViewById<Button>(R.id.btnCopyApiKey).setOnClickListener {
            copyToClipboard("api_key", ConfigStore.load(this).apiKey, "API Key disalin")
        }
        findViewById<Button>(R.id.btnCopySecret).setOnClickListener {
            copyToClipboard("webhook_secret", ConfigStore.load(this).secret, "Webhook Secret disalin")
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
        syncDashboard()
    }

    override fun onPause() {
        unregisterReceiver(logReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        viewFlipper = findViewById(R.id.viewFlipper)
        navHome = findViewById(R.id.navHome)
        navHistory = findViewById(R.id.navHistory)
        navSettings = findViewById(R.id.navSettings)
        navProfile = findViewById(R.id.navProfile)

        tvHomeHeaderTitle = findViewById(R.id.tvHomeHeaderTitle)
        tvHomeListenerDot = findViewById(R.id.tvHomeListenerDot)
        tvHomeMerchantName = findViewById(R.id.tvHomeMerchantName)
        tvHomeNmid = findViewById(R.id.tvHomeNmid)
        tvHomeActiveBadge = findViewById(R.id.tvHomeActiveBadge)
        tvHomeRevenue = findViewById(R.id.tvHomeRevenue)
        tvHomeTodaySummary = findViewById(R.id.tvHomeTodaySummary)
        llRecentTransactions = findViewById(R.id.llRecentTransactions)

        tvHistoryTotalAmount = findViewById(R.id.tvHistoryTotalAmount)
        tvHistoryTotalCount = findViewById(R.id.tvHistoryTotalCount)
        chipAll = findViewById(R.id.chipAll)
        chipPending = findViewById(R.id.chipPending)
        chipPaid = findViewById(R.id.chipPaid)
        chipExpired = findViewById(R.id.chipExpired)
        llAllTransactions = findViewById(R.id.llAllTransactions)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etCallbackUrl = findViewById(R.id.etCallbackUrl)
        etSecret = findViewById(R.id.etSecret)
        etApiKey = findViewById(R.id.etApiKey)
        etDeviceId = findViewById(R.id.etDeviceId)
        etPackages = findViewById(R.id.etPackages)
        rgFilterMode = findViewById(R.id.rgFilterMode)
        rbFilterStandard = findViewById(R.id.rbFilterStandard)
        rbFilterAnyNumber = findViewById(R.id.rbFilterAnyNumber)
        rbFilterCustom = findViewById(R.id.rbFilterCustom)
        etIncludeKeywords = findViewById(R.id.etIncludeKeywords)
        etExcludeKeywords = findViewById(R.id.etExcludeKeywords)
        etDefaultExpiry = findViewById(R.id.etDefaultExpiry)
        tvDeliveryLogs = findViewById(R.id.tvDeliveryLogs)

        tvProfileAvatar = findViewById(R.id.tvProfileAvatar)
        tvProfileMerchant = findViewById(R.id.tvProfileMerchant)
        tvProfileCity = findViewById(R.id.tvProfileCity)
        tvProfileNmid = findViewById(R.id.tvProfileNmid)
        tvProfileSource = findViewById(R.id.tvProfileSource)
        tvProfileDeviceId = findViewById(R.id.tvProfileDeviceId)
        tvProfileApiKey = findViewById(R.id.tvProfileApiKey)
        tvProfileSecret = findViewById(R.id.tvProfileSecret)
    }

    private fun switchTab(index: Int) {
        viewFlipper.displayedChild = index
        val activeColor = Color.parseColor("#1F8A58")
        val inactiveColor = Color.parseColor("#64748B")
        val tabs = listOf(navHome, navHistory, navSettings, navProfile)
        tabs.forEachIndexed { idx, btn ->
            btn.setTextColor(if (idx == index) activeColor else inactiveColor)
            btn.setTypeface(null, if (idx == index) Typeface.BOLD else Typeface.NORMAL)
        }
        if (index == 0 || index == 1) {
            syncDashboard()
        }
    }

    private fun setStatusChip(status: String) {
        currentStatusFilter = status
        updateChipStyles()
        syncDashboard()
    }

    private fun updateChipStyles() {
        val mapping = mapOf(
            "" to chipAll,
            "PENDING" to chipPending,
            "PAID" to chipPaid,
            "EXPIRED" to chipExpired
        )
        for ((key, btn) in mapping) {
            val selected = key == currentStatusFilter
            btn.setBackgroundColor(Color.parseColor(if (selected) "#141A12" else "#E2E8F0"))
            btn.setTextColor(Color.parseColor(if (selected) "#CCFF00" else "#141A12"))
        }
    }

    private fun selectedFilterMode(): FilterMode {
        return when (rgFilterMode.checkedRadioButtonId) {
            R.id.rbFilterAnyNumber -> FilterMode.ANY_NUMBER
            R.id.rbFilterCustom -> FilterMode.CUSTOM
            else -> FilterMode.STANDARD_RP
        }
    }

    private fun saveFormToConfig() {
        val expiry = etDefaultExpiry.text.toString().toIntOrNull() ?: 15
        ConfigStore.save(
            context = this,
            webhookUrl = etWebhookUrl.text.toString(),
            secret = etSecret.text.toString(),
            deviceId = etDeviceId.text.toString(),
            rawPackages = etPackages.text.toString(),
            filterMode = selectedFilterMode(),
            rawIncludeKeywords = etIncludeKeywords.text.toString(),
            rawExcludeKeywords = etExcludeKeywords.text.toString(),
            apiKey = etApiKey.text.toString(),
            defaultCallbackUrl = etCallbackUrl.text.toString(),
            defaultExpiryMinutes = expiry
        )
        loadProfileCredentials()
    }

    private fun loadFormFromConfig() {
        val cfg = ConfigStore.load(this)
        etWebhookUrl.setText(cfg.webhookUrl)
        etCallbackUrl.setText(cfg.defaultCallbackUrl)
        etSecret.setText(cfg.secret)
        etApiKey.setText(cfg.apiKey)
        etDeviceId.setText(cfg.deviceId)
        etPackages.setText(cfg.allowedPackages.joinToString(", "))
        when (cfg.filterMode) {
            FilterMode.STANDARD_RP -> rbFilterStandard.isChecked = true
            FilterMode.ANY_NUMBER -> rbFilterAnyNumber.isChecked = true
            FilterMode.CUSTOM -> rbFilterCustom.isChecked = true
        }
        etIncludeKeywords.setText(cfg.includeKeywords.joinToString(", "))
        etExcludeKeywords.setText(cfg.excludeKeywords.joinToString(", "))
        etDefaultExpiry.setText(cfg.defaultExpiryMinutes.toString())
        updateChipStyles()
        loadProfileCredentials()
    }

    private fun loadProfileCredentials() {
        val cfg = ConfigStore.load(this)
        tvProfileDeviceId.text = "Device ID: ${cfg.deviceId}"
        tvProfileApiKey.text = "API Key: ${maskSecret(cfg.apiKey)}"
        tvProfileSecret.text = "Webhook Secret: ${maskSecret(cfg.secret)}"
    }

    private fun maskSecret(s: String): String {
        if (s.isBlank()) return "(belum diisi)"
        if (s.length <= 8) return "••••••••"
        return "${s.take(6)}••••••${s.takeLast(4)}"
    }

    private fun refreshLogs() {
        tvDeliveryLogs.text = DeliveryLogStore.readAll(this)
    }

    private fun refreshStatus() {
        val notifOk = isNotificationServiceEnabled()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        tvStatusBadge.text = buildString {
            append(if (notifOk) "🟢 Listener Aktif (Berjalan Normal)" else "🔴 Izin Notifikasi Belum Aktif")
            append("\n")
            append(if (batteryOk) "🟢 Baterai: Tidak Dibatasi" else "🟡 Baterai: Masih Dibatasi OS")
        }
        tvHomeListenerDot.text = if (notifOk) "● Listener Aktif • Mode QRIS Mandiri" else "○ Izin Notif Belum Aktif"
        tvHomeActiveBadge.text = if (notifOk) "✓ Aktif" else "! Setup"
    }

    private fun syncDashboard() {
        val cfg = ConfigStore.load(this)
        if (cfg.apiKey.isBlank() || cfg.webhookUrl.isBlank()) {
            renderEmptyTransactionList(
                llRecentTransactions,
                "Isi Webhook URL & API Key di tab Setting untuk menampilkan omzet & transaksi."
            )
            renderEmptyTransactionList(
                llAllTransactions,
                "Isi Webhook URL & API Key di tab Setting untuk melihat riwayat transaksi."
            )
            return
        }

        ioExecutor.execute {
            val data = WebhookClient.fetchDashboard(cfg, currentStatusFilter, filterMinAmount, filterMaxAmount)
            runOnUiThread {
                if (!data.ok) {
                    renderEmptyTransactionList(llRecentTransactions, data.error ?: "Gagal memuat data")
                    return@runOnUiThread
                }

                tvHomeMerchantName.text = data.merchantName
                tvHomeNmid.text = "NMID: ${data.nmid} • ${data.merchantCity}"
                tvHomeRevenue.text = "Rp ${idrFormat.format(data.totalPaidAmount)}"
                tvHomeTodaySummary.text =
                    "Hari ini: Rp ${idrFormat.format(data.todayPaidAmount)} (${data.todayPaidCount} transaksi lunas)"

                tvHistoryTotalAmount.text = "Rp ${idrFormat.format(data.totalPaidAmount)}"
                tvHistoryTotalCount.text = "${data.totalPaidCount}"

                tvProfileAvatar.text = data.merchantName.firstOrNull()?.uppercase() ?: "S"
                tvProfileMerchant.text = data.merchantName
                tvProfileCity.text = data.merchantCity
                tvProfileNmid.text = "NMID: ${data.nmid}"
                tvProfileSource.text = "Sumber QRIS: ${data.qrisSource.uppercase()}"

                if (etCallbackUrl.text.isNullOrBlank() && data.callbackUrl.isNotBlank()) {
                    etCallbackUrl.setText(data.callbackUrl)
                }

                renderTransactionList(llRecentTransactions, data.invoices.take(5), cfg)
                renderTransactionList(llAllTransactions, data.invoices, cfg)
            }
        }
    }

    private fun renderEmptyTransactionList(container: LinearLayout, message: String) {
        container.removeAllViews()
        val tv = TextView(this).apply {
            text = message
            setPadding(24, 24, 24, 24)
            setTextColor(Color.parseColor("#52604C"))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_card)
        }
        container.addView(tv)
    }

    private fun renderTransactionList(container: LinearLayout, items: List<InvoiceItem>, cfg: ListenerConfig) {
        container.removeAllViews()
        if (items.isEmpty()) {
            renderEmptyTransactionList(container, "Belum ada transaksi yang sesuai filter.")
            return
        }

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(30, 24, 30, 24)
                setBackgroundResource(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 18
                layoutParams = lp
                setOnClickListener { showTransactionDetailDialog(item, cfg) }
            }

            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvOrder = TextView(this).apply {
                text = item.externalOrderId.ifBlank { item.id }
                setTextColor(Color.parseColor("#141A12"))
                setTypeface(null, Typeface.BOLD)
                textSize = 13f
            }
            val tvMeta = TextView(this).apply {
                text = "${item.createdAt.take(16).replace("T", " ")} • CB: ${item.callbackStatus}"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
            }
            left.addView(tvOrder)
            left.addView(tvMeta)

            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            val tvAmount = TextView(this).apply {
                text = (if (item.status == "PAID") "+" else "") + "Rp ${idrFormat.format(item.totalAmount)}"
                setTextColor(
                    Color.parseColor(
                        when (item.status) {
                            "PAID" -> "#1F8A58"
                            "PENDING" -> "#D97706"
                            else -> "#141A12"
                        }
                    )
                )
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            }
            val tvStatus = TextView(this).apply {
                text = when (item.status) {
                    "PAID" -> "✓ Berhasil"
                    "PENDING" -> "⏳ Menunggu"
                    "CANCELLED" -> "✕ Dibatalkan"
                    else -> "⏱ Kedaluwarsa"
                }
                setBackgroundResource(
                    when (item.status) {
                        "PAID" -> R.drawable.bg_badge_paid
                        "PENDING" -> R.drawable.bg_badge_pending
                        else -> R.drawable.bg_badge_expired
                    }
                )
                setPadding(18, 4, 18, 4)
                textSize = 10f
                setTextColor(Color.parseColor("#141A12"))
            }
            right.addView(tvAmount)
            right.addView(tvStatus)

            card.addView(left)
            card.addView(right)
            container.addView(card)
        }
    }

    private fun showTransactionDetailDialog(item: InvoiceItem, cfg: ListenerConfig) {
        val msg = buildString {
            append("Invoice ID: ${item.id}\n")
            append("Order Toko: ${item.externalOrderId}\n")
            append("Nominal: Rp ${idrFormat.format(item.totalAmount)}\n")
            append("Status: ${item.status}\n")
            append("Callback: ${item.callbackStatus}")
            if (item.callbackHttpCode != null) append(" (HTTP ${item.callbackHttpCode})")
            if (!item.paidAt.isNullOrBlank()) append("\nLunas pada: ${item.paidAt}")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Detail Transaksi")
            .setMessage(msg)
            .setPositiveButton("Tutup", null)

        if (item.status == "PAID" && item.callbackStatus != "NONE") {
            builder.setNeutralButton("Kirim Ulang Callback") { _, _ ->
                ioExecutor.execute {
                    val (_, resMsg) = WebhookClient.retryInvoiceCallback(cfg, item.id)
                    runOnUiThread {
                        Toast.makeText(this, resMsg, Toast.LENGTH_LONG).show()
                        syncDashboard()
                    }
                }
            }
        }
        builder.show()
    }

    private fun showHistoryFilterDialog() {
        val etMin = EditText(this).apply {
            hint = "Nominal Minimal (contoh: 1000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (filterMinAmount != null) setText(filterMinAmount.toString())
        }
        val etMax = EditText(this).apply {
            hint = "Nominal Maksimal (contoh: 100000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (filterMaxAmount != null) setText(filterMaxAmount.toString())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(etMin)
            addView(etMax)
        }
        AlertDialog.Builder(this)
            .setTitle("Filter Transaksi")
            .setView(box)
            .setPositiveButton("Terapkan") { _, _ ->
                filterMinAmount = etMin.text.toString().toLongOrNull()
                filterMaxAmount = etMax.text.toString().toLongOrNull()
                syncDashboard()
            }
            .setNeutralButton("Reset") { _, _ ->
                filterMinAmount = null
                filterMaxAmount = null
                syncDashboard()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showQuickQrDialog() {
        val cfg = ConfigStore.load(this)
        val etAmount = EditText(this).apply {
            hint = "Nominal dasar (contoh: 10000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etMinutes = EditText(this).apply {
            hint = "Durasi menit (15, 30, 60, 720, 1440)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cfg.defaultExpiryMinutes.toString())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(etAmount)
            addView(etMinutes)
        }
        AlertDialog.Builder(this)
            .setTitle("Buat QRIS Dinamis Cepat")
            .setView(box)
            .setPositiveButton("Generate QR") { _, _ ->
                val baseAmt = etAmount.text.toString().toLongOrNull() ?: 0L
                val mins = etMinutes.text.toString().toIntOrNull() ?: cfg.defaultExpiryMinutes
                if (baseAmt <= 0L) {
                    Toast.makeText(this, "Nominal harus lebih dari 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ioExecutor.execute {
                    val res = WebhookClient.createQuickInvoice(cfg, baseAmt, mins)
                    runOnUiThread {
                        if (!res.success) {
                            Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                        } else {
                            showGeneratedQrPopup(res)
                            syncDashboard()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showGeneratedQrPopup(res: QuickInvoiceResult) {
        val bmp = QrisDecoder.encodeToBitmap(res.qrString, 620)
        val iv = ImageView(this).apply { setImageBitmap(bmp) }
        val tv = TextView(this).apply {
            text = "Nominal Tepat: Rp ${idrFormat.format(res.totalAmount)}\nID: ${res.invoiceId}"
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#141A12"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 24, 36, 12)
            addView(iv)
            addView(tv)
        }
        AlertDialog.Builder(this)
            .setTitle("Scan QRIS Dinamis")
            .setView(box)
            .setPositiveButton("Tutup", null)
            .show()
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
            text = "Sertakan Secret & API Key (Rahasia — jangan bagikan)"
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
                copyToClipboard("qris-listener-config", json, "Config JSON disalin ke clipboard!")
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
                    syncDashboard()
                    Toast.makeText(this, "Config JSON berhasil di-import!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Format JSON tidak valid: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showChangeQrisDialog() {
        val savedApiKey = ConfigStore.load(this).apiKey
        val etAdminKey = EditText(this).apply {
            hint = "Masukkan API_KEY Admin"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (savedApiKey.isNotBlank()) setText(savedApiKey)
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
            addView(etAdminKey)
            addView(btnPickImg)
            addView(etQrisRaw)
            addView(tvPreview)
        }

        AlertDialog.Builder(this)
            .setTitle("Admin: Ganti Kode QRIS Merchant")
            .setView(container)
            .setPositiveButton("Update ke Server") { _, _ ->
                val apiKey = etAdminKey.text.toString().trim()
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
                        syncDashboard()
                        Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String, toastMsg: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Belum diisi", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
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
