package id.fotodrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Menempelkan watermark teks (+ tanggal + logo opsional) ke foto JPEG. */
object Watermarker {

    val POSITIONS = arrayOf(
        "Kanan bawah", "Kiri bawah", "Tengah bawah", "Tengah", "Kanan atas", "Kiri atas"
    )

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
    )

    /**
     * @param maxSideOverride dipakai untuk preview; null = ikut pengaturan.
     */
    fun process(ctx: Context, src: File, dst: File, p: Prefs, maxSideOverride: Int? = null) {
        val exif = ExifInterface(src.path)
        val rotation = exif.rotationDegrees
        val maxSide = maxSideOverride ?: p.maxSide

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        require(bounds.outWidth > 0) { "Bukan file gambar yang valid" }

        var sample = 1
        if (maxSide > 0) {
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        }
        var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
        }) ?: error("Gagal membaca gambar")

        if (maxSide > 0 && max(bmp.width, bmp.height) > maxSide) {
            val s = maxSide.toFloat() / max(bmp.width, bmp.height)
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
            bmp.recycle(); bmp = scaled
        }
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle(); bmp = rotated
        }
        if (!bmp.isMutable) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle(); bmp = copy
        }

        drawWatermark(ctx, Canvas(bmp), bmp.width, bmp.height, p, exif)

        FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, p.jpegQuality, it) }
        val outW = bmp.width
        val outH = bmp.height
        bmp.recycle()
        copyExif(exif, dst, outW, outH)
    }

    private fun drawWatermark(ctx: Context, canvas: Canvas, w: Int, h: Int, p: Prefs, exif: ExifInterface) {
        val lines = p.watermarkText.lines().filter { it.isNotBlank() }.toMutableList()
        if (p.showDate) photoDate(exif)?.let { lines += it }

        val alpha = (p.opacity * 255 / 100).coerceIn(0, 255)
        val base = min(w, h).toFloat()
        val textSize = base * p.textSizePct / 100f
        val margin = base * 0.03f
        val lineH = textSize * 1.25f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.argb((alpha * 0.8f).toInt(), 0, 0, 0))
        }

        val logo = loadLogo(ctx, p, (textSize * 3).roundToInt())
        val gap = if (logo != null && lines.isNotEmpty()) textSize * 0.4f else 0f
        val textW = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val blockW = max(textW, logo?.width?.toFloat() ?: 0f)
        val blockH = lines.size * lineH + (logo?.height ?: 0) + gap
        if (blockW == 0f) return

        // 0 kanan bawah, 1 kiri bawah, 2 tengah bawah, 3 tengah, 4 kanan atas, 5 kiri atas
        val align = when (p.position) { 1, 5 -> Paint.Align.LEFT; 2, 3 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT }
        val anchorX = when (align) {
            Paint.Align.LEFT -> margin
            Paint.Align.CENTER -> w / 2f
            else -> w - margin
        }
        val top = when (p.position) {
            3 -> (h - blockH) / 2f
            4, 5 -> margin
            else -> h - margin - blockH
        }

        var y = top
        logo?.let {
            val lx = when (align) {
                Paint.Align.LEFT -> anchorX
                Paint.Align.CENTER -> anchorX - it.width / 2f
                else -> anchorX - it.width
            }
            canvas.drawBitmap(it, lx, y, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
            y += it.height + gap
            it.recycle()
        }
        paint.textAlign = align
        for (line in lines) {
            canvas.drawText(line, anchorX, y - paint.ascent(), paint)
            y += lineH
        }
    }

    private fun photoDate(exif: ExifInterface): String? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return try {
            val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw) ?: return null
            SimpleDateFormat("dd MMM yyyy  HH:mm", Locale("id", "ID")).format(d)
        } catch (e: Exception) { null }
    }

    private fun loadLogo(ctx: Context, p: Prefs, targetH: Int): Bitmap? {
        val uri = p.logoUri?.let(Uri::parse) ?: return null
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            if (b.outHeight <= 0) return null
            var sample = 1
            while (b.outHeight / (sample * 2) >= targetH) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val s = targetH.toFloat() / raw.height
            val scaled = Bitmap.createScaledBitmap(raw, (raw.width * s).roundToInt().coerceAtLeast(1), targetH, true)
            if (scaled !== raw) raw.recycle()
            scaled
        } catch (e: Exception) { null }
    }

    private fun copyExif(src: ExifInterface, dst: File, w: Int, h: Int) {
        try {
            val out = ExifInterface(dst.path)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, w.toString())
            out.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, h.toString())
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "FotoDrive")
            out.saveAttributes()
        } catch (_: Exception) { /* EXIF opsional, abaikan bila gagal */ }
    }
}
