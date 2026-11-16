package com.openguard.core.evidence

import com.google.gson.annotations.SerializedName

// DTOs mirror the backend evidence contract exactly (see
// backend/src/routes/evidence.ts). Field names use @SerializedName
// because the API speaks snake_case. Metadata only — clip bytes never
// go through our server; they go to the owner's own Google Drive.

data class EvidenceMetadataRequest(
    /** "front" | "back" | "audio". */
    @SerializedName("camera") val camera: String,
    /** ISO-8601 UTC, when the clip was recorded. */
    @SerializedName("recorded_at") val recordedAt: String,
    @SerializedName("duration_s") val durationS: Int,
    @SerializedName("size_bytes") val sizeBytes: Long,
    /** "local" | "drive". */
    @SerializedName("storage") val storage: String,
    @SerializedName("drive_file_id") val driveFileId: String?,
    @SerializedName("drive_link") val driveLink: String?,
    /** ISO-8601 UTC, when the Drive upload finished. Null for local clips. */
    @SerializedName("uploaded_at") val uploadedAt: String?,
)

data class EvidenceClipDto(
    @SerializedName("id") val id: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("recorded_at") val recordedAt: String,
    @SerializedName("camera") val camera: String,
    @SerializedName("duration_s") val durationS: Int,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("storage") val storage: String,
    @SerializedName("drive_file_id") val driveFileId: String?,
    @SerializedName("drive_link") val driveLink: String?,
    @SerializedName("uploaded_at") val uploadedAt: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class EvidenceMetadataResponse(
    @SerializedName("clip") val clip: EvidenceClipDto,
)

data class EvidenceListResponse(
    @SerializedName("clips") val clips: List<EvidenceClipDto>,
)
