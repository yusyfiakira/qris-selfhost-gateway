/**
 * Contoh Integrasi Toko Online -> QRIS Self-Hosted Gateway (Standalone Worker)
 * Kompatibel di Cloudflare Workers, Hono, Next.js, Express, maupun Bun.
 */

const encoder = new TextEncoder();

export interface RemoteQrisGatewayConfig {
  baseUrl: string; // contoh: https://qris-selfhost-gateway.<user>.workers.dev
  apiKey: string;
  callbackSigningSecret: string;
}

export async function createRemoteQrisInvoice(
  cfg: RemoteQrisGatewayConfig,
  params: {
    externalOrderId: string;
    baseAmount: number;
    expiryMinutes?: number;
    callbackUrl: string;
  }
) {
  const res = await fetch(`${cfg.baseUrl.replace(/\/+$/, '')}/v1/invoices`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${cfg.apiKey}`,
    },
    body: JSON.stringify(params),
  });
  const json: any = await res.json();
  if (!res.ok || !json.success) {
    throw new Error(json?.message || `Gateway error HTTP ${res.status}`);
  }
  return json.data as {
    id: string;
    externalOrderId: string;
    baseAmount: number;
    uniqueCode: number;
    totalAmount: number;
    qrString: string;
    status: string;
    expiresAt: string;
  };
}

export async function verifyGatewayCallbackSignature(
  rawBody: string,
  providedSignatureHex: string,
  callbackSigningSecret: string
): Promise<boolean> {
  if (!providedSignatureHex || !callbackSigningSecret) return false;
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(callbackSigningSecret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sigBuf = await crypto.subtle.sign('HMAC', key, encoder.encode(rawBody));
  const expectedHex = Array.from(new Uint8Array(sigBuf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');

  if (expectedHex.length !== providedSignatureHex.length) return false;
  let diff = 0;
  for (let i = 0; i < expectedHex.length; i++) {
    diff |= expectedHex.charCodeAt(i) ^ providedSignatureHex.charCodeAt(i);
  }
  return diff === 0;
}
