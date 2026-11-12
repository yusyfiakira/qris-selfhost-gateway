import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { computeCrc16CcittFalse, verifyQrisCrc, parseQrisMetadata } from '@qris-selfhost/core';
import { app, runScheduledTasks } from '../src/index';

function buildSignedTestQris(rawWithoutCrc: string, merchantName = 'TEST STORE'): string {
  const nameTag = `59${String(merchantName.length).padStart(2, '0')}${merchantName}`;
  const body =
    `000201` +
    `010211` +
    `26330015ID.SAMPLE.MERCH01101234567890` +
    `51440014ID.CO.QRIS.WWW0215ID00000000000010303UMI` +
    `52045411` +
    `5303360` +
    `5802ID` +
    nameTag +
    `6007JAKARTA` +
    `610510110` +
    `62070703A01` +
    `6304`;
  return `${body}${computeCrc16CcittFalse(body)}`;
}

const SAMPLE_STATIC_QRIS = buildSignedTestQris('TEST STORE', 'TEST STORE');
const SECOND_STATIC_QRIS = buildSignedTestQris('SECOND SHOP', 'SECOND SHOP');

function createMockD1() {
  const rawDb = new DatabaseSync(':memory:');
  const mig1 = fs.readFileSync(path.resolve(process.cwd(), 'migrations/0001_init.sql'), 'utf8');
  const mig2 = fs.readFileSync(path.resolve(process.cwd(), 'migrations/0002_callback_and_settings.sql'), 'utf8');
  rawDb.exec(mig1);
  rawDb.exec(mig2);

  const d1: any = {
    prepare(query: string) {
      let bound: any[] = [];
      return {
        bind(...params: any[]) {
          bound = params.map((p) => (p === undefined ? null : p));
          return this;
        },
        async first(col?: string) {
          const row: any = rawDb.prepare(query).get(...bound);
          if (!row) return null;
          return col ? row[col] : row;
        },
        async all() {
          const results = rawDb.prepare(query).all(...bound);
          return { results, success: true, meta: { duration: 0 } };
        },
        async run() {
          const info = rawDb.prepare(query).run(...bound);
          return {
            results: [],
            success: true,
            meta: { changes: Number(info.changes), last_row_id: Number(info.lastInsertRowid) },
          };
        },
      };
    },
  };

  return { rawDb, d1 };
}

