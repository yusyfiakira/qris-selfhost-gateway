/**
 * EMVCo Dynamic QRIS Generator & Validator (Standar QRIS Bank Indonesia)
 * Zero-dependency: kompatibel di Cloudflare Workers, Node.js, Bun, Deno, dan Browser.
 */

export interface QrisTlvField {
  tag: string;
  value: string;
}

export interface QrisMetadata {
  isValidCrc: boolean;
  payloadFormatIndicator?: string;
  pointOfInitiation?: string;
  acquirerDomain?: string;
  nmid?: string;
  merchantCategoryCode?: string;
  currency?: string;
  amount?: number;
  countryCode?: string;
  merchantName?: string;
  merchantCity?: string;
  postalCode?: string;
}

const textEncoder = new TextEncoder();

/**
 * Menghitung checksum CRC16 CCITT-FALSE (Poly 0x1021, Init 0xFFFF).
 */
export function computeCrc16CcittFalse(payload: string): string {
  const bytes = textEncoder.encode(payload);
  let crc = 0xffff;

  for (let i = 0; i < bytes.length; i++) {
    crc ^= bytes[i] << 8;
    for (let bit = 0; bit < 8; bit++) {
      if ((crc & 0x8000) !== 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xffff;
      } else {
        crc = (crc << 1) & 0xffff;
      }
    }
  }

  return crc.toString(16).toUpperCase().padStart(4, '0');
}

/**
 * Mem-parse string EMVCo TLV (Tag 2 digit + Length 2 digit + Value).
 */
export function parseEmvTlv(payload: string): QrisTlvField[] {
  const trimmed = payload.trim();
  if (!trimmed) {
    throw new Error('String QRIS tidak boleh kosong.');
  }

  const fields: QrisTlvField[] = [];
  let cursor = 0;

  while (cursor < trimmed.length) {
    if (cursor + 4 > trimmed.length) {
      throw new Error(`Format TLV QRIS terpotong pada offset ${cursor}.`);
    }

    const tag = trimmed.slice(cursor, cursor + 2);
    const lenStr = trimmed.slice(cursor + 2, cursor + 4);

    if (!/^\d{2}$/.test(tag)) {
      throw new Error(`Tag TLV QRIS tidak valid "${tag}" pada offset ${cursor}.`);
    }
    if (!/^\d{2}$/.test(lenStr)) {
      throw new Error(`Panjang TLV QRIS tidak valid "${lenStr}" untuk tag ${tag}.`);
    }

    const length = Number(lenStr);
    const valueStart = cursor + 4;
    const valueEnd = valueStart + length;

    if (valueEnd > trimmed.length) {
      throw new Error(`Panjang nilai tag ${tag} (${length}) melebihi panjang string QRIS.`);
    }

    fields.push({
      tag,
      value: trimmed.slice(valueStart, valueEnd),
    });

    cursor = valueEnd;
  }

  return fields;
}

/**
 * Menggabungkan daftar field TLV kembali menjadi string EMVCo.
 */
export function serializeEmvTlv(fields: QrisTlvField[]): string {
  return fields
    .map(({ tag, value }) => {
      if (!/^\d{2}$/.test(tag)) {
        throw new Error(`Tag TLV tidak valid: "${tag}".`);
      }
      if (value.length > 99) {
        throw new Error(`Nilai tag ${tag} melebihi batas 99 karakter.`);
      }
      const lenStr = String(value.length).padStart(2, '0');
      return `${tag}${lenStr}${value}`;
    })
    .join('');
}

/**
 * Memvalidasi checksum Tag 63 (6304XXXX) dan struktur TLV QRIS.
 */
export function verifyQrisCrc(qrisString: string): boolean {
  const trimmed = qrisString.trim();
  if (trimmed.length < 12) return false;

  const crcTagPrefix = trimmed.slice(-8, -4);
  if (crcTagPrefix !== '6304') return false;

  const providedCrc = trimmed.slice(-4).toUpperCase();
  if (!/^[0-9A-F]{4}$/.test(providedCrc)) return false;

  try {
    parseEmvTlv(trimmed);
  } catch {
    return false;
  }

  const bodyWithoutChecksum = trimmed.slice(0, -4);
  return providedCrc === computeCrc16CcittFalse(bodyWithoutChecksum);
}

