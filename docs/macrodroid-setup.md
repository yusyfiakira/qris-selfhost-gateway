# Alternatif Listener Menggunakan MacroDroid

Jika belum ingin memasang APK Companion App, kamu bisa memakai **MacroDroid**:

1. Aktifkan **Notification Access** untuk MacroDroid & matikan **Battery Optimization**.
2. **Trigger:** `Device Events → Notification → Notification Received`
   - Pilih aplikasi merchant resmi saja (mis. **Shopee Partner**, **GoBiz**, **DANA**).
   - *Text Content:* `Contains` → `Rp`.
3. **Action:** `Connectivity → HTTP Request`
   - **Method:** `POST`
   - **URL:** `https://<worker-url>/v1/webhook/mutation`
   - **Header Params:** `x-gateway-secret` = `<WEBHOOK_SECRET>`
   - **Content Type:** `application/x-www-form-urlencoded`
   - **Content Body:** `message={not_title} {notification}` (gunakan tombol `...` Magic Text).
