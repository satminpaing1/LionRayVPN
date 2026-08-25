package com.lionray.vpn.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object QrUtil {

    fun generate(content: String, size: Int = 640): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Reads the first QR code found in a bitmap (e.g. picked from the gallery).
     * Tries all four rotations. Returns null when nothing decodable is found.
     */
    fun readFromBitmap(src: Bitmap): String? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var source: RGBLuminanceSource = RGBLuminanceSource(w, h, pixels)
        val reader = MultiFormatReader()
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        for (i in 0 until 4) {
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: Throwable) {
            } finally {
                reader.reset()
            }
            // rotate 90 degrees for the next attempt
            val rotated = rotate(source)
            source = rotated
        }
        return null
    }

    private fun rotate(s: RGBLuminanceSource): RGBLuminanceSource {
        val w = s.width
        val h = s.height
        val flat = IntArray(w * h)
        for (y in 0 until h) {
            val row = s.getRow(y, null as ByteArray?)
            for (x in 0 until w) {
                // 90 degrees clockwise: (x, y) -> (h-1-y, x); new width = h
                flat[x * h + (h - 1 - y)] = row[x].toInt() and 0xFF
            }
        }
        return RGBLuminanceSource(h, w, flat)
    }
}
