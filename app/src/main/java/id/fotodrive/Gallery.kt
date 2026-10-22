package id.fotodrive

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Menyimpan salinan foto hasil ke galeri HP, folder Pictures/FotoDrive (Android 10+). */
object Gallery {
    fun save(ctx: Context, file: File, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FotoDrive")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            Prefs(ctx).log("Gagal simpan ke galeri: ${e.message}")
            false
        }
    }
}
