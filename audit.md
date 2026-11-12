# Audit & Roadmap Peningkatan — `qris-selfhost-gateway`

**Tanggal Audit:** 2026-11-12  
**Status Baseline:** Terverifikasi End-to-End (Cloudflare Workers + D1 + Android Companion APK `0.1.0` pada jaringan seluler, uji transaksi riil `Rp 1.024` sukses berstatus `PAID`).

---

## 1. Apa yang Sudah Terbukti Jalan (Baseline)

- **Core SDK (`@qris-selfhost/core`):**
  - EMVCo Dynamic QRIS generator + validator (`Tag 01 = 12`, `Tag 54 = nominal`, `Tag 6304 = CRC16 CCITT-FALSE`).
  - Parser notifikasi mutasi Indonesia (`Shopee Partner`, `GoPay Merchant/GoBiz`, `DANA`, `BCA`, `BRI`, `Mandiri`) + penolakan mutasi keluar (*outgoing transfer guard*).
  - Alokasi kode unik bebas bentrok (`1..499`).
- **Standalone Worker Gateway (`@qris-selfhost/worker`):**
  - `POST /v1/invoices`, `GET /v1/invoices/:id`, `POST /v1/webhook/mutation`, `POST /v1/device/heartbeat`, `GET /v1/device/status`, `GET /v1/setup/android-config`.
  - Proteksi `API_KEY` dan `WEBHOOK_SECRET` berbasis `constantTimeEqual`, atomic claim `PENDING -> PAID`, serta penandatanganan callback HMAC-SHA256 (`x-gateway-signature`).
- **Android Companion App (`apps/android-listener`):**
  - APK terbuild otomatis via GitHub Actions (`android-apk.yml`).
  - Berjalan di Android dengan `NotificationListenerService` + Foreground Notification, `EncryptedSharedPreferences`, deduplikasi notifikasi 3 detik, offline retry queue (`WorkManager`), heartbeat 15 menit, dan live log pengiriman di layar HP.

---

## 2. Temuan & Keputusan Desain (Disetujui)

### A. Arsitektur Gateway (`apps/worker`)
1. **Durasi Expired QRIS Fleksibel (`15m`, `30m`, `1j`, `12j`, `24j`) + Cancel Endpoint:**
   - Perluas batas `expiryMinutes` di `POST /v1/invoices` dari `1..120` menjadi `1..1440` menit (maksimal 24 jam), dengan `DEFAULT_EXPIRY_MINUTES` configurable.
   - Dukung `UNIQUE_CODE_MAX` hingga `999` untuk mengakomodasi TTL panjang (12–24 jam) agar slot nominal tidak cepat penuh.
   - Tambah endpoint `POST /v1/invoices/:id/cancel` agar saat order dibatalkan di web toko, slot kode unik langsung dilepas saat itu juga.
2. **Webhook Callback Tracking, Retry, & Observability:**
   - Catat status pengiriman callback ke toko pada D1 (`callback_status`: `NONE | PENDING | DELIVERED | FAILED`, `callback_attempts`, `callback_http_code`, `callback_last_error`, `callback_delivered_at`).
   - Tambah **Cloudflare Cron Trigger** (`*/1 * * * *`) untuk auto-retry callback gagal (backoff eksponensial maks 5×) + endpoint manual `POST /v1/invoices/:id/retry-callback` dan `GET /v1/mutations`.
3. **Notifikasi Bot Telegram Admin (Opsional):**
   - Konfigurasi via secret `TELEGRAM_BOT_TOKEN` & `TELEGRAM_CHAT_ID`.
   - Kirim alert untuk 4 kejadian:
     1. Transaksi sukses (`MATCHED_PAID`) beserta status callback toko.
     2. Callback ke toko gagal permanen setelah retry.
     3. Listener HP offline (heartbeat terakhir > 20 menit, dicek via Cron Trigger).
     4. Perubahan `STATIC_QRIS_STRING` aktif (anti-hijack alert).
