package com.openguard.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Outbox queue for location fixes. Fixes are written here by
 * LocationService and uploaded by UploadWorker.
 *
 * DATA-LOSS RULE: rows are deleted ONLY after the server confirms the
 * upload (see UploadWorker). A failed upload leaves the rows queued.
 */
@Entity(tableName = "queued_locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    /** ISO-8601 UTC, e.g. "2026-11-16T15:30:00.000Z". */
    val recordedAt: String,
    val provider: String?,
)

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM queued_locations ORDER BY id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<LocationEntity>

    @Query("DELETE FROM queued_locations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM queued_locations")
    suspend fun count(): Int
}

/**
 * Outbox queue for security events (SIM change, admin disabled, location
 * permission revoked, ...). Same data-loss rule as locations: rows are
 * deleted ONLY after the server confirms the upload.
 *
 * detailsJson is a JSON object string (or null) — parsed back to a
 * JsonObject at upload time.
 */
@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val detailsJson: String?,
    val confirmed: Boolean,
    /** ISO-8601 UTC. */
    val createdAt: String,
)

@Dao
interface SecurityEventDao {
    @Insert
    suspend fun insert(event: SecurityEventEntity)

    @Query("SELECT * FROM security_events ORDER BY id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<SecurityEventEntity>

    @Query("DELETE FROM security_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM security_events")
    suspend fun count(): Int
}

/**
 * Outbox queue for evidence clips (Lost Mode only). One row per clip
 * file in filesDir/evidence/. Same data-loss rule as locations: a row
 * is marked posted ONLY after the server confirms the metadata
 * (see EvidenceUploadWorker). The file itself is deleted only by the
 * storage cap (newest 50 clips / 500 MB, oldest first).
 *
 * storage is "pending" until the upload decision is made, then "local"
 * (Drive not connected) or "drive". driveFileId/driveLink/uploadedAt
 * are set when the Drive upload succeeds, before the metadata post, so
 * a retry never uploads the same clip twice.
 */
@Entity(tableName = "evidence_clips")
data class EvidenceClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    /** "front" | "back" | "audio". */
    val camera: String,
    /** ISO-8601 UTC. */
    val recordedAt: String,
    val durationS: Int,
    val sizeBytes: Long,
    val storage: String = "pending",
    val driveFileId: String? = null,
    val driveLink: String? = null,
    /** ISO-8601 UTC, when the Drive upload finished. */
    val uploadedAt: String? = null,
    val metadataPosted: Boolean = false,
)

@Dao
interface EvidenceClipDao {
    @Insert
    suspend fun insert(clip: EvidenceClipEntity): Long

    @Query("SELECT * FROM evidence_clips WHERE metadataPosted = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<EvidenceClipEntity>

    @Query(
        "UPDATE evidence_clips SET driveFileId = :fileId, driveLink = :link, " +
            "uploadedAt = :uploadedAt, storage = 'drive' WHERE id = :id",
    )
    suspend fun markDriveUploaded(id: Long, fileId: String, link: String, uploadedAt: String)

    @Query("UPDATE evidence_clips SET storage = :storage, metadataPosted = 1 WHERE id = :id")
    suspend fun markMetadataPosted(id: Long, storage: String)

    @Query("SELECT * FROM evidence_clips ORDER BY id ASC")
    suspend fun allOldestFirst(): List<EvidenceClipEntity>

    @Query("DELETE FROM evidence_clips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM evidence_clips")
    suspend fun count(): Int
}

@Database(
    entities = [LocationEntity::class, SecurityEventEntity::class, EvidenceClipEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun evidenceClipDao(): EvidenceClipDao

    companion object {
        /**
         * v1 -> v2: adds the security_events table. The CREATE TABLE
         * matches the SecurityEventEntity definition exactly (column
         * names, types, nullability, autoincrement PK) so Room's
         * post-migration schema check passes. Queued locations survive
         * untouched — no destructive migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `security_events` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventType` TEXT NOT NULL, `detailsJson` TEXT, " +
                        "`confirmed` INTEGER NOT NULL, `createdAt` TEXT NOT NULL)",
                )
            }
        }

        /**
         * v2 -> v3: adds the evidence_clips table (Workstream A). The
         * CREATE TABLE matches the EvidenceClipEntity definition
         * exactly so Room's post-migration schema check passes.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `evidence_clips` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, `camera` TEXT NOT NULL, " +
                        "`recordedAt` TEXT NOT NULL, `durationS` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `storage` TEXT NOT NULL, " +
                        "`driveFileId` TEXT, `driveLink` TEXT, `uploadedAt` TEXT, " +
                        "`metadataPosted` INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "openguard.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
