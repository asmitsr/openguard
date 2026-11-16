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

@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "openguard.db")
                .build()
    }
}
