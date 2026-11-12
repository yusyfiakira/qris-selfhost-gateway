import { Hono } from 'hono';
import {
  generateDynamicQris,
  parseMutationNotification,
  parseQrisMetadata,
  selectAvailableUniqueAmount,
} from '@qris-selfhost/core';
import { constantTimeEqual, signCallbackPayload } from './security';

export interface EnvBindings {
  DB: D1Database;
  ENVIRONMENT?: string;
  STATIC_QRIS_STRING?: string;
  API_KEY?: string;
  WEBHOOK_SECRET?: string;
  CALLBACK_SIGNING_SECRET?: string;
  DEFAULT_CALLBACK_URL?: string;
  DEFAULT_EXPIRY_MINUTES?: string;
  UNIQUE_CODE_MIN?: string;
  UNIQUE_CODE_MAX?: string;
}

const app = new Hono<{ Bindings: EnvBindings }>();

function extractBearer(authHeader?: string): string {
  if (!authHeader) return '';
  const match = authHeader.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : '';
}

function requireApiAuth(c: any): Response | null {
  const configured = String(c.env?.API_KEY || '').trim();
  const provided = extractBearer(c.req.header('Authorization')) || String(c.req.header('x-api-key') || '').trim();
  if (!configured || !constantTimeEqual(provided, configured)) {
    return c.json({ success: false, message: 'Unauthorized API key' }, 401);
  }
  return null;
}

function requireWebhookAuth(c: any): Response | null {
  const configured = String(c.env?.WEBHOOK_SECRET || '').trim();
  const provided = String(
    c.req.header('x-gateway-secret') || c.req.header('x-custom-gateway-secret') || ''
  ).trim();
  if (!configured || !constantTimeEqual(provided, configured)) {
    return c.json({ success: false, message: 'Unauthorized webhook secret' }, 401);
  }
  return null;
}

async function parseFlexibleBody(c: any): Promise<any> {
  try {
    return await c.req.json();
  } catch {
    try {
      return await c.req.parseBody();
    } catch {
      return {};
    }
  }
}

function runBackground(c: any, task: () => Promise<void>) {
  try {
    const ctx = c.executionCtx;
    if (ctx && typeof ctx.waitUntil === 'function') {
      ctx.waitUntil(task());
      return;
    }
  } catch {
    // Non-Worker environment
  }
  return task();
}

app.get('/health', (c) => {
  const staticQris = String(c.env?.STATIC_QRIS_STRING || '').trim();
  let merchantName: string | undefined;
  if (staticQris) {
    try {
      merchantName = parseQrisMetadata(staticQris).merchantName;
    } catch {}
  }
  return c.json({
    status: 'ok',
    service: 'qris-selfhost-gateway',
    merchantName: merchantName || null,
    timestamp: new Date().toISOString(),
  });
});

/**
 * Membuat invoice QRIS dinamis dengan kode unik bebas bentrok (1..499).
 */
