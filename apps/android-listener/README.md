# QRIS Listener — Android Companion App (Kotlin Native)

Aplikasi pendamping ringan untuk meneruskan notifikasi pembayaran masuk (Shopee Partner, GoPay Merchant / GoBiz, DANA, myBCA, Livin' Merchant, BRImerchant) ke endpoint `/v1/webhook/mutation`.

## Fitur Utama
- **Log Status Real-Time di Layar HP:** Langsung terlihat apakah notifikasi berhasil melunasi invoice (`✅ LUNAS`), diabaikan (`⚪ DIABAIKAN`), ditolak secret (`❌ DITOLAK`), atau masuk antrean retry (`🔁`).
- **Offline Retry Queue (`WorkManager`):** Jika koneksi 4G/5G sempat terputus saat notifikasi masuk, pengiriman otomatis diulang dengan exponential backoff begitu internet kembali.
- **Heartbeat Monitor (`15 menit`):** Mengirim status online + level baterai ke `/v1/device/heartbeat`.
- **Penyimpanan Aman:** URL dan `WEBHOOK_SECRET` disimpan dengan `EncryptedSharedPreferences`.

## Cara Build APK
- **Via GitHub Actions (Tanpa install Android Studio):** Workflow [`.github/workflows/android-apk.yml`](../../.github/workflows/android-apk.yml) otomatis membangun APK debug & release setiap ada perubahan di `apps/android-listener/**`.
- **Via Android Studio:** Buka folder `apps/android-listener`, tunggu Gradle sync selesai, lalu jalankan **Build → Build APK(s)**.

## Catatan Penting Android 13+ (Sideload APK)
Jika APK di-install langsung di luar Play Store:
1. Buka **Settings → Apps → QRIS Listener**.
2. Tap ikon **⋮ (titik tiga di pojok kanan atas)** → pilih **Allow restricted settings**.
3. Baru aktifkan **Notification Access** dari tombol **Izin Notifikasi** di dalam aplikasi.
4. Tekan **Bebas Baterai** (dan aktifkan *Autostart* bila memakai Xiaomi/Oppo/Vivo) agar service tidak dimatikan di latar belakang.
