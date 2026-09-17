package com.astralnetwork.poc.wallet

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * AstralQR — QR code bitmap generator.
 *
 * Only generates the visual bitmap. Parsing is handled by
 * AstralQRPayload.parse() in the SDK module.
 */
object AstralQR {

    /**
     * Generate a QR code bitmap from a JSON string (from SDK payload.toJSONString()).
     */
    fun generateQR(jsonString: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = writer.encode(jsonString, BarcodeFormat.QR_CODE, size, size, hints)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) Color.parseColor("#F0F0F0")
                    else Color.parseColor("#000000")
                )
            }
        }
        return bitmap
    }
}
