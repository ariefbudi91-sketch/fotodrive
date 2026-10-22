package id.fotodrive

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs

    private lateinit var tvAccount: TextView
    private lateinit var etDriveFolder: EditText
    private lateinit var etDriveLink: EditText
    private lateinit var tvLinkStatus: TextView
    private lateinit var swWifi: MaterialSwitch
    private lateinit var swGallery: MaterialSwitch
    private lateinit var spTone: Spinner
    private lateinit var tvFolder: TextView
    private lateinit var swExisting: MaterialSwitch
    private lateinit var etText: EditText
    private lateinit var swDate: MaterialSwitch
    private lateinit var spPosition: Spinner
    private lateinit var sbSize: SeekBar
    private lateinit var tvSize: TextView
    private lateinit var sbOpacity: SeekBar
    private lateinit var tvOpacity: TextView
    private lateinit var tvLogo: TextView
    private lateinit var spMaxSide: Spinner
    private lateinit var ivPreview: ImageView
    private lateinit var btnStart: MaterialButton
    private lateinit var tvLog: TextView

    private val maxSideValues = intArrayOf(0, 4096, 3000, 2048, 1600)
    private val maxSideLabels = arrayOf(
        "Asli (resolusi penuh)", "4096 px", "3000 px", "2048 px (hemat kuota)", "1600 px (sangat hemat)"
    )

    private val logListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "log" || key == "running") runOnUiThread { refreshStatus() }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        p.folderUri = uri.toString()
        p.needBaseline = true
        refreshFolder()
    }

    private val pickLogo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        p.logoUri = uri.toString()
        refreshLogo()
    }

    private val pickPreview = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { preview(it) }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            onAuthorized(r.accessToken != null)
        } catch (e: Exception) {
            toast("Login dibatalkan / gagal: ${e.message}")
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        p = Prefs(this)

        tvAccount = findViewById(R.id.tvAccount)
        etDriveFolder = findViewById(R.id.etDriveFolder)
        etDriveLink = findViewById(R.id.etDriveLink)
        tvLinkStatus = findViewById(R.id.tvLinkStatus)
        swWifi = findViewById(R.id.swWifi)
        swGallery = findViewById(R.id.swGallery)
        spTone = findViewById(R.id.spTone)
        tvFolder = findViewById(R.id.tvFolder)
        swExisting = findViewById(R.id.swExisting)
        etText = findViewById(R.id.etText)
        swDate = findViewById(R.id.swDate)
        spPosition = findViewById(R.id.spPosition)
        sbSize = findViewById(R.id.sbSize)
        tvSize = findViewById(R.id.tvSize)
        sbOpacity = findViewById(R.id.sbOpacity)
        tvOpacity = findViewById(R.id.tvOpacity)
        tvLogo = findViewById(R.id.tvLogo)
        spMaxSide = findViewById(R.id.spMaxSide)
        ivPreview = findViewById(R.id.ivPreview)
        btnStart = findViewById(R.id.btnStart)
        tvLog = findViewById(R.id.tvLog)

        spTone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ToneEnhancer.LEVELS)
        spPosition.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Watermarker.POSITIONS)
        spMaxSide.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, maxSideLabels)

        loadSettings()

        sbSize.setOnSeekBarChangeListener(seek { tvSize.text = "Ukuran teks: ${it + 1}%" })
        sbOpacity.setOnSeekBarChangeListener(seek { tvOpacity.text = "Kepekatan: ${it + 10}%" })

        findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener { saveSettings(); login() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener { switchAccount() }
        etDriveLink.setOnFocusChangeListener { _, has -> if (!has) { saveSettings(); refreshAccount() } }
        findViewById<MaterialButton>(R.id.btnFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<MaterialButton>(R.id.btnLogo).setOnClickListener { pickLogo.launch(arrayOf("image/*")) }
        findViewById<MaterialButton>(R.id.btnClearLogo).setOnClickListener { p.logoUri = null; refreshLogo() }
        findViewById<MaterialButton>(R.id.btnPreview).setOnClickListener { saveSettings(); pickPreview.launch("image/jpeg") }
        btnStart.setOnClickListener { toggle() }

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** Kamera dicolok → langsung mulai memantau bila sudah login. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && (p.authorized || p.saveToGallery) && !p.running) {
            saveSettings()
            CaptureService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        p.sp.registerOnSharedPreferenceChangeListener(logListener)
        refreshStatus()
    }

    override fun onPause() {
        saveSettings()
        p.sp.unregisterOnSharedPreferenceChangeListener(logListener)
        super.onPause()
    }

    private fun loadSettings() {
        etDriveFolder.setText(p.driveFolderName)
        swWifi.isChecked = p.wifiOnly
        swGallery.isChecked = p.saveToGallery
        spTone.setSelection(p.toneLevel)
        swExisting.isChecked = p.uploadExisting
        etText.setText(p.watermarkText)
        swDate.isChecked = p.showDate
        spPosition.setSelection(p.position)
        sbSize.progress = p.textSizePct - 1
        tvSize.text = "Ukuran teks: ${p.textSizePct}%"
        sbOpacity.progress = p.opacity - 10
        tvOpacity.text = "Kepekatan: ${p.opacity}%"
        spMaxSide.setSelection(maxSideValues.indexOf(p.maxSide).coerceAtLeast(0))
        etDriveLink.setText(p.driveFolderLink)
        refreshAccount(); refreshFolder(); refreshLogo()
    }

    private fun saveSettings() {
        val folderName = etDriveFolder.text.toString().trim().ifEmpty { "Foto Kamera" }
        if (folderName != p.driveFolderName) { p.driveFolderName = folderName; p.driveFolderId = null }
        val link = etDriveLink.text.toString().trim()
        if (link != p.driveFolderLink) {
            p.driveFolderLink = link
            // Folder yang sudah ada butuh izin akses Drive penuh → perlu login ulang
            if (p.needsFullScope && !p.authFullScope) p.authorized = false
        }
        p.wifiOnly = swWifi.isChecked
        p.saveToGallery = swGallery.isChecked
        p.toneLevel = spTone.selectedItemPosition
        p.uploadExisting = swExisting.isChecked
        p.watermarkText = etText.text.toString()
        p.showDate = swDate.isChecked
        p.position = spPosition.selectedItemPosition
        p.textSizePct = sbSize.progress + 1
        p.opacity = sbOpacity.progress + 10
        p.maxSide = maxSideValues[spMaxSide.selectedItemPosition]
    }

    private fun refreshFolder() {
        tvFolder.text = p.folderUri?.let { "Folder: " + Uri.decode(it).substringAfterLast(':') } ?: "Folder: (tidak dipakai)"
    }

    private fun refreshLogo() {
        tvLogo.text = if (p.logoUri != null) "Logo dipakai ✓" else "Tanpa logo"
    }

    private fun refreshStatus() {
        btnStart.text = if (p.running) "Hentikan" else "Mulai"
        tvLog.text = p.logText.lines().reversed().joinToString("\n")
    }

    private fun refreshAccount() {
        tvAccount.text = when {
            !p.authorized -> if (p.needsFullScope) "Belum login (login ulang untuk memakai folder dari link)" else "Belum login"
            p.accountEmail != null -> "Login sebagai ${p.accountEmail} ✓"
            else -> "Terhubung ke Google Drive ✓"
        }
        val raw = etDriveLink.text.toString().trim()
        tvLinkStatus.text = when {
            raw.isEmpty() -> ""
            Prefs.parseFolderId(raw) == null -> "⚠ Link folder tidak dikenali"
            else -> tvLinkStatus.text.takeIf { it.startsWith("📁") } ?: "Link folder terbaca ✓"
        }
        findViewById<android.view.View>(R.id.tilFolderName).isEnabled = !p.needsFullScope
    }

    private fun switchAccount() {
        // Cabut izin agar saat Login berikutnya muncul pilihan akun
        @Suppress("DEPRECATION")
        com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
            this, com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        ).revokeAccess()
        p.authorized = false; p.accountEmail = null
        refreshAccount()
        toast("Akun dilepas. Tekan Login untuk memilih akun lain.")
    }

    /** Setelah login: ambil email akun & cek folder dari link. */
    private fun verifyDrive() {
        lifecycleScope.launch {
            val (email, folderMsg) = withContext(Dispatchers.IO) {
                try {
                    val token = GoogleAuth.tokenSilently(this@MainActivity) ?: return@withContext null to null
                    val d = DriveUploader(token)
                    val msg = p.driveLinkId?.let { id ->
                        try { "📁 Upload ke folder: ${d.folderName(id)}" }
                        catch (e: DriveException) { "⚠ Folder tidak bisa diakses akun ini (HTTP ${e.code})" }
                    }
                    d.userEmail() to msg
                } catch (e: Exception) { null to null }
            }
            if (email != null) p.accountEmail = email
            refreshAccount()
            folderMsg?.let { tvLinkStatus.text = it }
        }
    }

    private fun login() {
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request(p.needsFullScope))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else onAuthorized(true)
            }
            .addOnFailureListener { toast("Login gagal: ${it.message}") }
    }

    private fun onAuthorized(ok: Boolean) {
        p.authorized = ok
        if (ok) {
            p.authFullScope = p.needsFullScope
            toast("Berhasil terhubung ke Google Drive")
            verifyDrive()
        }
        refreshAccount()
    }

    private fun toggle() {
        saveSettings()
        if (p.running) {
            CaptureService.stop(this)
        } else {
            if (!p.authorized && !p.saveToGallery) { toast("Login Google Drive dulu"); return }
            if (!p.authorized) toast("Belum login Drive: foto hanya disimpan ke galeri")
            if (p.driveFolderLink.isNotBlank() && p.driveLinkId == null) { toast("Link folder Drive tidak valid"); return }
            if (p.watermarkText.isBlank() && p.logoUri == null && !p.showDate) {
                toast("Isi teks watermark atau pilih logo"); return
            }
            CaptureService.start(this)
        }
        btnStart.postDelayed({ refreshStatus() }, 500)
    }

    private fun preview(uri: Uri) {
        toast("Membuat preview…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val src = File(cacheDir, "preview_src.jpg")
                    val dst = File(cacheDir, "preview_out.jpg")
                    contentResolver.openInputStream(uri)?.use { i -> src.outputStream().use { i.copyTo(it) } }
                    Watermarker.process(this@MainActivity, src, dst, p, maxSideOverride = 1600)
                    BitmapFactory.decodeFile(dst.path)
                } catch (e: Exception) { null }
            }
            if (result != null) ivPreview.setImageBitmap(result) else toast("Gagal membuat preview")
        }
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
