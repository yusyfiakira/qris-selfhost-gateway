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
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import id.qrisgateway.listener.ui.RoundedCard
import id.qrisgateway.listener.ui.RoundedInputField
import id.qrisgateway.listener.ui.SectionLabel
import id.qrisgateway.listener.ui.StzenBottomBar
import id.qrisgateway.listener.ui.StzenCardBorder
import id.qrisgateway.listener.ui.StzenDark
import id.qrisgateway.listener.ui.StzenEmerald
import id.qrisgateway.listener.ui.StzenEmeraldLight
import id.qrisgateway.listener.ui.StzenErrorBg
import id.qrisgateway.listener.ui.StzenErrorFg
import id.qrisgateway.listener.ui.StzenLime
import id.qrisgateway.listener.ui.StzenMuted
import id.qrisgateway.listener.ui.StzenSubtle
import id.qrisgateway.listener.ui.StzenSurface
import id.qrisgateway.listener.ui.TransactionRowCard
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

data class InstalledAppItem(
    val packageName: String,
    val label: String
)

class MainActivity : ComponentActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val idrFormat = NumberFormat.getInstance(Locale("id", "ID"))

    // Navigation & Live State
    private var selectedTab by mutableIntStateOf(0)
    private var notifEnabled by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var deliveryLogs by mutableStateOf("")

    // Dashboard State
    private var dashboardData by mutableStateOf(DashboardData(ok = false))
    private var isSyncing by mutableStateOf(false)
    private var statusFilter by mutableStateOf("")
    private var minAmountFilter by mutableStateOf<Long?>(null)
    private var maxAmountFilter by mutableStateOf<Long?>(null)

    // Form Config State
    private var webhookUrl by mutableStateOf("")
    private var callbackUrl by mutableStateOf("")
    private var secret by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var deviceId by mutableStateOf("")
    private var packagesCsv by mutableStateOf("")
    private var filterMode by mutableStateOf(FilterMode.STANDARD_RP)
    private var includeKeywords by mutableStateOf("")
    private var excludeKeywords by mutableStateOf("")
    private var defaultExpiryText by mutableStateOf("15")

    // Modals / Bottom Sheets
    private var showFilterSheet by mutableStateOf(false)
    private var showAppPickerDialog by mutableStateOf(false)
    private var installedApps by mutableStateOf<List<InstalledAppItem>>(emptyList())
    private var showQuickQrDialog by mutableStateOf(false)
    private var generatedQrResult by mutableStateOf<QuickInvoiceResult?>(null)
    private var generatedQrBitmap by mutableStateOf<Bitmap?>(null)
    private var selectedInvoiceDetail by mutableStateOf<InvoiceItem?>(null)
    private var showChangeQrisDialog by mutableStateOf(false)
    private var qrisDialogRaw by mutableStateOf("")
    private var qrisDialogPreview by mutableStateOf("Pilih gambar QRIS dari Galeri atau tempel string EMVCo.")
    private var qrisDialogAdminKey by mutableStateOf("")
    private var showExportDialog by mutableStateOf(false)
    private var showImportDialog by mutableStateOf(false)
    private var pendingExportIncludeSecret = false

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
                Toast.makeText(this, "Config berhasil di-import!", Toast.LENGTH_SHORT).show()
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
                        qrisDialogRaw = preview.rawString
                        qrisDialogPreview = buildString {
                            append("Merchant: ${preview.merchantName}\n")
                            append("CRC16: ${if (preview.isValidCrc) "Valid" else "Tidak Valid"} • ")
                            append("Tipe: ${if (preview.isStatic) "Statis (11)" else "Bukan Statis"}")
                        }
                    }.onFailure { err ->
                        Toast.makeText(this, "Gagal baca gambar QR: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            deliveryLogs = DeliveryLogStore.readAll(this@MainActivity)
            syncDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFormFromConfig()
        requestPostNotificationsIfNeeded()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = StzenEmerald,
                    secondary = StzenDark,
                    background = StzenSurface,
                    surface = Color.White
                )
            ) {
                MainScreen()
            }
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
        refreshSystemStatus()
        deliveryLogs = DeliveryLogStore.readAll(this)
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        Scaffold(
            containerColor = StzenSurface,
            bottomBar = {
                StzenBottomBar(
                    selectedTab = selectedTab,
                    onSelectTab = { idx ->
                        selectedTab = idx
                        if (idx == 0 || idx == 1) syncDashboard()
                    },
                    onQuickQr = { showQuickQrDialog = true }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> HomeTab()
                    1 -> HistoryTab()
                    2 -> SettingsTab()
                    3 -> ProfileTab()
                }
            }
        }

        if (showFilterSheet) {
            FilterBottomSheet()
        }
        if (showAppPickerDialog) {
            AppPickerDialog()
        }
        if (showQuickQrDialog) {
            QuickQrInputDialog()
        }
        if (generatedQrResult != null && generatedQrBitmap != null) {
            GeneratedQrPopupDialog(generatedQrResult!!, generatedQrBitmap!!)
        }
        if (selectedInvoiceDetail != null) {
            TransactionDetailDialog(selectedInvoiceDetail!!)
        }
        if (showChangeQrisDialog) {
            ChangeQrisDialog()
        }
        if (showExportDialog) {
            ExportConfigDialog()
        }
        if (showImportDialog) {
            ImportConfigDialog()
        }
    }

    @Composable
    private fun HomeTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StzenLime)
                            .border(1.5.dp, StzenDark, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = StzenDark,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ST'ZEN QRIS PG",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StzenDark
                        )
                        Text(
                            text = if (notifEnabled) "● Listener Aktif • QRIS Mandiri" else "○ Izin Notif Belum Aktif",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (notifEnabled) StzenEmerald else StzenErrorFg
                        )
                    }
                    IconButton(onClick = { syncDashboard() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Sync",
                            tint = StzenDark
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(StzenDark, StzenEmerald)))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Storefront,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (dashboardData.ok) dashboardData.merchantName else "Merchant QRIS",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = if (dashboardData.ok) "NMID: ${dashboardData.nmid}" else "Belum tersambung ke Worker",
                                    color = Color(0xFFCBE7D5),
                                    fontSize = 11.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (notifEnabled) StzenLime else StzenErrorBg
                            ) {
                                Text(
                                    text = if (notifEnabled) "✓ Aktif" else "! Setup",
                                    color = StzenDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Pemasukan Terdeteksi",
                            color = Color(0xFFCBE7D5),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Rp ${idrFormat.format(dashboardData.totalPaidAmount)}",
                            color = StzenLime,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isSyncing) {
                                "Menyinkronkan data dari Cloudflare..."
                            } else {
                                "Hari ini: Rp ${idrFormat.format(dashboardData.todayPaidAmount)} (${dashboardData.todayPaidCount} lunas)"
                            },
                            color = Color(0xFFE2F4E9),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { showQuickQrDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StzenLime,
                            contentColor = StzenDark
                        ),
                        border = BorderStroke(1.5.dp, StzenDark),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buat QR Cepat", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            selectedTab = 1
                            syncDashboard()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StzenDark,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Riwayat", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = "Transaksi Terakhir",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = StzenDark,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        selectedTab = 1
                        syncDashboard()
                    }) {
                        Text("Lihat Semua", color = StzenEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            if (!dashboardData.ok) {
                item {
                    RoundedCard {
                        Text(
                            text = dashboardData.error ?: "Isi Webhook URL & API Key di tab Setting untuk melihat transaksi.",
                            color = StzenMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (dashboardData.invoices.isEmpty()) {
                item {
                    RoundedCard {
                        Text("Belum ada transaksi tercatat.", color = StzenMuted, fontSize = 13.sp)
                    }
                }
            } else {
                items(dashboardData.invoices.take(5), key = { it.id }) { item ->
                    TransactionRowCard(item, idrFormat) { selectedInvoiceDetail = item }
                }
            }
        }
    }

    @Composable
    private fun HistoryTab() {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(StzenDark, StzenEmerald)))
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Semua Transaksi",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "Riwayat Invoice & Callback",
                            color = Color(0xFFCBE7D5),
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Filter", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = StzenLime,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pendapatan", color = Color(0xFFCBE7D5), fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Rp ${idrFormat.format(dashboardData.totalPaidAmount)}",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp
                            )
                        }
                    }

                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Total Lunas", color = Color(0xFFCBE7D5), fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${dashboardData.totalPaidCount} Transaksi",
                                color = StzenLime,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp
                            )
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val chips = listOf("" to "Semua", "PENDING" to "PENDING", "PAID" to "PAID", "EXPIRED" to "EXPIRED")
                for ((key, label) in chips) {
                    FilterChip(
                        selected = statusFilter == key,
                        onClick = {
                            statusFilter = key
                            syncDashboard()
                        },
                        label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StzenDark,
                            selectedLabelColor = StzenLime
                        )
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (!dashboardData.ok) {
                    item {
                        RoundedCard {
                            Text(
                                text = dashboardData.error ?: "Isi Webhook URL & API Key di tab Setting.",
                                color = StzenMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else if (dashboardData.invoices.isEmpty()) {
                    item {
                        RoundedCard {
                            Text("Tidak ada transaksi sesuai filter.", color = StzenMuted, fontSize = 13.sp)
                        }
                    }
                } else {
                    items(dashboardData.invoices, key = { it.id }) { item ->
                        TransactionRowCard(item, idrFormat) { selectedInvoiceDetail = item }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Pengaturan",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = StzenDark
            )

            SectionLabel("Status Sistem")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF147D50), StzenEmerald)))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = StzenLime,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (notifEnabled) "Listener Aktif" else "Izin Notif Belum Aktif",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (batteryUnrestricted) "Optimasi baterai: Bebas" else "Optimasi baterai: Masih dibatasi",
                                color = Color(0xFFE2F4E9),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            colors = ButtonDefaults.buttonColors(containerColor = StzenLime, contentColor = StzenDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Izin Notif", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Bebas Baterai", fontSize = 12.sp)
                        }
                    }
                }
            }

            SectionLabel("Pembayaran Aktif & QRIS")
            RoundedCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Aplikasi Merchant Dipantau",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = StzenDark,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        installedApps = loadLauncherApps()
                        showAppPickerDialog = true
                    }) {
                        Text("✓ Pilih App", color = StzenEmerald, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { packagesCsv = "" }) {
                        Text("Semua", color = StzenMuted)
                    }
                }

                RoundedInputField(
                    label = "Daftar Package App (otomatis terisi dari Pilih App)",
                    value = packagesCsv,
                    onValueChange = { packagesCsv = it },
                    placeholder = "Kosong = semua app",
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        saveFormToConfig()
                        qrisDialogAdminKey = apiKey
                        showChangeQrisDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenLime, contentColor = StzenDark),
                    border = BorderStroke(1.5.dp, StzenDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ganti Kode QRIS Statis (Galeri / String)", fontWeight = FontWeight.Bold)
                }
            }

            SectionLabel("Pengaturan Filter & Batas Waktu")
            RoundedCard {
                Text("Mode Filter Notifikasi", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = StzenDark)
                Spacer(modifier = Modifier.height(4.dp))
                FilterRadioRow("Standar Pembayaran (Rp / IDR)", filterMode == FilterMode.STANDARD_RP) {
                    filterMode = FilterMode.STANDARD_RP
                }
                FilterRadioRow("Semua Notif Mengandung Angka", filterMode == FilterMode.ANY_NUMBER) {
                    filterMode = FilterMode.ANY_NUMBER
                }
                FilterRadioRow("Filter Kustom (Kata Kunci Sendiri)", filterMode == FilterMode.CUSTOM) {
                    filterMode = FilterMode.CUSTOM
                }

                if (filterMode == FilterMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(6.dp))
                    RoundedInputField(
                        label = "Kata Wajib Ada (Include, pisah koma)",
                        value = includeKeywords,
                        onValueChange = { includeKeywords = it },
                        placeholder = "diterima, masuk, qris"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                RoundedInputField(
                    label = "Kata Diabaikan (Exclude, pisah koma)",
                    value = excludeKeywords,
                    onValueChange = { excludeKeywords = it },
                    placeholder = "promo, cashback, diskon, voucher"
                )

                Spacer(modifier = Modifier.height(10.dp))
                RoundedInputField(
                    label = "Batas Waktu Default QRIS",
                    value = defaultExpiryText,
                    onValueChange = { defaultExpiryText = it.filter { ch -> ch.isDigit() } },
                    placeholder = "15",
                    keyboardType = KeyboardType.Number,
                    trailingUnit = "Menit"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf("15" to "15m", "30" to "30m", "60" to "1j", "720" to "12j", "1440" to "24j").forEach { (mins, lbl) ->
                        FilterChip(
                            selected = defaultExpiryText == mins,
                            onClick = { defaultExpiryText = mins },
                            label = { Text(lbl, fontSize = 11.sp) }
                        )
                    }
                }
            }

            SectionLabel("Integrasi Sistem & Webhook")
            RoundedCard {
                RoundedInputField(
                    label = "1. Webhook URL Worker (/v1/webhook/mutation)",
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    placeholder = "https://.../v1/webhook/mutation",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(10.dp))
                RoundedInputField(
                    label = "2. Webhook Sukses Toko (Default Callback URL, opsional)",
                    value = callbackUrl,
                    onValueChange = { callbackUrl = it },
                    placeholder = "https://toko-kamu.com/api/webhook/remote-qris",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(10.dp))
                RoundedInputField(
                    label = "3. Webhook Secret (qris_hook_...)",
                    value = secret,
                    onValueChange = { secret = it },
                    placeholder = "qris_hook_...",
                    isSecret = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                RoundedInputField(
                    label = "4. API Key Admin (qris_api_...)",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "qris_api_...",
                    isSecret = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                RoundedInputField(
                    label = "5. Device ID",
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    placeholder = "android-sm-m236b"
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        saveFormToConfig()
                        HeartbeatWorker.schedule(this@MainActivity)
                        val cfg = ConfigStore.load(this@MainActivity)
                        if (cfg.apiKey.isNotBlank() && cfg.webhookUrl.isNotBlank()) {
                            ioExecutor.execute {
                                val (ok, msg) = WebhookClient.saveCallbackConfig(
                                    cfg,
                                    cfg.defaultCallbackUrl,
                                    cfg.defaultExpiryMinutes
                                )
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    if (ok) syncDashboard()
                                }
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Konfigurasi lokal tersimpan terenkripsi!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = StzenLime),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Simpan & Sync ke Worker", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            saveFormToConfig()
                            val cfg = ConfigStore.load(this@MainActivity)
                            ioExecutor.execute {
                                val (_, msg) = WebhookClient.testCallback(cfg, cfg.defaultCallbackUrl)
                                DeliveryLogStore.append(this@MainActivity, "🧪 Callback: $msg")
                                runOnUiThread {
                                    deliveryLogs = DeliveryLogStore.readAll(this@MainActivity)
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes Callback", fontSize = 12.sp, color = StzenDark)
                    }
                    OutlinedButton(
                        onClick = {
                            saveFormToConfig()
                            val cfg = ConfigStore.load(this@MainActivity)
                            ioExecutor.execute {
                                val hbOk = WebhookClient.sendHeartbeat(
                                    cfg,
                                    HeartbeatWorker.readBatteryPct(this@MainActivity),
                                    HeartbeatWorker.readNetworkType(this@MainActivity)
                                )
                                val res = WebhookClient.sendMutation(cfg, packageName, "Tes koneksi Rp 99.999")
                                val msg = when (res) {
                                    is SendResult.Delivered -> "🧪 TES OK (${res.outcome}) | Heartbeat: ${if (hbOk) "OK" else "Skip"}"
                                    is SendResult.Rejected -> "🧪 TES DITOLAK (${res.code}): ${res.message}"
                                    is SendResult.TransientFailure -> "🧪 TES GAGAL: ${res.reason}"
                                }
                                DeliveryLogStore.append(this@MainActivity, msg)
                                runOnUiThread {
                                    deliveryLogs = DeliveryLogStore.readAll(this@MainActivity)
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes Listener", fontSize = 12.sp, color = StzenDark)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            saveFormToConfig()
                            showExportDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = StzenDark)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", fontSize = 12.sp, color = StzenDark)
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = StzenDark)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import", fontSize = 12.sp, color = StzenDark)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) { SectionLabel("Aktivitas Sistem") }
                IconButton(onClick = {
                    DeliveryLogStore.clear(this@MainActivity)
                    deliveryLogs = DeliveryLogStore.readAll(this@MainActivity)
                }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Bersihkan", tint = StzenMuted)
                }
            }

            Surface(
                color = StzenDark,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = deliveryLogs.ifBlank { "Belum ada aktivitas." },
                    color = Color(0xFFE2F4E9),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    @Composable
    private fun ProfileTab() {
        var revealApiKey by mutableStateOf(false)
        var revealSecret by mutableStateOf(false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(StzenDark, StzenEmerald)))
                    .statusBarsPadding()
                    .padding(28.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(StzenLime)
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    Text(
                        text = dashboardData.merchantName.firstOrNull()?.uppercase() ?: "S",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StzenDark
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (dashboardData.ok) dashboardData.merchantName else "Merchant QRIS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = if (dashboardData.ok) "${dashboardData.merchantCity} • ${dashboardData.qrisSource.uppercase()}" else "Belum sync Worker",
                    color = Color(0xFFCBE7D5),
                    fontSize = 12.sp
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                SectionLabel("Informasi Toko")
                RoundedCard {
                    ProfileInfoRow("NMID Merchant", dashboardData.nmid)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = StzenCardBorder)
                    ProfileInfoRow("Device ID", deviceId)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = StzenCardBorder)
                    ProfileInfoRow("Batas Waktu Default", "$defaultExpiryText Menit")
                }

                SectionLabel("Kredensial API")
                RoundedCard {
                    SecretCopyRow(
                        label = "API Key Admin",
                        value = apiKey,
                        revealed = revealApiKey,
                        onToggleReveal = { revealApiKey = !revealApiKey },
                        onCopy = { copyToClipboard("api_key", apiKey, "API Key disalin") }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = StzenCardBorder)
                    SecretCopyRow(
                        label = "Webhook Secret",
                        value = secret,
                        revealed = revealSecret,
                        onToggleReveal = { revealSecret = !revealSecret },
                        onCopy = { copyToClipboard("webhook_secret", secret, "Webhook Secret disalin") }
                    )
                }
            }
        }
    }

    @Composable
    private fun ProfileInfoRow(label: String, value: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, fontSize = 12.sp, color = StzenMuted, modifier = Modifier.weight(1f))
            Text(value.ifBlank { "-" }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = StzenDark)
        }
    }

    @Composable
    private fun SecretCopyRow(
        label: String,
        value: String,
        revealed: Boolean,
        onToggleReveal: () -> Unit,
        onCopy: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Key, contentDescription = null, tint = StzenEmerald, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = StzenMuted)
                Text(
                    text = when {
                        value.isBlank() -> "(belum diisi)"
                        revealed -> value
                        else -> "••••••••••••" + value.takeLast(4)
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = StzenDark
                )
            }
            IconButton(onClick = onToggleReveal) {
                Icon(
                    imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = null,
                    tint = StzenMuted
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Salin", tint = StzenDark)
            }
        }
    }

    @Composable
    private fun FilterRadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onSelect() }
                .padding(vertical = 2.dp)
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(label, fontSize = 13.sp, color = StzenDark)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FilterBottomSheet() {
        var minText by mutableStateOf(minAmountFilter?.toString() ?: "")
        var maxText by mutableStateOf(maxAmountFilter?.toString() ?: "")

        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = Color.White
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Filter Transaksi", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = StzenDark)
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        RoundedInputField(
                            label = "Nominal Minimal",
                            value = minText,
                            onValueChange = { minText = it.filter { ch -> ch.isDigit() } },
                            placeholder = "1000",
                            keyboardType = KeyboardType.Number
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        RoundedInputField(
                            label = "Nominal Maksimal",
                            value = maxText,
                            onValueChange = { maxText = it.filter { ch -> ch.isDigit() } },
                            placeholder = "500000",
                            keyboardType = KeyboardType.Number
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            minAmountFilter = null
                            maxAmountFilter = null
                            showFilterSheet = false
                            syncDashboard()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset", color = StzenDark)
                    }
                    Button(
                        onClick = {
                            minAmountFilter = minText.toLongOrNull()
                            maxAmountFilter = maxText.toLongOrNull()
                            showFilterSheet = false
                            syncDashboard()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = StzenLime),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Terapkan", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun AppPickerDialog() {
        val selectedSet = mutableStateOf(
            packagesCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        )

        AlertDialog(
            onDismissRequest = { showAppPickerDialog = false },
            title = { Text("Pilih Aplikasi Merchant", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    items(installedApps, key = { it.packageName }) { app ->
                        val isChecked = selectedSet.value.contains(app.packageName.lowercase())
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = selectedSet.value.toMutableSet()
                                    if (isChecked) next.remove(app.packageName.lowercase())
                                    else next.add(app.packageName.lowercase())
                                    selectedSet.value = next
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val next = selectedSet.value.toMutableSet()
                                    if (checked) next.add(app.packageName.lowercase())
                                    else next.remove(app.packageName.lowercase())
                                    selectedSet.value = next
                                }
                            )
                            Column {
                                Text(app.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = StzenDark)
                                Text(app.packageName, fontSize = 11.sp, color = StzenSubtle)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        packagesCsv = selectedSet.value.sorted().joinToString(", ")
                        showAppPickerDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenEmerald)
                ) {
                    Text("Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppPickerDialog = false }) { Text("Batal") }
            }
        )
    }

    @Composable
    private fun QuickQrInputDialog() {
        var amountStr by mutableStateOf("1000")
        var expiryStr by mutableStateOf(defaultExpiryText.ifBlank { "15" })

        AlertDialog(
            onDismissRequest = { showQuickQrDialog = false },
            title = { Text("Buat QRIS Dinamis Cepat", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundedInputField(
                        label = "Nominal Dasar (Rp)",
                        value = amountStr,
                        onValueChange = { amountStr = it.filter { c -> c.isDigit() } },
                        placeholder = "1000",
                        keyboardType = KeyboardType.Number
                    )
                    RoundedInputField(
                        label = "Durasi Kedaluwarsa",
                        value = expiryStr,
                        onValueChange = { expiryStr = it.filter { c -> c.isDigit() } },
                        placeholder = "15",
                        keyboardType = KeyboardType.Number,
                        trailingUnit = "Menit"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountStr.toLongOrNull() ?: 0L
                        val mins = expiryStr.toIntOrNull() ?: 15
                        if (amt <= 0L) {
                            Toast.makeText(this, "Nominal harus > 0", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        showQuickQrDialog = false
                        val cfg = ConfigStore.load(this)
                        ioExecutor.execute {
                            val res = WebhookClient.createQuickInvoice(cfg, amt, mins)
                            val bmp = if (res.success && res.qrString.isNotBlank()) {
                                runCatching { QrisDecoder.encodeToBitmap(res.qrString, 620) }.getOrNull()
                            } else null
                            runOnUiThread {
                                if (!res.success || bmp == null) {
                                    Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                                } else {
                                    generatedQrResult = res
                                    generatedQrBitmap = bmp
                                    syncDashboard()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = StzenLime)
                ) {
                    Text("Generate QR", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickQrDialog = false }) { Text("Batal") }
            }
        )
    }

    @Composable
    private fun GeneratedQrPopupDialog(res: QuickInvoiceResult, bmp: Bitmap) {
        AlertDialog(
            onDismissRequest = {
                generatedQrResult = null
                generatedQrBitmap = null
            },
            title = { Text("Scan QRIS Dinamis", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QRIS Dinamis",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, StzenCardBorder, RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Rp ${idrFormat.format(res.totalAmount)}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StzenEmerald
                    )
                    Text(
                        text = "Wajib pas hingga 3 digit terakhir\nID: ${res.invoiceId}",
                        fontSize = 11.sp,
                        color = StzenMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        generatedQrResult = null
                        generatedQrBitmap = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenDark)
                ) {
                    Text("Tutup")
                }
            }
        )
    }

    @Composable
    private fun TransactionDetailDialog(item: InvoiceItem) {
        AlertDialog(
            onDismissRequest = { selectedInvoiceDetail = null },
            title = { Text("Detail Transaksi", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Invoice: ${item.id}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Order Toko: ${item.externalOrderId}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Nominal: Rp ${idrFormat.format(item.totalAmount)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = StzenEmerald)
                    Text("Status: ${item.status}", fontSize = 12.sp)
                    Text("Callback Toko: ${item.callbackStatus} ${item.callbackHttpCode?.let { "(HTTP $it)" } ?: ""}", fontSize = 12.sp)
                    if (!item.paidAt.isNullOrBlank()) {
                        Text("Lunas pada: ${item.paidAt}", fontSize = 11.sp, color = StzenMuted)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedInvoiceDetail = null }) { Text("Tutup") }
            },
            dismissButton = {
                if (item.status == "PAID" && item.callbackStatus != "NONE") {
                    Button(
                        onClick = {
                            val cfg = ConfigStore.load(this)
                            ioExecutor.execute {
                                val (_, msg) = WebhookClient.retryInvoiceCallback(cfg, item.id)
                                runOnUiThread {
                                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                    selectedInvoiceDetail = null
                                    syncDashboard()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StzenEmerald)
                    ) {
                        Text("Kirim Ulang Callback", fontSize = 12.sp)
                    }
                }
            }
        )
    }

    @Composable
    private fun ChangeQrisDialog() {
        AlertDialog(
            onDismissRequest = { showChangeQrisDialog = false },
            title = { Text("Ganti Kode QRIS Merchant", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundedInputField(
                        label = "API Key Admin",
                        value = qrisDialogAdminKey,
                        onValueChange = { qrisDialogAdminKey = it },
                        placeholder = "qris_api_...",
                        isSecret = true
                    )
                    Button(
                        onClick = { pickQrisImageLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = StzenEmeraldLight, contentColor = StzenDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pilih Gambar QRIS dari Galeri", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    RoundedInputField(
                        label = "String QRIS Statis (000201...)",
                        value = qrisDialogRaw,
                        onValueChange = { qrisDialogRaw = it },
                        placeholder = "000201010211...",
                        singleLine = false
                    )
                    Text(qrisDialogPreview, fontSize = 11.sp, color = StzenMuted)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val key = qrisDialogAdminKey.trim()
                        val raw = qrisDialogRaw.trim()
                        if (key.isNotBlank()) {
                            apiKey = key
                            saveFormToConfig()
                        }
                        val cfg = ConfigStore.load(this)
                        showChangeQrisDialog = false
                        ioExecutor.execute {
                            val res = WebhookClient.updateStaticQris(cfg.webhookUrl, key, cfg.deviceId, raw)
                            val logLine = if (res.success) {
                                "🔄 QRIS DIGANTI: ${res.merchantName ?: "-"}"
                            } else {
                                "❌ GAGAL GANTI QRIS: ${res.message}"
                            }
                            DeliveryLogStore.append(this, logLine)
                            runOnUiThread {
                                deliveryLogs = DeliveryLogStore.readAll(this)
                                Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                                if (res.success) syncDashboard()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = StzenLime)
                ) {
                    Text("Update ke Server", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeQrisDialog = false }) { Text("Batal") }
            }
        )
    }

    @Composable
    private fun ExportConfigDialog() {
        var includeSec by mutableStateOf(false)
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Konfigurasi", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { includeSec = !includeSec }
                ) {
                    Checkbox(checked = includeSec, onCheckedChange = { includeSec = it })
                    Text("Sertakan Secret & API Key (Rahasia)", fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingExportIncludeSecret = includeSec
                        showExportDialog = false
                        exportFileLauncher.launch("qris-listener-config.json")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenEmerald)
                ) {
                    Text("Simpan .json")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val json = ConfigStore.exportToJson(this, includeSec)
                    copyToClipboard("qris-listener-config", json, "Config JSON disalin!")
                    showExportDialog = false
                }) {
                    Text("Salin Teks")
                }
            }
        )
    }

    @Composable
    private fun ImportConfigDialog() {
        var pasteJson by mutableStateOf("")
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Konfigurasi", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            showImportDialog = false
                            importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StzenEmeraldLight, contentColor = StzenDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pilih File .json dari HP", fontWeight = FontWeight.Bold)
                    }
                    RoundedInputField(
                        label = "Atau Tempel JSON di sini",
                        value = pasteJson,
                        onValueChange = { pasteJson = it },
                        placeholder = "{\"webhookUrl\":\"...\",\"secret\":\"...\"}",
                        singleLine = false
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            ConfigStore.importFromJson(this, pasteJson)
                            loadFormFromConfig()
                            HeartbeatWorker.schedule(this)
                            syncDashboard()
                            showImportDialog = false
                            Toast.makeText(this, "Config JSON berhasil di-import!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, "Format JSON tidak valid: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StzenDark, contentColor = StzenLime)
                ) {
                    Text("Import Teks")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Batal") }
            }
        )
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun loadFormFromConfig() {
        val cfg = ConfigStore.load(this)
        webhookUrl = cfg.webhookUrl
        callbackUrl = cfg.defaultCallbackUrl
        secret = cfg.secret
        apiKey = cfg.apiKey
        deviceId = cfg.deviceId
        packagesCsv = cfg.allowedPackages.joinToString(", ")
        filterMode = cfg.filterMode
        includeKeywords = cfg.includeKeywords.joinToString(", ")
        excludeKeywords = cfg.excludeKeywords.joinToString(", ")
        defaultExpiryText = cfg.defaultExpiryMinutes.toString()
    }

    private fun saveFormToConfig() {
        ConfigStore.save(
            context = this,
            webhookUrl = webhookUrl,
            secret = secret,
            deviceId = deviceId,
            rawPackages = packagesCsv,
            filterMode = filterMode,
            rawIncludeKeywords = includeKeywords,
            rawExcludeKeywords = excludeKeywords,
            apiKey = apiKey,
            defaultCallbackUrl = callbackUrl,
            defaultExpiryMinutes = defaultExpiryText.toIntOrNull() ?: 15
        )
    }

    private fun refreshSystemStatus() {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val cn = ComponentName(this, PaymentNotificationService::class.java).flattenToString()
        notifEnabled = flat.split(":").any { it.equals(cn, ignoreCase = true) }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun syncDashboard() {
        val cfg = ConfigStore.load(this)
        if (cfg.apiKey.isBlank() || cfg.webhookUrl.isBlank()) {
            dashboardData = DashboardData(
                ok = false,
                error = "Isi Webhook URL & API Key di tab Setting untuk menampilkan omzet & transaksi."
            )
            return
        }
        isSyncing = true
        ioExecutor.execute {
            val data = WebhookClient.fetchDashboard(cfg, statusFilter, minAmountFilter, maxAmountFilter)
            runOnUiThread {
                isSyncing = false
                dashboardData = data
                if (data.ok && callbackUrl.isBlank() && data.callbackUrl.isNotBlank()) {
                    callbackUrl = data.callbackUrl
                }
            }
        }
    }

    private fun loadLauncherApps(): List<InstalledAppItem> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        val map = linkedMapOf<String, InstalledAppItem>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue
            val label = ri.loadLabel(pm).toString().trim().ifBlank { pkg }
            map.putIfAbsent(pkg, InstalledAppItem(pkg, label))
        }
        return map.values.sortedBy { it.label.lowercase() }
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
