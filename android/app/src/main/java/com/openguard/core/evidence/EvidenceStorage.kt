package com.openguard.core.evidence

import android.content.Context
import com.openguard.core.database.EvidenceClipDao
import java.io.File

/**
 * App-private clip storage: filesDir/evidence/. No other app can read
 * these files, and they are not in shared storage or the MediaStore.
 */
object EvidenceStorage {

    fun dir(context: Context): File =
        File(context.filesDir, "evidence").apply { mkdirs() }

    /**
     * Enforces the storage cap: keep the newest 50 clips or 500 MB
     * total, oldest deleted first (file + Room row together). Also
     * deletes orphan files with no Room row (e.g. a crash mid-record).
     * A pruned clip whose metadata was already posted stays listed on
     * the dashboard as "on device" — the file itself is gone, which is
     * what the cap promises; Drive copies survive in Drive.
     */
    suspend fun enforceCap(context: Context, dao: EvidenceClipDao) {
        val clips = dao.allOldestFirst()
        var totalBytes = clips.sumOf { it.sizeBytes }
        var count = clips.size
        val deleted = mutableSetOf<String>()
        for (clip in clips) {
            if (count <= EvidenceConfig.MAX_CLIPS &&
                totalBytes <= EvidenceConfig.MAX_TOTAL_BYTES
            ) {
                break
            }
            File(dir(context), clip.fileName).delete()
            dao.deleteById(clip.id)
            deleted += clip.fileName
            totalBytes -= clip.sizeBytes
            count--
        }
        val known = clips.map { it.fileName }.toSet() - deleted
        dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name !in known }
            .forEach { it.delete() }
    }
}