app.post('/v1/invoices', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const db = c.env.DB;
  const staticQris = String(c.env?.STATIC_QRIS_STRING || '').trim();
  if (!staticQris) {
    return c.json({ success: false, message: 'STATIC_QRIS_STRING is not configured' }, 500);
  }

  const body = await parseFlexibleBody(c);
  const externalOrderId = String(body.externalOrderId || body.orderId || '').trim();
  const baseAmount = Math.round(Number(body.baseAmount ?? body.amount ?? 0));
  const expiryMinutes = Math.max(
    1,
    Math.min(120, Math.round(Number(body.expiryMinutes ?? c.env?.DEFAULT_EXPIRY_MINUTES ?? 15)))
  );
  const callbackUrl = String(body.callbackUrl || c.env?.DEFAULT_CALLBACK_URL || '').trim() || null;
  const metadataJson = body.metadata ? JSON.stringify(body.metadata) : null;

  if (!externalOrderId) {
    return c.json({ success: false, message: 'externalOrderId is required' }, 400);
  }
  if (!Number.isSafeInteger(baseAmount) || baseAmount <= 0) {
    return c.json({ success: false, message: 'baseAmount must be a positive integer' }, 400);
  }

  const codeMin = Math.max(1, Number(c.env?.UNIQUE_CODE_MIN || 1));
  const codeMax = Math.max(codeMin, Number(c.env?.UNIQUE_CODE_MAX || 499));

  const now = new Date();
  const nowIso = now.toISOString();
  const expiresAt = new Date(now.getTime() + expiryMinutes * 60 * 1000).toISOString();
  const invoiceId = `inv_${Date.now().toString(36)}_${crypto.randomUUID().slice(0, 8)}`;

  const triedCodes = new Set<number>();
  let chosen: { uniqueCode: number; totalAmount: number } | null = null;
  let qrString = '';

  for (let attempt = 0; attempt < 10; attempt++) {
    const activeRows = await db
      .prepare(
        `SELECT total_amount FROM invoices
         WHERE status = 'PENDING'
           AND expires_at > ?
           AND total_amount BETWEEN ? AND ?`
      )
      .bind(nowIso, baseAmount + codeMin, baseAmount + codeMax)
      .all<{ total_amount: number }>();

    const activeTotals = (activeRows.results || []).map((r) => Number(r.total_amount));
    const candidate = selectAvailableUniqueAmount(baseAmount, activeTotals, {
      min: codeMin,
      max: codeMax,
      excludeCodes: triedCodes,
    });

    if (!candidate) {
      return c.json(
        { success: false, message: 'No unique amount slots available for this base amount right now' },
        409
      );
    }

    triedCodes.add(candidate.uniqueCode);
    qrString = generateDynamicQris(staticQris, candidate.totalAmount);

    await db
      .prepare(
        `INSERT INTO invoices (id, external_order_id, base_amount, unique_code, total_amount, qr_string, status, callback_url, metadata_json, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           unique_code = excluded.unique_code,
           total_amount = excluded.total_amount,
           qr_string = excluded.qr_string`
      )
      .bind(
        invoiceId,
        externalOrderId,
        baseAmount,
        candidate.uniqueCode,
        candidate.totalAmount,
        qrString,
        callbackUrl,
        metadataJson,
        expiresAt
      )
      .run();

    // Race check: pastikan tidak ada invoice PENDING lain dengan total_amount identik
    const collision = await db
      .prepare(
        `SELECT id FROM invoices
         WHERE status = 'PENDING' AND expires_at > ? AND total_amount = ? AND id != ?
         LIMIT 1`
      )
      .bind(nowIso, candidate.totalAmount, invoiceId)
      .first<{ id: string }>();

    if (!collision) {
      chosen = candidate;
      break;
    }
  }

  if (!chosen) {
    await db.prepare('DELETE FROM invoices WHERE id = ?').bind(invoiceId).run();
    return c.json({ success: false, message: 'Failed to allocate unique amount after retries' }, 409);
  }

  return c.json(
    {
      success: true,
      data: {
        id: invoiceId,
        externalOrderId,
        baseAmount,
        uniqueCode: chosen.uniqueCode,
        totalAmount: chosen.totalAmount,
        qrString,
        status: 'PENDING',
        expiresAt,
      },
    },
    201
  );
});

/**
 * Mengambil status invoice saat ini (otomatis menandai EXPIRED bila melewati expiresAt).
 */
app.get('/v1/invoices/:id', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const id = c.req.param('id');
  const row = await c.env.DB.prepare('SELECT * FROM invoices WHERE id = ? LIMIT 1').bind(id).first<any>();

  if (!row) {
    return c.json({ success: false, message: 'Invoice not found' }, 404);
  }

  const nowIso = new Date().toISOString();
  let status = row.status;
  if (status === 'PENDING' && row.expires_at <= nowIso) {
    await c.env.DB
      .prepare(`UPDATE invoices SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'`)
      .bind(id)
      .run();
    status = 'EXPIRED';
  }

  return c.json({
    success: true,
    data: {
      id: row.id,
      externalOrderId: row.external_order_id,
      baseAmount: row.base_amount,
      uniqueCode: row.unique_code,
      totalAmount: row.total_amount,
      qrString: row.qr_string,
      status,
      expiresAt: row.expires_at,
      paidAt: row.paid_at,
    },
  });
});

/**
 * Webhook penerima mutasi dari Android Companion App / MacroDroid / Google Apps Script.
 */
