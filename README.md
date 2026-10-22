# FotoDrive — Auto-upload foto DSLR/Mirrorless ke Google Drive + Watermark

Aplikasi Android (Kotlin) yang:
1. **Mengambil foto dari kamera** — lewat kabel **USB OTG** (protokol PTP, langsung dari kartu memori kamera) **atau** dari folder tempat aplikasi Wi-Fi kamera menyimpan foto (Canon Camera Connect, Nikon SnapBridge, Sony Creators' App/Imaging Edge, Fujifilm XApp, dll.).
2. **Memberi watermark** — teks bebas (bisa beberapa baris), tanggal & jam pemotretan dari EXIF, logo PNG transparan, 6 pilihan posisi, ukuran, dan kepekatan. Data EXIF penting (kamera, lensa, ISO, aperture, shutter, GPS) tetap dipertahankan.
3. **Upload otomatis ke Google Drive** — ke folder pilihan Anda, dengan antrean yang tahan internet putus dan HP restart (WorkManager), opsi Wi-Fi saja.

## Langkah 1 — Siapkan Google Cloud (sekali saja, ±10 menit)
1. Buka https://console.cloud.google.com → buat project baru (mis. "FotoDrive").
2. **APIs & Services → Library** → cari **Google Drive API** → **Enable**.
3. **APIs & Services → OAuth consent screen** → tipe **External** → isi nama aplikasi & email.
   - Scope: tambahkan `.../auth/drive.file` dan `.../auth/drive` (yang kedua dipakai bila Anda menempel link folder Drive yang sudah ada).
   - **Test users**: tambahkan email Google yang akan dipakai upload.
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID** → tipe **Android**:
   - Package name: `id.fotodrive`
   - SHA-1: `BD:62:DC:76:7A:42:A2:BC:46:51:85:E2:0A:B9:39:5E:5D:D4:B5:A1`
     (dari kunci `app/fotodrive.jks` yang sudah disertakan — APK debug maupun release memakai kunci ini).

> Tidak perlu memasukkan client ID ke kode — Google Play Services mencocokkan package name + SHA-1 secara otomatis.

## Langkah 2 — Build
### Cara A: tanpa Android Studio (GitHub Actions)
1. Buat repository baru di https://github.com/new (boleh Private).
2. Upload semua isi folder `FotoDrive` (termasuk folder `.github`) → Commit.
3. Buka tab **Actions** → tunggu job **Build APK** selesai (±5–8 menit, centang hijau).
4. Klik job tersebut → bagian **Artifacts** → unduh **FotoDrive-apk** → ekstrak → kirim `app-release.apk` ke HP → instal (izinkan "Instal dari sumber tidak dikenal").

### Cara B: Android Studio
1. Buka folder `FotoDrive` di **Android Studio** (Ladybug atau lebih baru) → tunggu Gradle sync.
2. Colok HP (USB debugging aktif) → tekan **Run ▶**. Atau **Build → Build APK(s)** lalu instal APK-nya.
3. Syarat HP: Android 8.0+, Google Play Services. Untuk mode kabel: HP harus mendukung **USB OTG**.

## Langkah 3 — Pakai
1. **Login Google Drive** → izinkan akses.
2. Tentukan tujuan upload: **tempel link folder Google Drive** yang sudah ada (buka folder di Drive → Bagikan/Salin link), atau kosongkan dan isi **nama folder baru** (dibuat otomatis). Setelah menempel link, tekan Login lagi; aplikasi akan menampilkan email akun dan nama folder tujuan sebagai konfirmasi. Folder milik orang lain juga bisa, asalkan akun Anda punya akses *Editor*. Tombol **Ganti akun Google** untuk login dengan akun lain.
3. Atur **watermark**, lalu tekan **Coba watermark pada satu foto** untuk melihat hasilnya.
4. Pilih sumber:
   - **Kabel**: set USB kamera ke mode **PTP / MTP / "Connect to PC"**, colok ke HP pakai kabel OTG. Saat Android bertanya, pilih **FotoDrive** dan centang "selalu". Pemantauan langsung mulai otomatis.
   - **Wi-Fi**: aktifkan auto-transfer di aplikasi kamera bawaan merek Anda, lalu di FotoDrive tekan **Pilih folder foto** dan arahkan ke folder tempat aplikasi itu menyimpan foto (biasanya di `DCIM` atau `Pictures`).
5. Tekan **Mulai**. Notifikasi "FotoDrive aktif" muncul; setiap foto baru otomatis diberi watermark dan diupload. Lihat progres di bagian **Aktivitas**.

Secara default hanya **foto baru** yang diupload. Aktifkan "Upload juga foto lama" bila ingin semua foto di kartu/folder ikut terupload.

## Tips & batasan
- **Format**: yang diproses hanya **JPEG**. Atur kamera ke JPEG atau RAW+JPEG (file RAW diabaikan).
- **Foto besar** (24–60 MP): bila HP kehabisan memori, pilih *Ukuran maksimal* 4096 px atau lebih kecil. Ukuran 2048 px juga jauh lebih hemat kuota untuk upload di lapangan.
- **Mode kabel** butuh kamera yang tetap bisa memotret saat terhubung USB PTP. Kebanyakan Canon & Nikon bisa; beberapa Sony/Fuji mengunci kamera saat mode USB — untuk kamera seperti itu gunakan mode Wi-Fi + folder.
- Supaya tidak dimatikan sistem, buka **Pengaturan HP → Aplikasi → FotoDrive → Baterai → Tidak dibatasi** (penting di Xiaomi, Oppo, Vivo, Samsung).
- Scope `drive.file` artinya aplikasi hanya bisa melihat file/folder yang dibuatnya sendiri — tidak bisa membaca isi Drive Anda yang lain.
- Selama OAuth consent screen masih status *Testing*, login hanya untuk *test users* dan token perlu login ulang tiap 7 hari. Untuk pemakaian permanen, ubah ke *In production* (scope `drive.file` tidak memerlukan verifikasi ketat dari Google).

## Struktur kode
| File | Fungsi |
|---|---|
| `MainActivity.kt` | Layar pengaturan, login, preview |
| `CaptureService.kt` | Service latar depan: memantau USB & folder tiap 4 detik |
| `UsbCameraImporter.kt` | Ambil JPEG dari kamera via PTP (`android.mtp.MtpDevice`) |
| `Watermarker.kt` | Render watermark teks/tanggal/logo, rotasi EXIF, salin EXIF |
| `UploadWorker.kt` | Antrean watermark + upload (retry otomatis) |
| `GoogleDrive.kt` | Login (Authorization API) & Drive REST v3 resumable upload |
| `Prefs.kt` | Pengaturan, daftar foto yang sudah diproses, log |
