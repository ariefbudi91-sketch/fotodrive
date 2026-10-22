package id.fotodrive

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Semua pengaturan aplikasi disimpan di SharedPreferences. */
class Prefs(context: Context) {
    private val ctx = context.applicationContext
    val sp = ctx.getSharedPreferences("fotodrive", Context.MODE_PRIVATE)

    private fun str(k: String, d: String?) = sp.getString(k, d)
    private fun putStr(k: String, v: String?) = sp.edit().putString(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // Sumber foto
    var folderUri: String? get() = str("folderUri", null); set(v) = putStr("folderUri", v)
    var needBaseline: Boolean get() = bool("needBaseline", true); set(v) = putBool("needBaseline", v)
    var uploadExisting: Boolean get() = bool("uploadExisting", false); set(v) = putBool("uploadExisting", v)

    // Watermark
    var watermarkText: String get() = str("wmText", "© Nama Anda")!!; set(v) = putStr("wmText", v)
    var position: Int get() = int("position", 0); set(v) = putInt("position", v)
    var textSizePct: Int get() = int("textSizePct", 3); set(v) = putInt("textSizePct", v)
    var opacity: Int get() = int("opacity", 80); set(v) = putInt("opacity", v)
    var showDate: Boolean get() = bool("showDate", true); set(v) = putBool("showDate", v)
    var logoUri: String? get() = str("logoUri", null); set(v) = putStr("logoUri", v)

    // Output & upload
    var maxSide: Int get() = int("maxSide", 0); set(v) = putInt("maxSide", v)
    var jpegQuality: Int get() = int("jpegQuality", 92); set(v) = putInt("jpegQuality", v)
    var driveFolderName: String get() = str("driveFolder", "Foto Kamera")!!; set(v) = putStr("driveFolder", v)
    var driveFolderId: String? get() = str("driveFolderId", null); set(v) = putStr("driveFolderId", v)
    var driveFolderLink: String get() = str("driveLink", "")!!; set(v) = putStr("driveLink", v)
    var accountEmail: String? get() = str("email", null); set(v) = putStr("email", v)
    var authFullScope: Boolean get() = bool("authFull", false); set(v) = putBool("authFull", v)

    /** ID folder dari link Drive yang ditempel user (null = pakai folder buatan aplikasi). */
    val driveLinkId: String? get() = parseFolderId(driveFolderLink)
    val needsFullScope: Boolean get() = driveLinkId != null

    var wifiOnly: Boolean get() = bool("wifiOnly", false); set(v) = putBool("wifiOnly", v)
    var authorized: Boolean get() = bool("authorized", false); set(v) = putBool("authorized", v)
    var running: Boolean get() = bool("running", false); set(v) = putBool("running", v)

    // Daftar foto yang sudah diproses (agar tidak terupload dua kali)
    @Synchronized
    fun isProcessed(key: String) = sp.getStringSet("done", emptySet())!!.contains(key)

    @Synchronized
    fun markProcessed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val s = HashSet(sp.getStringSet("done", emptySet())!!)
        s.addAll(keys)
        sp.edit().putStringSet("done", s).apply()
    }

    fun markProcessed(key: String) = markProcessed(listOf(key))

    // Log aktivitas (50 baris terakhir)
    @Synchronized
    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = (str("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(49)
        putStr("log", (lines + "$time  $msg").joinToString("\n"))
    }

    val logText: String get() = str("log", "") ?: ""

    companion object {
        /** Terima link "drive.google.com/drive/folders/<ID>", "...?id=<ID>", atau ID saja. */
        fun parseFolderId(input: String?): String? {
            val s = input?.trim().orEmpty()
            if (s.isEmpty()) return null
            Regex("folders/([A-Za-z0-9_-]{10,})").find(s)?.let { return it.groupValues[1] }
            Regex("[?&]id=([A-Za-z0-9_-]{10,})").find(s)?.let { return it.groupValues[1] }
            return if (Regex("^[A-Za-z0-9_-]{10,}$").matches(s)) s else null
        }
    }

    fun incomingDir() = File(ctx.filesDir, "incoming").apply { mkdirs() }
}
