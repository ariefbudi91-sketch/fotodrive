package id.fotodrive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import java.io.File

/**
 * Mengambil foto JPEG langsung dari kamera DSLR/mirrorless lewat kabel USB OTG
 * memakai protokol PTP/MTP bawaan Android (tanpa root, tanpa library tambahan).
 */
class UsbCameraImporter(
    private val ctx: Context,
    private val p: Prefs,
    private val onNewPhoto: (File, String) -> Unit
) {
    private val usb: UsbManager? = ctx.getSystemService(UsbManager::class.java)
    private var device: UsbDevice? = null
    private var mtp: MtpDevice? = null
    private val seenHandles = HashSet<Int>()
    private var permissionAskedFor: String? = null

    private fun findCamera(): UsbDevice? = usb?.deviceList?.values?.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
    }

    /** Dipanggil berkala oleh CaptureService. */
    fun poll() {
        val usb = usb ?: return
        val dev = findCamera()
        if (dev == null) {
            if (mtp != null) p.log("Kamera USB dilepas")
            close(); permissionAskedFor = null
            return
        }
        if (mtp != null && device?.deviceName == dev.deviceName) {
            importNew(baseline = false)
            return
        }
        close()
        if (!usb.hasPermission(dev)) {
            if (permissionAskedFor != dev.deviceName) {
                permissionAskedFor = dev.deviceName
                val pi = PendingIntent.getBroadcast(
                    ctx, 0,
                    Intent("id.fotodrive.USB_PERMISSION").setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE
                )
                usb.requestPermission(dev, pi)
                p.log("Minta izin akses kamera USB…")
            }
            return
        }
        val conn = usb.openDevice(dev) ?: return
        val m = MtpDevice(dev)
        if (!m.open(conn)) {
            conn.close()
            p.log("Gagal membuka kamera. Pastikan mode USB kamera = PTP/MTP.")
            return
        }
        mtp = m; device = dev; seenHandles.clear()
        val info = m.deviceInfo
        p.log("Kamera terhubung: ${info?.manufacturer.orEmpty()} ${info?.model.orEmpty()}".trim())
        // Foto lama di kartu memori dilewati kecuali user memilih "upload foto yang sudah ada"
        importNew(baseline = !p.uploadExisting)
    }

    private fun importNew(baseline: Boolean) {
        val m = mtp ?: return
        val storages = m.storageIds ?: return
        val newKeys = mutableListOf<String>()
        for (sid in storages) {
            val handles = m.getObjectHandles(sid, 0, 0) ?: continue   // 0 = semua objek
            for (h in handles) {
                if (!seenHandles.add(h)) continue
                val info = m.getObjectInfo(h) ?: continue
                val name = info.name ?: continue
                if (!(name.endsWith(".jpg", true) || name.endsWith(".jpeg", true))) continue
                val key = "usb:$name:${info.dateCreated}:${info.compressedSizeLong}"
                if (p.isProcessed(key)) continue
                if (baseline) { newKeys += key; continue }

                val dst = File(p.incomingDir(), "${System.currentTimeMillis()}_$name")
                if (m.importFile(h, dst.path)) {
                    p.markProcessed(key)
                    onNewPhoto(dst, name)
                } else {
                    seenHandles.remove(h) // coba lagi di putaran berikutnya
                    dst.delete()
                }
            }
        }
        if (baseline) {
            p.markProcessed(newKeys)
            if (newKeys.isNotEmpty()) p.log("${newKeys.size} foto lama di kartu dilewati")
        }
    }

    fun close() {
        try { mtp?.close() } catch (_: Exception) {}
        mtp = null; device = null
    }
}
