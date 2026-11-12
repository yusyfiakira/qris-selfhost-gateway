import { Hono } from 'hono';
import {
  generateDynamicQris,
  parseMutationNotification,
  parseQrisMetadata,
  selectAvailableUniqueAmount,
  verifyQrisCrc,
} from '@qris-selfhost/core';
import { constantTimeEqual, signCallbackPayload } from './security';
import { escapeTelegramHtml, sendTelegramAlert } from './telegram';

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
  OFFLINE_ALERT_MINUTES?: string;
  TELEGRAM_BOT_TOKEN?: string;
  TELEGRAM_CHAT_ID?: string;
}

const MAX_EXPIRY_MINUTES = 1440; // 24 jam
const MAX_UNIQUE_CODE_LIMIT = 999;
const CALLBACK_RETRY_DELAYS_MINUTES = [1, 2, 5, 10, 30];
const SETTING_STATIC_QRIS = 'static_qris_string';
const SETTING_DEFAULT_CALLBACK = 'default_callback_url';
const SETTING_DEFAULT_EXPIRY = 'default_expiry_minutes';

export const app = new Hono<{ Bindings: EnvBindings }>();

function extractBearer(authHeader?: string): string {
  if (!authHeader) return '';
  const match = authHeader.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : '';
}

function requireApiAuth(c: any): Response | null {
  const configured = String(c.env?.API_KEY || '').trim();
  const provided =
    extractBearer(c.req.header('Authorization')) || String(c.req.header('x-api-key') || '').trim();
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

async function resolveActiveStaticQris(
  env: EnvBindings
): Promise<{ qrisString: string; source: 'd1' | 'env' | 'none'; updatedAt?: string }> {
  if (env.DB) {
    try {
      const row = await env.DB.prepare(
        'SELECT value, updated_at FROM gateway_settings WHERE key = ? LIMIT 1'
      )
        .bind(SETTING_STATIC_QRIS)
        .first<{ value: string; updated_at: string }>();
      if (row?.value && String(row.value).trim()) {
        return {
          qrisString: String(row.value).trim(),
          source: 'd1',
          updatedAt: row.updated_at,
        };
      }
    } catch {
      // Tabel belum ada atau DB fallback ke env
    }
  }

  const fromEnv = String(env.STATIC_QRIS_STRING || '').trim();
  if (fromEnv) {
    return { qrisString: fromEnv, source: 'env' };
  }
  return { qrisString: '', source: 'none' };
}

async function resolveDefaultCallbackUrl(env: EnvBindings): Promise<string | null> {
  if (env.DB) {
    try {
      const row = await env.DB.prepare(
        'SELECT value FROM gateway_settings WHERE key = ? LIMIT 1'
      )
        .bind(SETTING_DEFAULT_CALLBACK)
        .first<{ value: string }>();
      if (row?.value !== undefined && row?.value !== null) {
        const clean = String(row.value).trim();
        return clean || null;
      }
    } catch {}
  }
  const fromEnv = String(env.DEFAULT_CALLBACK_URL || '').trim();
  return fromEnv || null;
}

async function resolveDefaultExpiryMinutes(env: EnvBindings): Promise<number> {
  if (env.DB) {
    try {
      const row = await env.DB.prepare(
        'SELECT value FROM gateway_settings WHERE key = ? LIMIT 1'
      )
        .bind(SETTING_DEFAULT_EXPIRY)
        .first<{ value: string }>();
      const num = Number(row?.value);
      if (Number.isFinite(num) && num >= 1 && num <= MAX_EXPIRY_MINUTES) {
        return Math.round(num);
      }
    } catch {}
  }
  const fromEnv = Number(env.DEFAULT_EXPIRY_MINUTES || 15);
  return Number.isFinite(fromEnv) ? Math.max(1, Math.min(MAX_EXPIRY_MINUTES, Math.round(fromEnv))) : 15;
}

function getTelegramCfg(env: EnvBindings) {
  return {
    botToken: env.TELEGRAM_BOT_TOKEN,
    chatId: env.TELEGRAM_CHAT_ID,
  };
}

export async function deliverInvoiceCallback(
  env: EnvBindings,
  invoice: any,
  providerHint?: string
): Promise<{ status: 'NONE' | 'DELIVERED' | 'PENDING' | 'FAILED'; httpCode: number | null; error?: string }> {
  const callbackUrl = String(invoice?.callback_url || '').trim();
  if (!callbackUrl) {
    return { status: 'NONE', httpCode: null };
  }

  const attemptsBefore = Number(invoice.callback_attempts || 0);
  const nextAttempt = attemptsBefore + 1;
  const now = new Date();
  const nowIso = now.toISOString();

  const payload = JSON.stringify({
    event: 'invoice.paid',
    invoiceId: invoice.id,
    externalOrderId: invoice.external_order_id,
    amount: invoice.total_amount,
    paidAt: invoice.paid_at || nowIso,
    providerHint: providerHint || 'unknown',
    attempt: nextAttempt,
  });

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const signingSecret = String(env.CALLBACK_SIGNING_SECRET || '').trim();
  if (signingSecret) {
    headers['x-gateway-signature'] = await signCallbackPayload(payload, signingSecret);
  }

  let httpCode: number | null = null;
  let errorMessage: string | null = null;
  let delivered = false;

  try {
    const res = await fetch(callbackUrl, {
      method: 'POST',
      headers,
      body: payload,
    });
    httpCode = res.status;
    if (res.ok) {
      delivered = true;
    } else {
      errorMessage = `HTTP ${res.status}`;
    }
  } catch (err: any) {
    errorMessage = err?.message ? String(err.message).slice(0, 240) : 'Network error';
  }

  if (delivered) {
    await env.DB.prepare(
      `UPDATE invoices
       SET callback_status = 'DELIVERED',
           callback_attempts = ?,
           callback_http_code = ?,
           callback_last_error = NULL,
           callback_next_retry_at = NULL,
           callback_delivered_at = ?
       WHERE id = ?`
    )
      .bind(nextAttempt, httpCode, nowIso, invoice.id)
      .run();
    return { status: 'DELIVERED', httpCode };
  }

  const maxAttempts = CALLBACK_RETRY_DELAYS_MINUTES.length;
  if (nextAttempt >= maxAttempts) {
    await env.DB.prepare(
      `UPDATE invoices
       SET callback_status = 'FAILED',
           callback_attempts = ?,
           callback_http_code = ?,
           callback_last_error = ?,
           callback_next_retry_at = NULL
       WHERE id = ?`
    )
      .bind(nextAttempt, httpCode, errorMessage, invoice.id)
      .run();

    await sendTelegramAlert(
      getTelegramCfg(env),
      `⚠️ <b>CALLBACK TOKO GAGAL PERMANEN</b>\n` +
        `• <b>Invoice:</b> <code>${escapeTelegramHtml(invoice.id)}</code>\n` +
        `• <b>Order Toko:</b> <code>${escapeTelegramHtml(invoice.external_order_id)}</code>\n` +
        `• <b>Nominal:</b> Rp ${Number(invoice.total_amount).toLocaleString('id-ID')}\n` +
        `• <b>Error:</b> ${escapeTelegramHtml(errorMessage || 'Unknown')}`
    );

    return { status: 'FAILED', httpCode, error: errorMessage || undefined };
  }

  const delayMin = CALLBACK_RETRY_DELAYS_MINUTES[nextAttempt - 1] ?? 5;
  const nextRetryAt = new Date(now.getTime() + delayMin * 60 * 1000).toISOString();

  await env.DB.prepare(
    `UPDATE invoices
     SET callback_status = 'PENDING',
         callback_attempts = ?,
         callback_http_code = ?,
         callback_last_error = ?,
         callback_next_retry_at = ?
     WHERE id = ?`
  )
    .bind(nextAttempt, httpCode, errorMessage, nextRetryAt, invoice.id)
    .run();

  return { status: 'PENDING', httpCode, error: errorMessage || undefined };
}

app.get('/health', async (c) => {
  const active = await resolveActiveStaticQris(c.env);
  let merchantName: string | undefined;
  if (active.qrisString) {
    try {
      merchantName = parseQrisMetadata(active.qrisString).merchantName;
    } catch {}
  }
  return c.json({
    status: 'ok',
    service: 'qris-selfhost-gateway',
    merchantName: merchantName || null,
    qrisSource: active.source,
    timestamp: new Date().toISOString(),
  });
});

/**
 * Mengambil metadata QRIS statis aktif (tanpa mengekspos raw string rahasia bila tidak perlu).
 */
app.get('/v1/config/qris', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const active = await resolveActiveStaticQris(c.env);
  if (!active.qrisString) {
    return c.json({ success: true, configured: false, source: 'none', metadata: null });
  }

  let metadata = null;
  try {
    metadata = parseQrisMetadata(active.qrisString);
  } catch {}

  return c.json({
    success: true,
    configured: true,
    source: active.source,
    updatedAt: active.updatedAt || null,
    metadata,
  });
});

