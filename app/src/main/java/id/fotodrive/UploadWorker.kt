package id.fotodrive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Satu job per foto: watermark → upload ke Google Drive.
 * WorkManager otomatis mengulang saat internet putus / HP restart.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val p = Prefs(ctx)
        val src = File(inputData.getString(KEY_PATH) ?: return@withContext Result.failure())
        val name = inputData.getString(KEY_NAME) ?: src.name
        val outDir = File(ctx.filesDir, "out").apply { mkdirs() }
        val out = File(outDir, src.name)
        if (!src.exists() && !out.exists()) return@withContext Result.success()

        try {
            if (!out.exists()) {
                // Satu per satu agar foto 24–60 MP tidak membuat memori habis
                processLock.withLock {
                    val tmp = File(outDir, src.name + ".tmp")
                    Watermarker.process(ctx, src, tmp, p)
                    tmp.renameTo(out)
                }
                src.delete()
            }

            val token = GoogleAuth.tokenSilently(ctx)
            if (token == null) {
                p.log("Belum login Google. Buka aplikasi lalu tekan Login.")
                return@withContext Result.retry()
            }
            val drive = DriveUploader(token)
            try {
                val linked = p.driveLinkId
                val folderId = if (linked != null) linked else {
                    var id = p.driveFolderId
                    if (id == null || !drive.folderExists(id)) {
                        id = drive.findOrCreateFolder(p.driveFolderName)
                        p.driveFolderId = id
                    }
                    id
                }
                drive.upload(out, name, folderId)
            } catch (e: DriveException) {
                if (e.code == 401) GoogleAuth.invalidate(ctx, token)
                if (e.code == 404 && p.driveLinkId != null)
                    p.log("Folder dari link tidak ditemukan / akun tidak punya akses edit")
                throw e
            }
            out.delete()
            p.log("✓ $name terupload")
            Result.success()
        } catch (e: OutOfMemoryError) {
            p.log("✗ $name: memori HP tidak cukup. Kecilkan 'Ukuran maksimal'.")
            Result.failure()
        } catch (e: Exception) {
            p.log("✗ $name: ${e.message} (coba lagi otomatis)")
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private val processLock = Mutex()

        fun enqueue(ctx: Context, file: File, displayName: String) {
            val p = Prefs(ctx)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_PATH to file.absolutePath, KEY_NAME to displayName))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("upload")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-${file.name}", ExistingWorkPolicy.KEEP, req)
            p.log("Antre: $displayName")
        }
    }
}
