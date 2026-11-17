package com.syncedwallpapers.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Local delivery state of one approved schedule on this device. */
enum class LocalState { DOWNLOADING, NOT_READY, READY, LIVE, APPLIED_LATE, WAITING_TAP, NOT_TAPPED, SKIPPED, MISSED, ENDED }

/**
 * Mirror of an approved schedule plus everything the delivery engine needs offline:
 * encrypted image files, the wallpaper ids we set (to detect a manual change), and
 * whether alarms are set. Times are epoch millis in true (server) time.
 */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val proposalId: String,
    val groupId: String,
    val groupName: String,
    val timeZone: String,
    val startAt: Long,
    val endAt: Long,
    val target: String,
    val imagePath: String,
    val focalX: Float,
    val focalY: Float,
    val reminderText: String?,
    val reminderStyle: Int,
    val deliveryMode: String,
    val skipped: Boolean = false,
    val state: LocalState = LocalState.DOWNLOADING,
    val homeFile: String? = null,
    val lockFile: String? = null,
    val appliedHomeId: Int? = null,
    val appliedLockId: Int? = null,
    val appliedLate: Boolean = false,
    val tapNotified: Boolean = false,
    val lastReported: String? = null,
) {
    val isApplied get() = state == LocalState.LIVE || state == LocalState.APPLIED_LATE
    val isReady get() = homeFile != null || lockFile != null
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY startAt")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY startAt")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE proposalId = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE groupId = :groupId")
    suspend fun forGroup(groupId: String): List<ScheduleEntity>

    @Upsert
    suspend fun upsert(entity: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE proposalId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM schedules")
    suspend fun clear()
}

@Database(entities = [ScheduleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao
}
