package com.edusing.chamcongtvbox.data.hikvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Chuẩn hoá ảnh khuôn mặt (PNG / WebP / JPEG cỡ lớn từ Web tổng hoặc camera điện thoại)
 * thành ảnh **JPEG đạt chuẩn sinh trắc học Hikvision MinMoe** (`≤ 640px`, `≤ 160 KB`, dưới ngưỡng cứng 200 KB):
 * - Giải mã an toàn bằng `inSampleSize` tránh tràn RAM trên Android TV Box RAM 1–2 GB.
 * - Nếu chạy trên JVM unit test (không có `BitmapFactory` thật) mà dữ liệu vốn đã là JPEG hợp lệ ≤ 200 KB thì giữ nguyên.
 */
internal object HikvisionFaceNormalizer {

    const val MAX_HIK_FACE_BYTES = 195_000
    private const val TARGET_MAX_BYTES = 150_000
    private const val MAX_EDGE_PX = 640
    private const val MIN_VALID_BYTES = 512

    fun normalizeToHikvisionJpeg(rawBytes: ByteArray): ByteArray? {
        if (rawBytes.size < MIN_VALID_BYTES) return null

        val decoded = decodeScaledBitmap(rawBytes)
        if (decoded == null) {
            // Môi trường JVM unit test (android.graphics trả stub null): chấp nhận JPEG hợp lệ sẵn ≤ 195 KB
            return if (isJpegHeader(rawBytes) && rawBytes.size <= MAX_HIK_FACE_BYTES) rawBytes else null
        }

        val scaled = scaleDownIfNeeded(decoded)
        try {
            var quality = 86
            var jpeg = compressJpeg(scaled, quality)
            while (jpeg.size > TARGET_MAX_BYTES && quality >= 46) {
                quality -= 8
                jpeg = compressJpeg(scaled, quality)
            }
            if (jpeg.size > MAX_HIK_FACE_BYTES) {
                val smaller = Bitmap.createScaledBitmap(
                    scaled,
                    (scaled.width * 3 / 4).coerceAtLeast(160),
                    (scaled.height * 3 / 4).coerceAtLeast(160),
                    true,
                )
                try {
                    jpeg = compressJpeg(smaller, 68)
                } finally {
                    if (smaller !== scaled) smaller.recycle()
                }
            }
            return jpeg.takeIf { it.size in MIN_VALID_BYTES..MAX_HIK_FACE_BYTES }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    fun isJpegHeader(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF

    private fun decodeScaledBitmap(rawBytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return@runCatching null

        var sample = 1
        while (maxOf(rawW / sample, rawH / sample) > MAX_EDGE_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
    }.getOrNull()

    private fun scaleDownIfNeeded(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= MAX_EDGE_PX) return src
        val ratio = MAX_EDGE_PX.toFloat() / maxSide.toFloat()
        val dstW = (src.width * ratio).toInt().coerceAtLeast(120)
        val dstH = (src.height * ratio).toInt().coerceAtLeast(120)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
