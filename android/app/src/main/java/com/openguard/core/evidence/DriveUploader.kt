package com.openguard.core.evidence

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uploads clip bytes to the owner's own Google Drive with the Drive
 * REST API (v3) — raw OkHttp, no Google client library needed. Only
 * the drive.file scope is used, so the app can only see files it
 * created itself.
 *
 * Resumable upload (Google's recommended type for mobile: it survives
 * network interruptions): POST to initiate and get a session URI from
 * the Location header, then a single PUT of the whole clip. Clips are
 * short (12s, SD), so one PUT is enough — the WorkManager retry covers
 * a failed upload by starting a fresh session.
 *
 * All failures throw IOException, so the caller (EvidenceUploadWorker)
 * can retry them. No token or file content is ever logged.
 */
object DriveUploader {

    data class UploadedFile(val id: String, val link: String)

    private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD_API =
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
    private const val FOLDER_NAME = "OpenGuard Evidence"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun mimeTypeFor(camera: String): String =
        if (camera == EvidenceConfig.CAMERA_AUDIO) "audio/mp4" else "video/mp4"

    /** Uploads one clip file into the "OpenGuard Evidence" Drive folder. */
    @Throws(IOException::class)
    fun upload(context: Context, accessToken: String, file: File, mimeType: String): UploadedFile {
        val folderId = ensureFolder(context, accessToken)
        val sessionUri = initiateSession(accessToken, file, mimeType, folderId)
        return uploadBytes(accessToken, sessionUri, file, mimeType)
    }

    /**
     * Finds the app's folder (drive.file scope can list it: the app
     * created it) or creates it. The id is cached in plain prefs — it
     * is not a secret.
     */
    @Throws(IOException::class)
    private fun ensureFolder(context: Context, accessToken: String): String {
        EvidencePrefs.driveFolderId(context)?.let { return it }
        val listUrl = "$DRIVE_API/files".toHttpUrl().newBuilder()
            .addQueryParameter(
                "q",
                "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false",
            )
            .addQueryParameter("fields", "files(id)")
            .build()
        val listRequest = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(listRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Drive folder lookup failed (${response.code})")
            val files = parseJson(response).optJSONArray("files") ?: JSONArray()
            if (files.length() > 0) {
                val id = files.getJSONObject(0).getString("id")
                EvidencePrefs.setDriveFolderId(context, id)
                return id
            }
        }
        val createBody = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val createRequest = Request.Builder()
            .url("$DRIVE_API/files")
            .header("Authorization", "Bearer $accessToken")
            .post(createBody)
            .build()
        client.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Drive folder create failed (${response.code})")
            val id = parseJson(response).getString("id")
            EvidencePrefs.setDriveFolderId(context, id)
            return id
        }
    }

    /**
     * Starts a resumable upload session. Returns the session URI from
     * the Location header (valid about a week; we use it at once).
     */
    @Throws(IOException::class)
    private fun initiateSession(
        accessToken: String,
        file: File,
        mimeType: String,
        folderId: String,
    ): String {
        val metadata = JSONObject()
            .put("name", file.name)
            .put("mimeType", mimeType)
            .put("parents", JSONArray().put(folderId))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(UPLOAD_API)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metadata)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Drive upload session failed (${response.code})")
            }
            return response.header("Location")
                ?: throw IOException("Drive upload session failed (no session URI)")
        }
    }

    /** Uploads the whole clip in one PUT to the session URI. */
    @Throws(IOException::class)
    private fun uploadBytes(
        accessToken: String,
        sessionUri: String,
        file: File,
        mimeType: String,
    ): UploadedFile {
        val request = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $accessToken")
            .put(file.asRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 308) {
                // Resume Incomplete: cannot happen for a single-shot PUT
                // unless the connection broke mid-upload; retry restarts.
                throw IOException("Drive upload incomplete (308)")
            }
            if (!response.isSuccessful) throw IOException("Drive upload failed (${response.code})")
            val id = parseJson(response).getString("id")
            return UploadedFile(id, "https://drive.google.com/file/d/$id/view")
        }
    }

    /** Parses a Drive JSON response body; malformed JSON is an IOException (retryable). */
    @Throws(IOException::class)
    private fun parseJson(response: okhttp3.Response): JSONObject {
        val body = response.body?.string() ?: throw IOException("Drive returned an empty response")
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("Drive returned invalid JSON", e)
        }
    }
}
