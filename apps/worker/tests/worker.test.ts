import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { computeCrc16CcittFalse, verifyQrisCrc, parseQrisMetadata } from '@qris-selfhost/core';
import app from '../src/index';

function buildSignedTestQris(rawWithoutCrc: string): string {
  const body = `${rawWithoutCrc}6304`;
  return `${body}${computeCrc16CcittFalse(body)}`;
}

const SAMPLE_STATIC_QRIS = buildSignedTestQris(
  '000201' +
    '010211' +
    '26330015ID.SAMPLE.MERCH01101234567890' +
    '51440014ID.CO.QRIS.WWW0215ID00000000000010303UMI' +
    '52045411' +
    '5303360' +
    '5802ID' +
    '5910TEST STORE' +
    '6007JAKARTA' +
    '610510110' +
    '62070703A01'
);

function createMockD1() {
  const rawDb = new DatabaseSync(':memory:');
  const initSql = fs.readFileSync(path.resolve(process.cwd(), 'migrations/0001_init.sql'), 'utf8');
  rawDb.exec(initSql);

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

test('Worker E2E: buat 2 invoice unik, mutasi Shopee Partner melunasi invoice yang tepat, callback & heartbeat aktif', async () => {
  const { d1 } = createMockD1();
  const callbacks: Array<{ url: string; body: any; sig: string }> = [];

  const origFetch = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    callbacks.push({
      url,
      body: JSON.parse(String(init?.body || '{}')),
      sig: String((init?.headers as Record<string, string>)?.['x-gateway-signature'] || ''),
    });
    return new Response('ok', { status: 200 });
  }) as typeof fetch;

  try {
    const env = {
      DB: d1,
      ENVIRONMENT: 'test',
      STATIC_QRIS_STRING: SAMPLE_STATIC_QRIS,
      API_KEY: 'api-key-test-123',
      WEBHOOK_SECRET: 'webhook-secret-test-456',
      CALLBACK_SIGNING_SECRET: 'cb-secret-789',
    };

    const r1 = await app.request(
      '/v1/invoices',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({
          externalOrderId: 'ORD-001',
          baseAmount: 10000,
          callbackUrl: 'https://store.example.com/hook',
        }),
      },
      env as any
    );
    assert.equal(r1.status, 201);
    const inv1: any = await r1.json();
    assert.equal(verifyQrisCrc(inv1.data.qrString), true);
    assert.equal(parseQrisMetadata(inv1.data.qrString).amount, inv1.data.totalAmount);

    const r2 = await app.request(
      '/v1/invoices',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer api-key-test-123' },
        body: JSON.stringify({ externalOrderId: 'ORD-002', baseAmount: 10000 }),
      },
      env as any
    );
    assert.equal(r2.status, 201);
    const inv2: any = await r2.json();
    assert.notEqual(inv1.data.totalAmount, inv2.data.totalAmount);

    // Mutasi masuk sesuai totalAmount inv1
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
          deviceId: 'pixel-backup-01',
          message: `Pembayaran sebesar Rp${formatted} diterimaPembayaran sebesar Rp${formatted} telah diterima pada transaksi 145634304540423941.`,
        }),
      },
      env as any
    );
    assert.equal(hookRes.status, 200);
    const hookJson: any = await hookRes.json();
    assert.equal(hookJson.outcome, 'MATCHED_PAID');
    assert.equal(hookJson.externalOrderId, 'ORD-001');

    // Callback terkirim dengan HMAC signature
    assert.equal(callbacks.length, 1);
    assert.equal(callbacks[0].body.externalOrderId, 'ORD-001');
    assert.ok(callbacks[0].sig.length === 64);

    // Heartbeat Android Listener
    const hbRes = await app.request(
      '/v1/device/heartbeat',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-gateway-secret': 'webhook-secret-test-456',
        },
        body: JSON.stringify({ deviceId: 'pixel-backup-01', batteryPct: 88, networkType: '4G' }),
      },
      env as any
    );
    assert.equal(hbRes.status, 200);

    const statusRes = await app.request(
      '/v1/device/status',
      { headers: { Authorization: 'Bearer api-key-test-123' } },
      env as any
    );
    const statusJson: any = await statusRes.json();
    assert.equal(statusJson.devices[0].device_id, 'pixel-backup-01');
    assert.equal(statusJson.devices[0].battery_pct, 88);
  } finally {
    globalThis.fetch = origFetch;
  }
});