/**
 * Mengganti QRIS statis aktif di D1 (wajib API_KEY, validasi CRC16 + Tag 01=11, plus Telegram alert).
 */
app.put('/v1/config/qris', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const newQris = String(body.staticQrisString || body.qrisString || '').trim();
  const deviceId = String(body.deviceId || 'admin-client').trim().slice(0, 64);

  if (!newQris || !verifyQrisCrc(newQris)) {
    return c.json(
      { success: false, message: 'String QRIS tidak valid atau checksum CRC16 gagal diverifikasi' },
      400
    );
  }

  const newMeta = parseQrisMetadata(newQris);
  if (newMeta.payloadFormatIndicator !== '01' || newMeta.pointOfInitiation !== '11') {
    return c.json(
      {
        success: false,
        message: 'Hanya QRIS Statis (Tag 00 = 01 dan Tag 01 = 11) yang dapat dijadikan template merchant',
      },
      400
    );
  }

  const prev = await resolveActiveStaticQris(c.env);
  let prevMerchant = '(belum diset)';
  if (prev.qrisString) {
    try {
      prevMerchant = parseQrisMetadata(prev.qrisString).merchantName || '(tidak diketahui)';
    } catch {}
  }

  const nowIso = new Date().toISOString();
  await c.env.DB.prepare(
    `INSERT INTO gateway_settings (key, value, updated_at)
     VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  )
    .bind(SETTING_STATIC_QRIS, newQris, nowIso)
    .run();

  const newMerchant = newMeta.merchantName || '(tanpa nama)';
  await runBackground(c, () =>
    sendTelegramAlert(
      getTelegramCfg(c.env),
      `🔄 <b>PERUBAHAN QRIS MERCHANT AKTIF</b>\n` +
        `• <b>Dari:</b> ${escapeTelegramHtml(prevMerchant)}\n` +
        `• <b>Menjadi:</b> ${escapeTelegramHtml(newMerchant)} (${escapeTelegramHtml(newMeta.merchantCity || '-')})\n` +
        `• <b>NMID:</b> <code>${escapeTelegramHtml(newMeta.nmid || '-')}</code>\n` +
        `• <b>Oleh:</b> <code>${escapeTelegramHtml(deviceId)}</code>`
    ).then(() => undefined)
  );

  return c.json({
    success: true,
    message: 'QRIS statis aktif berhasil diperbarui',
    source: 'd1',
    updatedAt: nowIso,
    metadata: newMeta,
  });
});

/**
 * Mengambil & memperbarui Default Callback URL + Default Expiry (wajib API_KEY).
 */
app.get('/v1/config/callback', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const callbackUrl = await resolveDefaultCallbackUrl(c.env);
  const defaultExpiryMinutes = await resolveDefaultExpiryMinutes(c.env);
  return c.json({
    success: true,
    callbackUrl: callbackUrl || '',
    defaultExpiryMinutes,
  });
});

app.put('/v1/config/callback', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const rawUrl = String(body.callbackUrl ?? '').trim();
  if (rawUrl && !/^https?:\/\/.+/i.test(rawUrl)) {
    return c.json({ success: false, message: 'callbackUrl harus diawali http:// atau https://' }, 400);
  }

  const nowIso = new Date().toISOString();
  await c.env.DB.prepare(
    `INSERT INTO gateway_settings (key, value, updated_at)
     VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  )
    .bind(SETTING_DEFAULT_CALLBACK, rawUrl, nowIso)
    .run();

  if (body.defaultExpiryMinutes !== undefined) {
    const exp = Number(body.defaultExpiryMinutes);
    if (!Number.isFinite(exp) || exp < 1 || exp > MAX_EXPIRY_MINUTES) {
      return c.json(
        { success: false, message: `defaultExpiryMinutes harus antara 1 s/d ${MAX_EXPIRY_MINUTES}` },
        400
      );
    }
    await c.env.DB.prepare(
      `INSERT INTO gateway_settings (key, value, updated_at)
       VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
    )
      .bind(SETTING_DEFAULT_EXPIRY, String(Math.round(exp)), nowIso)
      .run();
  }

  return c.json({
    success: true,
    callbackUrl: rawUrl,
    defaultExpiryMinutes: await resolveDefaultExpiryMinutes(c.env),
    updatedAt: nowIso,
  });
});

app.post('/v1/config/callback/test', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const targetUrl = String(body.callbackUrl || (await resolveDefaultCallbackUrl(c.env)) || '').trim();
  if (!targetUrl) {
    return c.json({ success: false, message: 'Callback URL belum dikonfigurasi' }, 400);
  }

  const payload = JSON.stringify({
    event: 'invoice.test',
    invoiceId: 'inv_test_ping',
    externalOrderId: 'TEST-PING',
    amount: 1000,
    paidAt: new Date().toISOString(),
  });
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const signingSecret = String(c.env?.CALLBACK_SIGNING_SECRET || '').trim();
  if (signingSecret) {
    headers['x-gateway-signature'] = await signCallbackPayload(payload, signingSecret);
  }

  try {
    const res = await fetch(targetUrl, { method: 'POST', headers, body: payload });
    const text = await res.text().catch(() => '');
    return c.json({
      success: res.ok,
      httpCode: res.status,
      responsePreview: text.slice(0, 200),
    });
  } catch (err: any) {
    return c.json({
      success: false,
      httpCode: null,
      error: err?.message || 'Network error',
    });
  }
});

/**
 * Daftar invoice + ringkasan omzet untuk dashboard mobile/web (wajib API_KEY).
 */
app.get('/v1/invoices', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const statusFilter = String(c.req.query('status') || '').trim().toUpperCase();
  const minAmount = Number(c.req.query('minAmount') || 0);
  const maxAmount = Number(c.req.query('maxAmount') || 0);
  const fromDate = String(c.req.query('from') || '').trim();
  const toDate = String(c.req.query('to') || '').trim();
  const limit = Math.max(1, Math.min(100, Number(c.req.query('limit') || 50)));

  const whereParts: string[] = ['1=1'];
  const params: any[] = [];

  if (statusFilter && ['PENDING', 'PAID', 'EXPIRED', 'CANCELLED'].includes(statusFilter)) {
    whereParts.push('status = ?');
    params.push(statusFilter);
  }
  if (Number.isFinite(minAmount) && minAmount > 0) {
    whereParts.push('total_amount >= ?');
    params.push(Math.round(minAmount));
  }
  if (Number.isFinite(maxAmount) && maxAmount > 0) {
    whereParts.push('total_amount <= ?');
    params.push(Math.round(maxAmount));
  }
  if (fromDate) {
    whereParts.push('created_at >= ?');
    params.push(fromDate);
  }
  if (toDate) {
    whereParts.push('created_at <= ?');
    params.push(toDate);
  }

  const listQuery = `SELECT * FROM invoices WHERE ${whereParts.join(' AND ')} ORDER BY created_at DESC LIMIT ?`;
  const rows = await c.env.DB.prepare(listQuery)
    .bind(...params, limit)
    .all<any>();

  const nowIso = new Date().toISOString();
  const todayPrefix = nowIso.slice(0, 10);

  const summaryRow = await c.env.DB.prepare(
    `SELECT
       COALESCE(SUM(CASE WHEN status = 'PAID' THEN total_amount ELSE 0 END), 0) AS total_paid_amount,
       COALESCE(SUM(CASE WHEN status = 'PAID' THEN 1 ELSE 0 END), 0) AS total_paid_count,
       COALESCE(SUM(CASE WHEN status = 'PAID' AND substr(paid_at, 1, 10) = ? THEN total_amount ELSE 0 END), 0) AS today_paid_amount,
       COALESCE(SUM(CASE WHEN status = 'PAID' AND substr(paid_at, 1, 10) = ? THEN 1 ELSE 0 END), 0) AS today_paid_count,
       COUNT(*) AS total_invoices
     FROM invoices`
  )
    .bind(todayPrefix, todayPrefix)
    .first<any>();

  return c.json({
    success: true,
    summary: {
      totalPaidAmount: Number(summaryRow?.total_paid_amount || 0),
      totalPaidCount: Number(summaryRow?.total_paid_count || 0),
      todayPaidAmount: Number(summaryRow?.today_paid_amount || 0),
      todayPaidCount: Number(summaryRow?.today_paid_count || 0),
      totalInvoices: Number(summaryRow?.total_invoices || 0),
    },
    invoices: (rows.results || []).map((row: any) => ({
      id: row.id,
      externalOrderId: row.external_order_id,
      baseAmount: row.base_amount,
      uniqueCode: row.unique_code,
      totalAmount: row.total_amount,
      status: row.status === 'PENDING' && row.expires_at <= nowIso ? 'EXPIRED' : row.status,
      expiresAt: row.expires_at,
      paidAt: row.paid_at,
      createdAt: row.created_at,
      callbackStatus: row.callback_status || 'NONE',
      callbackAttempts: Number(row.callback_attempts || 0),
      callbackHttpCode: row.callback_http_code ?? null,
    })),
  });
});

/**
 * Membuat invoice QRIS dinamis dengan durasi fleksibel (1..1440 menit) & kode unik bebas bentrok.
 */
app.post('/v1/invoices', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const db = c.env.DB;
  const activeQris = await resolveActiveStaticQris(c.env);
  if (!activeQris.qrisString) {
    return c.json({ success: false, message: 'STATIC_QRIS_STRING is not configured' }, 500);
  }

  const body = await parseFlexibleBody(c);
  const externalOrderId = String(body.externalOrderId || body.orderId || '').trim();
  const baseAmount = Math.round(Number(body.baseAmount ?? body.amount ?? 0));
  const defaultExpiry = await resolveDefaultExpiryMinutes(c.env);
  const rawExpiry = Number(body.expiryMinutes ?? defaultExpiry);
  const expiryMinutes = Number.isFinite(rawExpiry)
    ? Math.max(1, Math.min(MAX_EXPIRY_MINUTES, Math.round(rawExpiry)))
    : defaultExpiry;
  const callbackUrl = String(body.callbackUrl || (await resolveDefaultCallbackUrl(c.env)) || '').trim() || null;
  const metadataJson = body.metadata ? JSON.stringify(body.metadata) : null;

  if (!externalOrderId) {
    return c.json({ success: false, message: 'externalOrderId is required' }, 400);
  }
  if (!Number.isSafeInteger(baseAmount) || baseAmount <= 0) {
    return c.json({ success: false, message: 'baseAmount must be a positive integer' }, 400);
  }

  const codeMin = Math.max(1, Number(c.env?.UNIQUE_CODE_MIN || 1));
  const codeMax = Math.min(
    MAX_UNIQUE_CODE_LIMIT,
    Math.max(codeMin, Number(c.env?.UNIQUE_CODE_MAX || 499))
  );

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
    qrString = generateDynamicQris(activeQris.qrisString, candidate.totalAmount);

    await db
      .prepare(
        `INSERT INTO invoices (id, external_order_id, base_amount, unique_code, total_amount, qr_string, status, callback_url, callback_status, metadata_json, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
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
        callbackUrl ? 'PENDING' : 'NONE',
        metadataJson,
        expiresAt
      )
      .run();

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
        expiryMinutes,
        expiresAt,
      },
    },
    201
  );
});

