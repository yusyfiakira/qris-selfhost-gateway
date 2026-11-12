/**
 * Parser Notifikasi Mutasi Masuk Indonesia
 * Mendukung payload JSON numerik maupun teks notifikasi HP dari:
 * Shopee Partner, GoPay Merchant (GoBiz), DANA Bisnis, BCA/myBCA, BRI, Mandiri, dll.
 */

export interface ParsedMutationPayload {
  amount: number;
  providerHint: string;
  rawText?: string;
}

const OUTGOING_KEYWORDS = [
  'pembayaran ke',
  'transfer keluar',
  'pembayaran berhasil ke',
  'anda mengirim',
  'kamu mengirim',
  'berhasil bayar ke',
  'debit',
  'penarikan',
];

const PROVIDER_PATTERNS: Array<{ name: string; pattern: RegExp }> = [
  { name: 'shopee_partner', pattern: /shopee|shopeepay|transaksi \d{12,}/i },
  { name: 'gopay_merchant', pattern: /gopay|gobiz/i },
  { name: 'dana_bisnis', pattern: /\bdana\b/i },
  { name: 'bca', pattern: /\bbca\b|mybca/i },
  { name: 'mandiri', pattern: /mandiri|livin/i },
  { name: 'bri', pattern: /\bbri\b|brimo|brimerchant/i },
];

export function parseIdrAmountString(raw: string): number | null {
  let s = raw.trim();
  if (!s) return null;

  s = s.replace(/^(?:rp\.?|idr)\s*/i, '').trim();
  // Hapus tanda baca trailing (contoh "1.267,-." -> "1.267")
  s = s.replace(/[.,-]+$/, '');
  // Hapus desimal sen ",00" atau ".00" di akhir
  s = s.replace(/[.,]00$/, '');

  // Format ribuan Indonesia/internasional (misal 25.143 atau 1,250,000)
  if (/^\d{1,3}(?:[.,]\d{3})+$/.test(s)) {
    s = s.replace(/[.,]/g, '');
  }

  if (!/^\d+$/.test(s)) return null;

  const num = Number(s);
  if (!Number.isSafeInteger(num) || num <= 0) return null;
  return num;
}

export function detectProviderHint(text: string, packageName?: string): string {
  const haystack = `${packageName || ''} ${text}`.trim();
  for (const item of PROVIDER_PATTERNS) {
    if (item.pattern.test(haystack)) {
      return item.name;
    }
  }
  return 'unknown';
}

export function isLikelyOutgoingMutation(text: string): boolean {
  const lower = text.toLowerCase();
  return OUTGOING_KEYWORDS.some((kw) => lower.includes(kw));
}

/**
 * Mengekstrak nominal transfer masuk (IDR bulat) dari payload webhook atau teks notifikasi.
 */
export function parseMutationNotification(
  payload: any,
  options?: { rejectOutgoing?: boolean }
): ParsedMutationPayload | null {
  if (!payload || typeof payload !== 'object') return null;

  const rawData = payload.data && typeof payload.data === 'object' ? payload.data : payload;
  const packageName = String(
    rawData.packageName ?? rawData.package_name ?? payload.packageName ?? payload.package_name ?? ''
  ).trim();

  const textCandidate = String(
    rawData.rawText ??
      rawData.raw_text ??
      rawData.message ??
      rawData.notification_text ??
      rawData.text ??
      payload.rawText ??
      payload.message ??
      ''
  ).trim();

  if (options?.rejectOutgoing !== false && textCandidate && isLikelyOutgoingMutation(textCandidate)) {
    return null;
  }

  const directCandidate =
    rawData.amount ??
    rawData.nominal ??
    rawData.total_amount ??
    rawData.totalAmount ??
    payload.amount ??
    payload.nominal;

  if (typeof directCandidate === 'number') {
    if (!Number.isFinite(directCandidate) || !Number.isInteger(directCandidate) || directCandidate <= 0) {
      return null;
    }
    return {
      amount: directCandidate,
      providerHint: detectProviderHint(textCandidate, packageName),
      rawText: textCandidate || undefined,
    };
  }

  if (typeof directCandidate === 'string') {
    const parsedDirect = parseIdrAmountString(directCandidate);
    if (parsedDirect !== null) {
      return {
        amount: parsedDirect,
        providerHint: detectProviderHint(textCandidate || directCandidate, packageName),
        rawText: textCandidate || directCandidate,
      };
    }
  }

  if (textCandidate) {
    const rpMatch = textCandidate.match(/(?:rp\.?|idr)\s*([\d.,]+)/i);
    if (rpMatch?.[1]) {
      const parsedFromText = parseIdrAmountString(rpMatch[1]);
      if (parsedFromText !== null) {
        return {
          amount: parsedFromText,
          providerHint: detectProviderHint(textCandidate, packageName),
          rawText: textCandidate,
        };
      }
    }
  }

  return null;
}
