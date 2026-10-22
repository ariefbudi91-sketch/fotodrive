package id.fotodrive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Login Google & akses token (scope drive.file: hanya file yang dibuat aplikasi ini). */
object GoogleAuth {
    private val DRIVE_FILE = Scope("https://www.googleapis.com/auth/drive.file")

    /** Akses penuh Drive — hanya diminta bila user memakai folder Drive yang sudah ada. */
    private val DRIVE_FULL = Scope("https://www.googleapis.com/auth/drive")
    private val EMAIL = Scope("email")

    fun request(full: Boolean): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(if (full) DRIVE_FULL else DRIVE_FILE, EMAIL))
            .build()

    /** Ambil token tanpa UI. null = user perlu login lagi dari aplikasi. */
    suspend fun tokenSilently(ctx: Context): String? {
        val full = Prefs(ctx).needsFullScope
        val r = Identity.getAuthorizationClient(ctx).authorize(request(full)).await()
        return if (r.hasResolution()) null else r.accessToken
    }

    fun invalidate(ctx: Context, token: String) {
        try { GoogleAuthUtil.clearToken(ctx, token) } catch (_: Exception) {}
    }
}

class DriveException(val code: Int, body: String) : IOException("Google Drive HTTP $code: ${body.take(300)}")

/** Klien REST Google Drive v3 yang ringan (OkHttp). */
class DriveUploader(private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
    private val json = "application/json; charset=UTF-8".toMediaType()
    private val folderMime = "application/vnd.google-apps.folder"

    private fun exec(b: Request.Builder): String =
        http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw DriveException(it.code, body)
            body
        }

    /** Email akun yang sedang login. */
    fun userEmail(): String? = try {
        JSONObject(exec(Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)").get()))
            .getJSONObject("user").optString("emailAddress").ifEmpty { null }
    } catch (e: Exception) { null }

    /** Nama folder (untuk memastikan link folder valid & bisa diakses). */
    fun folderName(id: String): String =
        JSONObject(exec(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?fields=name,mimeType&supportsAllDrives=true").get()))
            .getString("name")

    fun folderExists(id: String): Boolean = try {
        !JSONObject(exec(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed").get()))
            .optBoolean("trashed", false)
    } catch (e: DriveException) {
        if (e.code == 404) false else throw e
    }

    fun findOrCreateFolder(name: String): String {
        val q = "mimeType='$folderMime' and name='${name.replace("'", "\\'")}' and trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val files = JSONObject(exec(Request.Builder().url(url).get())).getJSONArray("files")
        if (files.length() > 0) return files.getJSONObject(0).getString("id")

        val meta = JSONObject().put("name", name).put("mimeType", folderMime).toString().toRequestBody(json)
        return JSONObject(exec(Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id").post(meta)))
            .getString("id")
    }

    /** Resumable upload — aman untuk file JPEG besar dari DSLR. */
    fun upload(file: File, name: String, parentId: String): String {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", "image/jpeg")
            .put("parents", JSONArray().put(parentId))
        val init = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id&supportsAllDrives=true")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "image/jpeg")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(json))
            .build()
        val location = http.newCall(init).execute().use {
            if (!it.isSuccessful) throw DriveException(it.code, it.body?.string().orEmpty())
            it.header("Location") ?: throw IOException("Drive tidak mengembalikan URL upload")
        }
        val put = Request.Builder().url(location).put(file.asRequestBody("image/jpeg".toMediaType()))
        return JSONObject(exec(put)).getString("id")
    }
}
