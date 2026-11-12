package id.qrisgateway.listener

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.charset.StandardCharsets

data class QrisPreview(
    val rawString: String,
    val isValidCrc: Boolean,
    val isStatic: Boolean,
    val merchantName: String
)

object QrisDecoder {

    fun encodeToBitmap(qrisString: String, sizePx: Int = 640): Bitmap {
        val matrix = QRCodeWriter().encode(qrisString, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun decodeFromUri(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Gagal membuka gambar QRIS")
        val original = stream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("File bukan gambar yang valid")

        val bitmap = scaleDownIfNeeded(original, 1024)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)

        val result = QRCodeReader().decode(binaryBitmap, hints)
        return result.text?.trim() ?: throw IllegalArgumentException("QR Code kosong")
    }

    fun inspectQris(qris: String): QrisPreview {
        val trimmed = qris.trim()
        val isValidCrc = verifyCrc16(trimmed)
        val tags = parseTopLevelTlv(trimmed)
        val isStatic = tags["00"] == "01" && tags["01"] == "11"
        val merchantName = tags["59"] ?: "(tanpa nama merchant)"
        return QrisPreview(
            rawString = trimmed,
            isValidCrc = isValidCrc,
            isStatic = isStatic,
            merchantName = merchantName
        )
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val newW = maxOf(1, (w * ratio).toInt())
        val newH = maxOf(1, (h * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun verifyCrc16(qris: String): Boolean {
        if (qris.length < 12) return false
        if (qris.substring(qris.length - 8, qris.length - 4) != "6304") return false
        val provided = qris.substring(qris.length - 4).uppercase()
        val body = qris.substring(0, qris.length - 4).toByteArray(StandardCharsets.UTF_8)
        var crc = 0xFFFF
        for (b in body) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        val expected = String.format("%04X", crc)
        return provided == expected
    }

    private fun parseTopLevelTlv(payload: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var idx = 0
        while (idx + 4 <= payload.length) {
            val tag = payload.substring(idx, idx + 2)
            val len = payload.substring(idx + 2, idx + 4).toIntOrNull() ?: break
            val end = idx + 4 + len
            if (end > payload.length) break
            map[tag] = payload.substring(idx + 4, end)
            idx = end
        }
        return map
    }
}
