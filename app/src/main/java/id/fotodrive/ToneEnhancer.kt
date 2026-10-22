package id.fotodrive

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Perbaikan tone otomatis (mirip "Auto" di Lightroom, versi ringan):
 *  - auto levels: titik hitam & putih dari histogram (0,5% / 99,5%)
 *  - auto exposure: gamma agar kecerahan rata-rata ke ±46%
 *  - kontras lembut (kurva S) + saturasi sedikit dinaikkan
 * Diproses per potongan baris supaya hemat memori untuk foto 24–60 MP.
 */
object ToneEnhancer {

    val LEVELS = arrayOf("Mati", "Ringan", "Sedang", "Kuat")
    private val STRENGTH = floatArrayOf(0f, 0.4f, 0.7f, 1f)

    fun apply(bmp: Bitmap, level: Int) {
        val strength = STRENGTH.getOrElse(level) { 0f }
        if (strength <= 0f || !bmp.isMutable) return
        val w = bmp.width
        val h = bmp.height

        // 1) Histogram kecerahan dari salinan kecil
        val sw = min(320, w)
        val sh = max(1, sw * h / w)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bmp) small.recycle()
        val hist = IntArray(256)
        var sum = 0L
        for (c in px) {
            val l = luma(c)
            hist[l]++; sum += l
        }
        val n = px.size
        val lo = percentile(hist, n, 0.005)
        val hi = percentile(hist, n, 0.995)
        val range = max(hi - lo, 60).toDouble()
        val meanStretched = ((sum.toDouble() / n - lo) / range).coerceIn(0.05, 0.95)
        val gamma = (ln(0.46) / ln(meanStretched)).coerceIn(0.75, 1.3)

        // 2) Lookup table
        val lut = IntArray(256)
        for (i in 0..255) {
            var x = ((i - lo) / range).coerceIn(0.0, 1.0)
            x = x.pow(gamma)
            val s = x * x * (3 - 2 * x)          // kurva S
            x += (s - x) * 0.35
            val enhanced = x * 255.0
            lut[i] = (i + (enhanced - i) * strength).roundToInt().coerceIn(0, 255)
        }
        val sat = 1f + 0.18f * strength

        // 3) Terapkan per potongan 128 baris
        val strip = 128
        val buf = IntArray(w * strip)
        var y = 0
        while (y < h) {
            val rows = min(strip, h - y)
            bmp.getPixels(buf, 0, w, 0, y, w, rows)
            for (k in 0 until w * rows) {
                val c = buf[k]
                var r = lut[(c shr 16) and 0xFF]
                var g = lut[(c shr 8) and 0xFF]
                var b = lut[c and 0xFF]
                val gray = (r * 77 + g * 150 + b * 29) shr 8
                r = (gray + (r - gray) * sat).toInt().coerceIn(0, 255)
                g = (gray + (g - gray) * sat).toInt().coerceIn(0, 255)
                b = (gray + (b - gray) * sat).toInt().coerceIn(0, 255)
                buf[k] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(buf, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    private fun luma(c: Int): Int =
        ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8)

    private fun percentile(hist: IntArray, n: Int, p: Double): Int {
        val target = (n * p).toLong()
        var acc = 0L
        for (i in 0..255) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
    }
}