/**
 * Mengambil detail status invoice + status pengiriman callback.
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
    await c.env.DB.prepare(
      `UPDATE invoices SET status = 'EXPIRED', callback_status = 'NONE' WHERE id = ? AND status = 'PENDING'`
    )
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
      cancelledAt: row.cancelled_at || null,
      callbackStatus: row.callback_status || 'NONE',
      callbackAttempts: Number(row.callback_attempts || 0),
      callbackHttpCode: row.callback_http_code ?? null,
      callbackLastError: row.callback_last_error || null,
      callbackDeliveredAt: row.callback_delivered_at || null,
    },
  });
});

/**
 * Membatalkan invoice PENDING secara eksplisit agar slot kode unik langsung bebas.
 */
app.post('/v1/invoices/:id/cancel', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const id = c.req.param('id');
  const existing = await c.env.DB.prepare('SELECT id, status FROM invoices WHERE id = ? LIMIT 1')
    .bind(id)
    .first<{ id: string; status: string }>();

  if (!existing) {
    return c.json({ success: false, message: 'Invoice not found' }, 404);
  }
  if (existing.status !== 'PENDING') {
    return c.json(
      { success: false, message: `Invoice status is ${existing.status}, cannot cancel` },
      409
    );
  }

  const nowIso = new Date().toISOString();
  await c.env.DB.prepare(
    `UPDATE invoices SET status = 'CANCELLED', cancelled_at = ?, callback_status = 'NONE'
     WHERE id = ? AND status = 'PENDING'`
  )
    .bind(nowIso, id)
    .run();

  return c.json({ success: true, id, status: 'CANCELLED', cancelledAt: nowIso });
});

