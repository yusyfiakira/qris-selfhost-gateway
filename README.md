# QRIS Self-Hosted Gateway

Self-hosted **EMVCo Dynamic QRIS Payment Gateway** (MDR 0%, dana langsung masuk ke rekening/merchant milik sendiri) berbasis **Cloudflare Workers + D1** dengan **Android Companion App (Kotlin)** maupun **MacroDroid**.

## Struktur Monorepo

| Workspace / Folder | Deskripsi |
| --- | --- |
| [`packages/core`](packages/core) | `@qris-selfhost/core` — Zero-dependency TypeScript SDK (EMVCo TLV, CRC16 CCITT-FALSE, alokasi kode unik, parser notifikasi bank/e-wallet Indonesia). |
| [`apps/worker`](apps/worker) | `@qris-selfhost/worker` — Standalone API Gateway di Cloudflare Workers + D1 (`POST /v1/invoices`, `CANCEL`, `PUT /v1/config/qris`, callback retry Cron, Telegram alert, heartbeat). |
| [`apps/android-listener`](apps/android-listener) | Aplikasi Android ringan (Kotlin native v0.2) dengan Visual App Picker, 3 mode filter teks + custom keywords, Export/Import JSON, ganti QRIS dari galeri, offline retry (`WorkManager`), dan heartbeat ping. |
| [`examples/store-adapter.ts`](examples/store-adapter.ts) | Contoh helper client toko & verifikasi HMAC-SHA256 callback `x-gateway-signature`. |
| [`docs/macrodroid-setup.md`](docs/macrodroid-setup.md) | Panduan alternatif tanpa compile APK menggunakan MacroDroid. |

## Quickstart (Core SDK & Worker Gateway)

```bash
npm install
npm test
npm run typecheck
```

### Deploy Standalone Worker ke Cloudflare (Free Tier)

1. Masuk ke `apps/worker` dan salin template konfigurasi:
   ```bash
   cd apps/worker
   cp wrangler.toml.example wrangler.toml
   cp .dev.vars.example .dev.vars
   ```
2. Buat D1 database baru lalu masukkan `database_id` ke `wrangler.toml`:
   ```bash
   npx wrangler d1 create qris-gateway-db
   npx wrangler d1 migrations apply qris-gateway-db --remote
   ```
3. Set secrets di Cloudflare Workers:
   ```bash
   npx wrangler secret put STATIC_QRIS_STRING
   npx wrangler secret put API_KEY
   npx wrangler secret put WEBHOOK_SECRET
   npx wrangler secret put CALLBACK_SIGNING_SECRET
   # Opsional untuk notifikasi Telegram sukses, callback gagal, ganti QRIS, & HP offline:
   npx wrangler secret put TELEGRAM_BOT_TOKEN
   npx wrangler secret put TELEGRAM_CHAT_ID
   ```
4. Deploy:
   ```bash
   npx wrangler deploy
   ```

## Ringkasan API Worker

- `POST /v1/invoices` (`Bearer API_KEY`) — buat invoice QRIS dinamis (`expiryMinutes`: `1`–`1440` menit / 24 jam).
- `GET /v1/invoices/:id` (`Bearer API_KEY`) — cek status invoice + status pengiriman callback (`DELIVERED` / `PENDING` / `FAILED`).
- `POST /v1/invoices/:id/cancel` (`Bearer API_KEY`) — batalkan invoice `PENDING` dan bebaskan slot kode unik.
- `POST /v1/invoices/:id/retry-callback` (`Bearer API_KEY`) — retry manual callback toko.
- `GET /v1/mutations` (`Bearer API_KEY`) — riwayat mutasi masuk dari listener.
- `GET /v1/config/qris` & `PUT /v1/config/qris` (`Bearer API_KEY`) — baca metadata / ganti QRIS statis aktif di D1 (dengan validasi CRC16 + alert Telegram).
- `POST /v1/webhook/mutation` (`x-gateway-secret`) — penerima notifikasi pembayaran masuk.
- `POST /v1/device/heartbeat` (`x-gateway-secret`) & `GET /v1/device/status` (`Bearer API_KEY`).
