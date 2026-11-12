import test from 'node:test';
import assert from 'node:assert/strict';
import {
  computeCrc16CcittFalse,
  parseEmvTlv,
  verifyQrisCrc,
  parseQrisMetadata,
  generateDynamicQris,
  parseMutationNotification,
  selectAvailableUniqueAmount,
} from '../src/index';

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

test('CRC16 CCITT-FALSE menghasilkan check value standar 0x29B1', () => {
  assert.equal(computeCrc16CcittFalse('123456789'), '29B1');
});

test('generateDynamicQris mengubah Tag 01=12, menyisipkan Tag 54, dan lolos verifyQrisCrc', () => {
  const dyn = generateDynamicQris(SAMPLE_STATIC_QRIS, 25143);
  assert.equal(verifyQrisCrc(dyn), true);
  const meta = parseQrisMetadata(dyn);
  assert.equal(meta.pointOfInitiation, '12');
  assert.equal(meta.amount, 25143);
  assert.equal(meta.merchantName, 'TEST STORE');
  assert.equal(meta.nmid, 'ID0000000000001');

  const fields = parseEmvTlv(dyn);
  assert.equal(fields[fields.length - 1]?.tag, '63');
});

test('parseMutationNotification mem-parse format asli Shopee Partner, GoPay, DANA, dan menolak outgoing', () => {
  const shopee = parseMutationNotification({
    message:
      'Pembayaran sebesar Rp1.267 diterimaPembayaran sebesar Rp1.267 telah diterima pada transaksi 145634304540423941.',
  });
  assert.deepEqual(shopee, {
    amount: 1267,
    providerHint: 'shopee_partner',
    rawText:
      'Pembayaran sebesar Rp1.267 diterimaPembayaran sebesar Rp1.267 telah diterima pada transaksi 145634304540423941.',
  });

  const gopay = parseMutationNotification({
    packageName: 'com.gobiz.app',
    message: 'Pembayaran QRIS GoPay masuk IDR 25,143.00.',
  });
  assert.equal(gopay?.amount, 25143);
  assert.equal(gopay?.providerHint, 'gopay_merchant');

  const outgoing = parseMutationNotification({
    message: 'Transfer keluar ke Budi sebesar Rp 50.000 berhasil',
  });
  assert.equal(outgoing, null);
});

test('selectAvailableUniqueAmount menghindari bentrok nominal PENDING aktif', () => {
  const used = [1001, 1002, 1003];
  const res = selectAvailableUniqueAmount(1000, used, { min: 1, max: 4 });
  assert.deepEqual(res, { uniqueCode: 4, totalAmount: 1004 });

  const full = selectAvailableUniqueAmount(1000, [1001, 1002], { min: 1, max: 2 });
  assert.equal(full, null);
});