/**
 * Mengulang pengiriman callback ke toko secara manual untuk invoice PAID.
 */
app.post('/v1/invoices/:id/retry-callback', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const id = c.req.param('id');
  const invoice = await c.env.DB.prepare('SELECT * FROM invoices WHERE id = ? LIMIT 1')
    .bind(id)
    .first<any>();

  if (!invoice) {
    return c.json({ success: false, message: 'Invoice not found' }, 404);
  }
  if (invoice.status !== 'PAID') {
    return c.json({ success: false, message: 'Callback retry only allowed for PAID invoices' }, 409);
  }
  if (!invoice.callback_url) {
    return c.json({ success: false, message: 'Invoice does not have a callbackUrl' }, 400);
  }

  const result = await deliverInvoiceCallback(c.env, invoice);
  return c.json({
    success: result.status === 'DELIVERED',
    invoiceId: invoice.id,
    callbackStatus: result.status,
    httpCode: result.httpCode,
    error: result.error || null,
  });
});

/**
 * Melihat riwayat mutasi masuk terbaru (untuk dashboard / audit).
 */
app.get('/v1/mutations', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const outcomeFilter = String(c.req.query('outcome') || '').trim().toUpperCase();
  const limit = Math.max(1, Math.min(100, Number(c.req.query('limit') || 50)));

  const rows = outcomeFilter
    ? await c.env.DB.prepare(
        `SELECT * FROM mutation_logs WHERE outcome = ? ORDER BY created_at DESC LIMIT ?`
      )
        .bind(outcomeFilter, limit)
        .all<any>()
    : await c.env.DB.prepare(`SELECT * FROM mutation_logs ORDER BY created_at DESC LIMIT ?`)
        .bind(limit)
        .all<any>();

  return c.json({ success: true, mutations: rows.results || [] });
});