4. **Ganti QRIS Statis Dinamis via D1 (`gateway_settings`):**
   - Tambah tabel `gateway_settings` di D1 untuk menyimpan QRIS statis aktif (dan opsi multi-preset QRIS); saat `POST /v1/invoices`, baca D1 terlebih dahulu lalu fallback ke `env.STATIC_QRIS_STRING`.
   - Endpoint `PUT /v1/config/qris` wajib terproteksi **`API_KEY` / `ADMIN_KEY`** (terpisah dari `WEBHOOK_SECRET`), memvalidasi CRC16 via `verifyQrisCrc`, dan wajib memicu alert Telegram Admin saat QRIS diganti.

### B. UX & Fitur Android Companion App (`apps/android-listener`)
1. **Visual App Picker (Checklist):**
   - Gunakan `<queries>` `ACTION_MAIN` + `CATEGORY_LAUNCHER` di `AndroidManifest.xml` (tanpa perlu izin sensitif `QUERY_ALL_PACKAGES`).
   - Tampilkan dialog checklist aplikasi terpasang (ikon + nama aplikasi + package name) sehingga pengguna tinggal mencentang app merchant tanpa mengetik package name manual.
2. **Filter Teks Notifikasi (3 Mode + Custom Keywords):**
   - **Mode 1 — Standar Pembayaran *(Default)*:** wajib memuat `Rp` / `IDR` dan otomatis menolak kata mutasi keluar.
   - **Mode 2 — Semua Notif Mengandung Angka:** untuk aplikasi dengan format teks khusus.
   - **Mode 3 — Filter Kustom:** input `includeKeywords` (pisah koma, mis. `diterima, masuk, QRIS`) dan `excludeKeywords` (pisah koma, mis. `promo, cashback, diskon, voucher`) agar notif promo langsung dibuang di HP.
3. **Export & Import Config Lengkap:**
   - Export/Import file `.json` (atau Copy/Paste / Share Sheet) mencakup `webhookUrl`, `heartbeatUrl`, `deviceId`, `allowedPackages`, `filterMode`, `includeKeywords`, `excludeKeywords`.
   - Disertai opsi centang **"Sertakan Secret"** dan peringatan keamanan saat mengekspor.
4. **Menu Admin Ganti Kode QRIS dari HP:**
   - Halaman/dialog khusus berproteksi input `API_KEY` (tidak disimpan di service background listener) untuk memilih gambar QRIS dari Galeri / scan / tempel string → decode & preview nama merchant → kirim ke `PUT /v1/config/qris`.
5. **Distribusi APK:**
   - Tambah job auto-release APK di GitHub Releases pada tag `v*` di `.github/workflows/android-apk.yml` (+ opsi signing keystore via GitHub Secrets).

### C. Integrasi Testbed ST'ZEN Staging (`d:\stzen-staging-custom-qris`)
1. **Pola Konsumsi Gateway Eksternal Resmi:**
   - Tambahkan adapter HTTP di ST'ZEN staging (`RemoteQrisGatewayAdapter`) yang memanggil `POST /v1/invoices` pada Worker standalone `qris-selfhost-gateway` dan memverifikasi callback HMAC-SHA256 `x-gateway-signature` di `/api/webhook/qris-gateway`.
   - Rapikan adapter tersebut sebagai referensi resmi di folder `examples/` repo `qris-selfhost-gateway` (Hono, Next.js, Express).

---

## 3. Urutan Eksekusi Terencana

1. **Fase 1 — Upgrade `apps/worker` (Migrasi `0002` + API):**
   - Durasi QRIS hingga 24 jam (`1..1440` menit) + `POST /v1/invoices/:id/cancel`.
   - Tabel `gateway_settings`, `PUT /v1/config/qris`, callback tracking + Cron retry, `GET /v1/mutations`, dan Telegram Bot Alert + offline heartbeat alert.
2. **Fase 2 — Upgrade `apps/android-listener` (UX & Fitur Admin):**
   - Visual App Picker Checklist, 3 Mode Filter Teks + Custom Keywords, Export/Import Config JSON, dan Menu Ganti QRIS (Galeri/Paste) berproteksi `API_KEY`.
   - Auto-release APK di GitHub Releases (`v*`).
3. **Fase 3 — End-to-End Integration ke ST'ZEN Staging + `examples/`:**
   - Sambungkan `d:\stzen-staging-custom-qris` ke Worker live via `RemoteQrisGatewayAdapter` + verifikasi HMAC callback + uji transaksi riil Rp 1.000.
