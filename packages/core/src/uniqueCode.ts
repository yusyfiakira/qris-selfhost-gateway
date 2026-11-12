/**
 * Pemilih Kode Unik (1..499) bebas bentrok untuk membedakan nominal invoice PENDING.
 */

export interface UniqueCodeRange {
  min?: number;
  max?: number;
  excludeCodes?: Set<number>;
}

function pickRandomIndex(maxExclusive: number): number {
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const arr = new Uint32Array(1);
    crypto.getRandomValues(arr);
    return arr[0] % maxExclusive;
  }
  return Math.floor(Math.random() * maxExclusive);
}

export function selectAvailableUniqueAmount(
  baseAmount: number,
  activePendingTotals: Iterable<number>,
  options?: UniqueCodeRange
): { uniqueCode: number; totalAmount: number } | null {
  const cleanBase = Math.round(baseAmount);
  if (!Number.isSafeInteger(cleanBase) || cleanBase <= 0) {
    throw new Error('Base amount harus berupa bilangan bulat positif.');
  }

  const min = options?.min ?? 1;
  const max = options?.max ?? 499;
  if (min < 1 || max < min) {
    throw new Error('Rentang kode unik tidak valid.');
  }

  const usedTotals = new Set<number>();
  for (const t of activePendingTotals) {
    usedTotals.add(Number(t));
  }

  const candidates: number[] = [];
  for (let code = min; code <= max; code++) {
    if (options?.excludeCodes?.has(code)) continue;
    if (!usedTotals.has(cleanBase + code)) {
      candidates.push(code);
    }
  }

  if (candidates.length === 0) {
    return null;
  }

  const uniqueCode = candidates[pickRandomIndex(candidates.length)];
  return {
    uniqueCode,
    totalAmount: cleanBase + uniqueCode,
  };
}