/**
 * Webhook penerima mutasi dari Android Companion App / MacroDroid / GAS.
 */
app.post('/v1/webhook/mutation', async (c) => {
  const authErr = requireWebhookAuth(c);
  if (authErr) return authErr;

  const body = await parseFlexibleBody(c);
  const parsed = parseMutationNotification(body);
  const deviceId =
    String(body.deviceId || body.device_id || c.req.header('x-device-id') || '').trim() || null;

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
      .bind(
        logId,
        parsed.amount,
        parsed.providerHint,
        parsed.rawText || null,
        alreadyPaid?.id || null,
        outcome,
        deviceId
      )
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

  const paidInvoice = { ...match, paid_at: nowIso, callback_attempts: 0 };

  await runBackground(c, async () => {
    const cbRes = await deliverInvoiceCallback(c.env, paidInvoice, parsed.providerHint);
    const cbLabel =
      cbRes.status === 'NONE'
        ? 'Tidak dikonfigurasi'
        : cbRes.status === 'DELIVERED'
        ? `Terkirim (HTTP ${cbRes.httpCode})`
        : `Pending Retry (${cbRes.error || 'Error'})`;

    await sendTelegramAlert(
      getTelegramCfg(c.env),
      `✅ <b>PEMBAYARAN QRIS LUNAS!</b>\n` +
        `• <b>Invoice:</b> <code>${escapeTelegramHtml(match.id)}</code>\n` +
        `• <b>Order Toko:</b> <code>${escapeTelegramHtml(match.external_order_id)}</code>\n` +
        `• <b>Nominal:</b> Rp ${Number(parsed.amount).toLocaleString('id-ID')}\n` +
        `• <b>Sumber:</b> ${escapeTelegramHtml(parsed.providerHint)} (${escapeTelegramHtml(deviceId || '-')})\n` +
        `• <b>Callback Toko:</b> ${escapeTelegramHtml(cbLabel)}`
    );
  });

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

  await c.env.DB.prepare(
    `INSERT INTO device_heartbeats (device_id, app_version, battery_pct, network_type, last_seen_at, alerted_offline_at)
     VALUES (?, ?, ?, ?, ?, NULL)
     ON CONFLICT(device_id) DO UPDATE SET
       app_version = excluded.app_version,
       battery_pct = excluded.battery_pct,
       network_type = excluded.network_type,
       last_seen_at = excluded.last_seen_at,
       alerted_offline_at = NULL`
  )
    .bind(deviceId, appVersion, batteryPct, networkType, nowIso)
    .run();

  return c.json({ success: true, deviceId, lastSeenAt: nowIso });
});

