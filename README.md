# QRIS Self-Hosted Gateway

Self-hosted **EMVCo Dynamic QRIS Payment Gateway** (MDR 0%, dana langsung masuk ke rekening/merchant milik sendiri) berbasis **Cloudflare Workers + D1** dengan **Android Companion App (Kotlin)** maupun **MacroDroid**.

## Struktur Monorepo

| Workspace / Folder | Deskripsi |
| --- | --- |
| [`packages/core`](packages/core) | `@qris-selfhost/core` — Zero-dependency TypeScript SDK (EMVCo TLV, CRC16 CCITT-FALSE, alokasi kode unik, parser notifikasi bank/e-wallet Indonesia). |
| [`apps/worker`](apps/worker) | `@qris-selfhost/worker` — Standalone API Gateway di Cloudflare Workers + D1 (`POST /v1/invoices`, `GET /v1/invoices/:id`, `POST /v1/webhook/mutation`, heartbeat device, callback HMAC-SHA256). |
| [`apps/android-listener`](apps/android-listener) | Aplikasi Android ringan (Kotlin native, R8 shrink) dengan `NotificationListenerService`, live delivery log, offline retry queue (`WorkManager`), dan heartbeat ping. |
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
   ```
4. Deploy:
   ```bash
   npx wrangler deploy
   ```

## Alur Kerja Pembayaran

1. Toko online memanggil `POST /v1/invoices` (`Authorization: Bearer <API_KEY>`) dengan `externalOrderId` dan `baseAmount`.
2. Gateway mengalokasikan kode unik `1..499` yang bebas bentrok terhadap invoice `PENDING` aktif lain dalam jendela 15 menit, lalu men-generate `qrString` EMVCo dinamis (`Tag 01 = 12`, `Tag 54 = totalAmount`, `Tag 6304 = CRC16`).
3. Pembeli scan QRIS → dana masuk ke rekening/merchant kamu → Android Companion App (atau MacroDroid) meneruskan notifikasi ke `POST /v1/webhook/mutation`.
4. Gateway mencocokkan nominal persis terhadap invoice `PENDING` yang belum kedaluwarsa, mengubah status menjadi `PAID` secara atomik, dan mengirim callback bertanda tangan HMAC-SHA256 (`x-gateway-signature`) ke `callbackUrl` toko.