test('Worker E2E: durasi 24 jam, cancel membebaskan slot, callback retry, Telegram alert, & ganti QRIS via D1', async () => {
  const { rawDb, d1 } = createMockD1();
  const callbacks: Array<{ url: string; body: any; sig: string }> = [];
  const telegramAlerts: string[] = [];
  let callbackShouldFail = true;

  const origFetch = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('api.telegram.org/')) {
      const body = JSON.parse(String(init?.body || '{}'));
      telegramAlerts.push(String(body.text || ''));
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    }
    if (callbackShouldFail) {
      return new Response('Store temporarily down', { status: 503 });
    }
    callbacks.push({
      url,
      body: JSON.parse(String(init?.body || '{}')),
      sig: String((init?.headers as Record<string, string>)?.['x-gateway-signature'] || ''),
    });
    return new Response('ok', { status: 200 });
  }) as typeof fetch;

  try {
    const env: any = {
      DB: d1,
      ENVIRONMENT: 'test',
      STATIC_QRIS_STRING: SAMPLE_STATIC_QRIS,
      API_KEY: 'api-key-test-123',
      WEBHOOK_SECRET: 'webhook-secret-test-456',
      CALLBACK_SIGNING_SECRET: 'cb-secret-789',
      TELEGRAM_BOT_TOKEN: '123456:mock_tg_token',
      TELEGRAM_CHAT_ID: '999888777',
      OFFLINE_ALERT_MINUTES: '20',
    };

    // 1. Buat invoice dengan durasi 24 jam (1440 menit)
    const r1 = await app.request(
      '/v1/invoices',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({
          externalOrderId: 'ORD-24H',
          baseAmount: 10000,
          expiryMinutes: 1440,
          callbackUrl: 'https://store.example.com/hook',
        }),
      },
      env
    );
    assert.equal(r1.status, 201);
    const inv1: any = await r1.json();
    assert.equal(inv1.data.expiryMinutes, 1440);
    assert.equal(verifyQrisCrc(inv1.data.qrString), true);

    // 2. Buat invoice kedua lalu cancel -> memastikan cancel membebaskan slot unik
    const r2 = await app.request(
      '/v1/invoices',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({ externalOrderId: 'ORD-CANCEL', baseAmount: 10000 }),
      },
      env
    );
    const inv2: any = await r2.json();
    assert.notEqual(inv1.data.totalAmount, inv2.data.totalAmount);

    const cancelRes = await app.request(
      `/v1/invoices/${inv2.data.id}/cancel`,
      { method: 'POST', headers: { Authorization: 'Bearer api-key-test-123' } },
      env
    );
    assert.equal(cancelRes.status, 200);
    const cancelJson: any = await cancelRes.json();
    assert.equal(cancelJson.status, 'CANCELLED');

    // 3. Mutasi masuk melunasi inv1; callback toko gagal 503 -> callbackStatus harus PENDING retry + Telegram sukses terkirim
    const formatted = inv1.data.totalAmount.toLocaleString('id-ID');
    const hookRes = await app.request(
      '/v1/webhook/mutation',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-gateway-secret': 'webhook-secret-test-456',
        },
        body: JSON.stringify({
          deviceId: 'android-test-01',
          message: `Pembayaran sebesar Rp${formatted} telah diterima pada transaksi 145634304540423941.`,
        }),
      },
      env
    );
    assert.equal(hookRes.status, 200);
    const hookJson: any = await hookRes.json();
    assert.equal(hookJson.outcome, 'MATCHED_PAID');
    assert.ok(telegramAlerts.some((m) => m.includes('PEMBAYARAN QRIS LUNAS') && m.includes('ORD-24H')));

    const checkInv1: any = await (
      await app.request(`/v1/invoices/${inv1.data.id}`, {
        headers: { Authorization: 'Bearer api-key-test-123' },
      }, env)
    ).json();
    assert.equal(checkInv1.data.status, 'PAID');
    assert.equal(checkInv1.data.callbackStatus, 'PENDING');
    assert.equal(checkInv1.data.callbackAttempts, 1);
    assert.equal(checkInv1.data.callbackHttpCode, 503);

    // 4. Simulasi toko pulih lalu Cron scheduled retry berjalan -> callbackStatus menjadi DELIVERED
    callbackShouldFail = false;
    rawDb
      .prepare(`UPDATE invoices SET callback_next_retry_at = '2020-01-01T00:00:00.000Z' WHERE id = ?`)
      .run(inv1.data.id);
    await runScheduledTasks(env, Date.now());

    const afterRetry: any = await (
      await app.request(`/v1/invoices/${inv1.data.id}`, {
        headers: { Authorization: 'Bearer api-key-test-123' },
      }, env)
    ).json();
    assert.equal(afterRetry.data.callbackStatus, 'DELIVERED');
    assert.equal(afterRetry.data.callbackAttempts, 2);
    assert.equal(callbacks.length, 1);
    assert.equal(callbacks[0].body.externalOrderId, 'ORD-24H');
    assert.equal(callbacks[0].sig.length, 64);

    // 5. Endpoint GET /v1/mutations menampilkan log mutasi
    const mutRes: any = await (
      await app.request('/v1/mutations?outcome=MATCHED_PAID', {
        headers: { Authorization: 'Bearer api-key-test-123' },
      }, env)
    ).json();
    assert.equal(mutRes.mutations.length, 1);
    assert.equal(mutRes.mutations[0].matched_invoice_id, inv1.data.id);

    // 6. Ganti QRIS statis harus ditolak jika hanya pakai WEBHOOK_SECRET, dan sukses jika pakai API_KEY
    const unauthorizedQrisRes = await app.request(
      '/v1/config/qris',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'x-gateway-secret': 'webhook-secret-test-456' },
        body: JSON.stringify({ staticQrisString: SECOND_STATIC_QRIS }),
      },
      env
    );
    assert.equal(unauthorizedQrisRes.status, 401);

    const updateQrisRes = await app.request(
      '/v1/config/qris',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({ staticQrisString: SECOND_STATIC_QRIS, deviceId: 'android-test-01' }),
      },
      env
    );
    assert.equal(updateQrisRes.status, 200);
    assert.ok(telegramAlerts.some((m) => m.includes('PERUBAHAN QRIS MERCHANT') && m.includes('SECOND SHOP')));

    // Invoice baru setelah update QRIS harus memakai merchant baru (SECOND SHOP)
    const r3: any = await (
      await app.request(
        '/v1/invoices',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
          body: JSON.stringify({ externalOrderId: 'ORD-NEW-QR', baseAmount: 15000 }),
        },
        env
      )
    ).json();
    assert.equal(parseQrisMetadata(r3.data.qrString).merchantName, 'SECOND SHOP');

    // 7. Heartbeat offline > 20 menit memicu 1x Telegram alert (tanpa spam ulang)
    rawDb
      .prepare(
        `INSERT INTO device_heartbeats (device_id, app_version, battery_pct, network_type, last_seen_at)
         VALUES ('android-stale-01', '0.1.0', 12, 'cellular', '2020-01-01T00:00:00.000Z')`
      )
      .run();
    const beforeOfflineCount = telegramAlerts.filter((m) => m.includes('LISTENER ANDROID OFFLINE')).length;
    await runScheduledTasks(env, Date.now());
    await runScheduledTasks(env, Date.now() + 60_000);
    const afterOfflineCount = telegramAlerts.filter((m) => m.includes('LISTENER ANDROID OFFLINE')).length;
    assert.equal(afterOfflineCount - beforeOfflineCount, 1);
    // 8. Default Callback URL & Expiry via D1 + GET /v1/invoices summary
    const cbSaveRes = await app.request(
      '/v1/config/callback',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({
          callbackUrl: 'https://store.example.com/default-hook',
          defaultExpiryMinutes: 30,
        }),
      },
      env
    );
    assert.equal(cbSaveRes.status, 200);
    const cbSaveJson: any = await cbSaveRes.json();
    assert.equal(cbSaveJson.callbackUrl, 'https://store.example.com/default-hook');
    assert.equal(cbSaveJson.defaultExpiryMinutes, 30);

    const invListRes: any = await (
      await app.request('/v1/invoices?status=PAID', {
        headers: { Authorization: 'Bearer api-key-test-123' },
      }, env)
    ).json();
    assert.equal(invListRes.success, true);
    assert.equal(invListRes.summary.totalPaidCount, 1);
    assert.equal(invListRes.invoices.length, 1);
    assert.equal(invListRes.invoices[0].id, inv1.data.id);
  } finally {
    globalThis.fetch = origFetch;
  }
});