app.get('/v1/device/status', async (c) => {
  const authErr = requireApiAuth(c);
  if (authErr) return authErr;

  const rows = await c.env.DB.prepare(
    'SELECT * FROM device_heartbeats ORDER BY last_seen_at DESC LIMIT 10'
  ).all<any>();

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
      'com.shopeepay.merchant.id',
      'com.gojek.gopaymerchant',
      'com.gojek.resto',
      'id.dana',
      'com.bca.merchantbca',
      'id.co.bankmandiri.livinmerchant',
      'id.co.bri.merchant',
    ],
  });
});

/**
 * Scheduled Cron Job (tiap 1 menit):
 * 1. Auto-retry callback invoice PAID yang gagal sementara.
 * 2. Alert Telegram bila ada device listener yang offline melewati batas menit.
 */
export async function runScheduledTasks(env: EnvBindings, nowMs: number = Date.now()) {
  if (!env.DB) return;
  const nowIso = new Date(nowMs).toISOString();

  // 1. Retry pending callbacks
  const pendingCallbacks = await env.DB.prepare(
    `SELECT * FROM invoices
     WHERE status = 'PAID'
       AND callback_status = 'PENDING'
       AND callback_url IS NOT NULL
       AND (callback_next_retry_at IS NULL OR callback_next_retry_at <= ?)
     LIMIT 10`
  )
    .bind(nowIso)
    .all<any>();

  for (const inv of pendingCallbacks.results || []) {
    await deliverInvoiceCallback(env, inv);
  }

  // 2. Check offline listener devices
  const offlineThresholdMin = Math.max(5, Number(env.OFFLINE_ALERT_MINUTES || 20));
  const cutoffIso = new Date(nowMs - offlineThresholdMin * 60 * 1000).toISOString();

  const offlineDevices = await env.DB.prepare(
    `SELECT * FROM device_heartbeats
     WHERE last_seen_at < ? AND alerted_offline_at IS NULL`
  )
    .bind(cutoffIso)
    .all<any>();

  for (const dev of offlineDevices.results || []) {
    await env.DB.prepare(`UPDATE device_heartbeats SET alerted_offline_at = ? WHERE device_id = ?`)
      .bind(nowIso, dev.device_id)
      .run();

    await sendTelegramAlert(
      getTelegramCfg(env),
      `📵 <b>LISTENER ANDROID OFFLINE!</b>\n` +
        `• <b>Device:</b> <code>${escapeTelegramHtml(dev.device_id)}</code>\n` +
        `• <b>Terakhir Aktif:</b> ${escapeTelegramHtml(dev.last_seen_at)}\n` +
        `• <b>Baterai Terakhir:</b> ${escapeTelegramHtml(dev.battery_pct ?? '-')}% (${escapeTelegramHtml(dev.network_type || '-')})`
    );
  }
}

export default {
  fetch: app.fetch,
  async scheduled(_event: ScheduledEvent, env: EnvBindings, ctx: ExecutionContext) {
    ctx.waitUntil(runScheduledTasks(env));
  },
};
