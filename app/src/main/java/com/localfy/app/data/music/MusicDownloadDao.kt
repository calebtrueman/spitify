package com.localfy.app.data.music

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "music_downloads")
data class MusicDownloadEntity(
    @PrimaryKey val id: String,
    val trackJson: String,
    val state: String = "queued",
    val downloadId: Long? = null,
    val localUri: String? = null,
    val error: String? = null,
    val quality: String? = null,
    val wifiOnly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

fun MusicDownloadEntity.track(): OnlineTrack = requireNotNull(Monochrome.parseTrack(org.json.JSONObject(trackJson)))
val MusicDownloadEntity.active: Boolean get() = state in listOf("queued", "waiting", "finding", "downloading", "checking")

@Dao
interface MusicDownloadDao {
    @Query("SELECT * FROM music_downloads ORDER BY createdAt") fun observe(): Flow<List<MusicDownloadEntity>>
    @Query("SELECT * FROM music_downloads ORDER BY createdAt") suspend fun all(): List<MusicDownloadEntity>
    @Query("SELECT * FROM music_downloads WHERE id = :id") suspend fun get(id: String): MusicDownloadEntity?
    @Query("SELECT * FROM music_downloads WHERE downloadId = :id LIMIT 1") suspend fun byDownloadId(id: Long): MusicDownloadEntity?
    @Upsert suspend fun put(job: MusicDownloadEntity)
}

val MUSIC_DOWNLOAD_MIGRATION_SQL = """CREATE TABLE IF NOT EXISTS `music_downloads` (`id` TEXT NOT NULL, `trackJson` TEXT NOT NULL, `state` TEXT NOT NULL, `downloadId` INTEGER, `localUri` TEXT, `error` TEXT, `quality` TEXT, `wifiOnly` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