app.post('/v1/webhook/mutation', async (c) => {
  const authErr = requireWebhookAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const parsed = parseMutationNotification(body);
  const deviceId = String(body.deviceId || body.device_id || c.req.header('x-device-id') || '').trim() || null;

  if (!parsed) {
    return c.json({ status: 'error', message: 'Valid incoming IDR amount not found in payload' }, 400);
  }

  const nowIso = new Date().toISOString();
  const db = c.env.DB;
  const logId = `mut_${Date.now().toString(36)}_${crypto.randomUUID().slice(0, 6)}`;

  const match = await db
    .prepare(
      `SELECT * FROM invoices
       WHERE status = 'PENDING' AND total_amount = ? AND expires_at > ?
       ORDER BY created_at DESC LIMIT 1`
    )
    .bind(parsed.amount, nowIso)
    .first<any>();

  if (!match) {
    const alreadyPaid = await db
      .prepare(
        `SELECT id, external_order_id FROM invoices
         WHERE status = 'PAID' AND total_amount = ? AND expires_at > ?
         ORDER BY paid_at DESC LIMIT 1`
      )
      .bind(parsed.amount, nowIso)
      .first<any>();

    const outcome = alreadyPaid ? 'DUPLICATE_PAID' : 'IGNORED';
    await db
      .prepare(
        `INSERT INTO mutation_logs (id, amount, provider_hint, raw_text, matched_invoice_id, outcome, device_id)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(logId, parsed.amount, parsed.providerHint, parsed.rawText || null, alreadyPaid?.id || null, outcome, deviceId)
      .run();

    return c.json({
      status: alreadyPaid ? 'ok' : 'ignored',
      outcome,
      amount: parsed.amount,
      invoiceId: alreadyPaid?.id || null,
      externalOrderId: alreadyPaid?.external_order_id || null,
    });
  }

  // Atomic transition PENDING -> PAID
  const updateRes = await db
    .prepare(`UPDATE invoices SET status = 'PAID', paid_at = ? WHERE id = ? AND status = 'PENDING'`)
    .bind(nowIso, match.id)
    .run();

  const changes = Number(updateRes.meta?.changes ?? 0);
  if (changes === 0) {
    return c.json({
      status: 'ok',
      outcome: 'DUPLICATE_PAID',
      amount: parsed.amount,
      invoiceId: match.id,
      externalOrderId: match.external_order_id,
    });
  }

  await db
    .prepare(
      `INSERT INTO mutation_logs (id, amount, provider_hint, raw_text, matched_invoice_id, outcome, device_id)
       VALUES (?, ?, ?, ?, ?, 'MATCHED_PAID', ?)`
    )
    .bind(logId, parsed.amount, parsed.providerHint, parsed.rawText || null, match.id, deviceId)
    .run();

  if (match.callback_url) {
    const callbackPayload = JSON.stringify({
      event: 'invoice.paid',
      invoiceId: match.id,
      externalOrderId: match.external_order_id,
      amount: match.total_amount,
      paidAt: nowIso,
      providerHint: parsed.providerHint,
    });

    const dispatch = async () => {
      try {
        const headers: Record<string, string> = {
          'Content-Type': 'application/json',
        };
        const signingSecret = String(c.env?.CALLBACK_SIGNING_SECRET || '').trim();
        if (signingSecret) {
          headers['x-gateway-signature'] = await signCallbackPayload(callbackPayload, signingSecret);
        }
        await fetch(match.callback_url, {
          method: 'POST',
          headers,
          body: callbackPayload,
        });
      } catch (err) {
        console.error('Callback dispatch failed:', err);
      }
    };

    await runBackground(c, dispatch);
  }

  return c.json({
    status: 'ok',
    outcome: 'MATCHED_PAID',
    invoiceId: match.id,
    externalOrderId: match.external_order_id,
    amount: parsed.amount,
    paidAt: nowIso,
  });
});

/**
 * Heartbeat dari Android Companion App agar server tahu device listener aktif.
 */
app.post('/v1/device/heartbeat', async (c) => {
  const authErr = requireWebhookAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const deviceId = String(body.deviceId || 'default-android').trim().slice(0, 64);
  const appVersion = String(body.appVersion || '').trim().slice(0, 32) || null;
  const batteryPct = Number.isFinite(Number(body.batteryPct)) ? Math.round(Number(body.batteryPct)) : null;
  const networkType = String(body.networkType || '').trim().slice(0, 32) || null;
  const nowIso = new Date().toISOString();

  await c.env.DB
    .prepare(
      `INSERT INTO device_heartbeats (device_id, app_version, battery_pct, network_type, last_seen_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET
         app_version = excluded.app_version,
         battery_pct = excluded.battery_pct,
         network_type = excluded.network_type,
         last_seen_at = excluded.last_seen_at`
    )
    .bind(deviceId, appVersion, batteryPct, networkType, nowIso)
    .run();

  return c.json({ success: true, deviceId, lastSeenAt: nowIso });
});

app.get('/v1/device/status', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const rows = await c.env.DB
    .prepare('SELECT * FROM device_heartbeats ORDER BY last_seen_at DESC LIMIT 10')
    .all<any>();

  return c.json({ success: true, devices: rows.results || [] });
});

/**
 * Menghasilkan JSON config siap-tempel untuk Android Companion App.
 */
app.get('/v1/setup/android-config', (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const baseUrl = new URL(c.req.url).origin;
  return c.json({
    webhookUrl: `${baseUrl}/v1/webhook/mutation`,
    heartbeatUrl: `${baseUrl}/v1/device/heartbeat`,
    secret: c.env?.WEBHOOK_SECRET || '',
    recommendedPackages: [
      'com.shopee.partner.id',
      'com.gobiz.app',
      'id.dana',
      'com.bca.mybca.omni.android',
      'id.co.bankmandiri.livinmerchant',
      'id.co.bri.brimerchant',
    ],
  });
});

export default app;