/**
 * Mengekstrak metadata utama dari string QRIS.
 */
export function parseQrisMetadata(qrisString: string): QrisMetadata {
  const trimmed = qrisString.trim();
  const isValidCrc = verifyQrisCrc(trimmed);
  const fields = parseEmvTlv(trimmed);
  const byTag = new Map<string, string>();
  for (const field of fields) {
    byTag.set(field.tag, field.value);
  }

  let acquirerDomain: string | undefined;
  let nmid: string | undefined;

  const tag51 = byTag.get('51');
  if (tag51) {
    try {
      const sub51 = parseEmvTlv(tag51);
      for (const sub of sub51) {
        if (sub.tag === '00' && !acquirerDomain) acquirerDomain = sub.value;
        if (sub.tag === '02') nmid = sub.value;
      }
    } catch {
      // Abaikan sub-tag opsional yang tidak standar
    }
  }

  for (let tagNum = 26; tagNum <= 45; tagNum++) {
    const raw = byTag.get(String(tagNum));
    if (!raw) continue;
    try {
      const subFields = parseEmvTlv(raw);
      const domain = subFields.find((f) => f.tag === '00')?.value;
      if (domain) {
        acquirerDomain = domain;
        break;
      }
    } catch {
      // Abaikan
    }
  }

  const rawAmount = byTag.get('54');
  const parsedAmount =
    rawAmount !== undefined && /^\d+(\.\d+)?$/.test(rawAmount) ? Number(rawAmount) : undefined;

  return {
    isValidCrc,
    payloadFormatIndicator: byTag.get('00'),
    pointOfInitiation: byTag.get('01'),
    acquirerDomain,
    nmid,
    merchantCategoryCode: byTag.get('52'),
    currency: byTag.get('53'),
    amount: parsedAmount,
    countryCode: byTag.get('58'),
    merchantName: byTag.get('59'),
    merchantCity: byTag.get('60'),
    postalCode: byTag.get('61'),
  };
}

/**
 * Mengonversi QRIS Statis (Tag 01=11) menjadi QRIS Dinamis (Tag 01=12)
 * dengan nominal tagihan (Tag 54) dan checksum CRC16 baru (Tag 6304).
 */
export function generateDynamicQris(staticQrisString: string, amount: number): string {
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    throw new Error('Nominal QRIS harus berupa bilangan bulat positif (IDR).');
  }

  const trimmed = staticQrisString.trim();
  if (!verifyQrisCrc(trimmed)) {
    throw new Error('String QRIS statis tidak valid atau checksum CRC16 gagal diverifikasi.');
  }

  const fields = parseEmvTlv(trimmed);
  const payloadFormat = fields.find((f) => f.tag === '00')?.value;
  if (payloadFormat !== '01') {
    throw new Error('Payload Format Indicator (Tag 00) QRIS harus bernilai "01".');
  }

  const filtered = fields.filter((f) => f.tag !== '54' && f.tag !== '63');

  const hasTag01 = filtered.some((f) => f.tag === '01');
  const withDynamicMethod: QrisTlvField[] = hasTag01
    ? filtered.map((f) => (f.tag === '01' ? { tag: '01', value: '12' } : f))
    : [
        { tag: '00', value: payloadFormat },
        { tag: '01', value: '12' },
        ...filtered.filter((f) => f.tag !== '00'),
      ];

  const amountField: QrisTlvField = { tag: '54', value: String(amount) };
  const insertIdx = withDynamicMethod.findIndex((f) => Number(f.tag) > 54);
  if (insertIdx === -1) {
    withDynamicMethod.push(amountField);
  } else {
    withDynamicMethod.splice(insertIdx, 0, amountField);
  }

  const bodyWithCrcPrefix = `${serializeEmvTlv(withDynamicMethod)}6304`;
  const crc = computeCrc16CcittFalse(bodyWithCrcPrefix);
  return `${bodyWithCrcPrefix}${crc}`;
}
