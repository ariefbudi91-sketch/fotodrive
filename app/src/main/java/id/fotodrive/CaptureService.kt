package id.fotodrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service latar depan yang terus memantau:
 *  1) kamera yang dicolok via USB OTG, dan
 *  2) folder tempat aplikasi Wi-Fi kamera (Canon/Nikon/Sony/Fuji, dll.) menyimpan foto.
 * Setiap foto baru dimasukkan ke antrean UploadWorker.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var p: Prefs
    private lateinit var usb: UsbCameraImporter
    private val lastSize = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        p = Prefs(this)
        createChannel()
        val n = notification("Memantau kamera & folder foto…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, n)

        p.running = true
        p.log("Pemantauan dimulai")
        usb = UsbCameraImporter(this, p) { file, name -> UploadWorker.enqueue(this, file, name) }

        scope.launch {
            while (isActive) {
                try { usb.poll() } catch (e: Exception) { p.log("USB: ${e.message}") }
                try { scanFolder() } catch (e: Exception) { p.log("Folder: ${e.message}") }
                delay(4000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        usb.close()
        p.running = false
        p.log("Pemantauan dihentikan")
        super.onDestroy()
    }

    private data class Entry(val uri: Uri, val name: String, val size: Long, val modified: Long)

    private fun scanFolder() {
        val tree = p.folderUri?.let(Uri::parse) ?: return
        val files = listJpegs(tree)

        if (p.needBaseline) {
            p.needBaseline = false
            if (!p.uploadExisting) {
                p.markProcessed(files.map { key(it) })
                if (files.isNotEmpty()) p.log("${files.size} foto lama di folder dilewati")
                return
            }
        }

        for (f in files) {
            val k = key(f)
            if (p.isProcessed(k)) continue
            // Tunggu sampai ukuran file stabil (aplikasi kamera mungkin masih menulis)
            val prev = lastSize.put(f.uri.toString(), f.size)
            if (prev == null || prev != f.size || f.size == 0L) continue

            val dst = File(p.incomingDir(), "${System.currentTimeMillis()}_${f.name}")
            contentResolver.openInputStream(f.uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: continue
            p.markProcessed(k)
            lastSize.remove(f.uri.toString())
            UploadWorker.enqueue(this, dst, f.name)
        }
    }

    private fun key(e: Entry) = "dir:${e.uri}:${e.modified}"

    private fun listJpegs(tree: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (depth < 4) walk(id, depth + 1)
                    } else if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) {
                        out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Upload foto", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("FotoDrive aktif")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "fotodrive"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "id.fotodrive.STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java))

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
