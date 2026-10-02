package com.localfy.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "playlist_entries", indices = [Index("playlistId")])
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(tableName = "liked")
data class LikedEntity(@PrimaryKey val songId: Long, val likedAt: Long)

@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @PrimaryKey val songId: Long,
    val playCount: Int,
    val lastPlayed: Long,
    val skipCount: Int,
)

/** Cached lyrics lookups (including "not found" so we don't hit the network repeatedly). */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val synced: String?,
    val plain: String?,
    val source: String,
    val offsetMs: Int,
    val fetchedAt: Long,
    val notFound: Boolean,
)

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val lastRefreshed: Long,
    val subscribedAt: Long,
    /** "podcast" or "audiobook" (LibriVox books reuse the feed machinery). */
    @androidx.room.ColumnInfo(defaultValue = "podcast") val kind: String = "podcast",
)

@Entity(tableName = "episodes", indices = [Index(value = ["podcastId", "guid"], unique = true)])
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val mimeType: String?,
    val pubDate: Long,
    val durationMs: Long,
    val artworkUrl: String?,
    val downloadId: Long?,
    val localPath: String?,
    /** Order within the feed (audiobook chapter number). */
    @androidx.room.ColumnInfo(defaultValue = "0") val position: Int = 0,
)

/** Resume position + played state for anything long-form (episodes, local podcasts, audiobooks). */
@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val played: Boolean,
    val updatedAt: Long,
)

/** Metadata corrections layered over the file's tags (never written into the file itself). */
@Entity(tableName = "metadata_overrides")
data class MetadataOverrideEntity(
    @PrimaryKey val songId: Long,
    /** Guards against MediaStore re-using an id for a different file. */
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val track: Int?,
    val disc: Int?,
    /** "user" edits always win over "online" auto-fixes. */
    val source: String,
    val updatedAt: Long,
)

/** One listen: the raw signal the recommendation engine learns from. */
@Entity(tableName = "play_events", indices = [Index("songId"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val source: String?,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity)

    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt")
    fun observeSince(since: Long): kotlinx.coroutines.flow.Flow<List<PlayEventEntity>>

    @Query("DELETE FROM play_events WHERE startedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata_overrides")
    fun observe(): kotlinx.coroutines.flow.Flow<List<MetadataOverrideEntity>>

    @Query("SELECT * FROM metadata_overrides WHERE songId = :id")
    suspend fun get(id: Long): MetadataOverrideEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(e: MetadataOverrideEntity)

    @Query("DELETE FROM metadata_overrides WHERE songId = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY subscribedAt DESC")
    fun observePodcasts(): kotlinx.coroutines.flow.Flow<List<PodcastEntity>>

    @Query("SELECT * FROM episodes ORDER BY pubDate DESC")
    fun observeEpisodes(): kotlinx.coroutines.flow.Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM resume")
    fun observeResume(): kotlinx.coroutines.flow.Flow<List<ResumeEntity>>

    @Query("SELECT * FROM podcasts")
    suspend fun podcasts(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :url")
    suspend fun byFeed(url: String): PodcastEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertPodcast(p: PodcastEntity): Long

    @androidx.room.Update
    suspend fun updatePodcast(p: PodcastEntity)

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(e: List<EpisodeEntity>): List<Long>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    @Query("UPDATE episodes SET downloadId = :downloadId, localPath = :localPath WHERE id = :id")
    suspend fun setDownload(id: Long, downloadId: Long?, localPath: String?)

    @Query("SELECT * FROM episodes WHERE downloadId IS NOT NULL AND localPath IS NULL")
    suspend fun pendingDownloads(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun episodesOf(podcastId: Long): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteEpisodes(podcastId: Long)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcast(id: Long)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putResume(r: ResumeEntity)

    @Query("SELECT * FROM resume WHERE mediaKey = :key")
    suspend fun resume(key: String): ResumeEntity?
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: Long): LyricsEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(entity: LyricsEntity)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE songId = :songId")
    suspend fun setOffset(songId: Long, offsetMs: Int)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun delete(songId: Long)

    @Query("DELETE FROM lyrics WHERE notFound = 1")
    suspend fun clearMisses()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    fun observeEntries(): Flow<List<PlaylistEntryEntity>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :id")
    suspend fun lastPosition(id: Long): Int

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun entries(id: Long): List<PlaylistEntryEntity>

    @Transaction
    suspend fun append(id: Long, songIds: List<Long>, now: Long) {
        val start = lastPosition(id) + 1
        insertEntries(songIds.mapIndexed { i, s -> PlaylistEntryEntity(playlistId = id, songId = s, position = start + i) })
        touch(id, now)
    }

    @Transaction
    suspend fun removeAt(id: Long, index: Int, now: Long) {
        val current = entries(id)
        current.getOrNull(index)?.let { deleteEntry(it.entryId) }
        touch(id, now)
    }

    @Transaction
    suspend fun reorder(id: Long, songIds: List<Long>, now: Long) {
        clearEntries(id)
        insertEntries(songIds.mapIndexed { i, s -> PlaylistEntryEntity(playlistId = id, songId = s, position = i) })
        touch(id, now)
    }

    @Transaction
    suspend fun delete(id: Long) {
        clearEntries(id)
        deletePlaylist(id)
    }
}

@Dao
interface LikedDao {
    @Query("SELECT * FROM liked ORDER BY likedAt DESC")
    fun observe(): Flow<List<LikedEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun like(entity: LikedEntity)

    @Query("DELETE FROM liked WHERE songId = :songId")
    suspend fun unlike(songId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM liked WHERE songId = :songId)")
    suspend fun isLiked(songId: Long): Boolean
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM play_stats")
    fun observe(): Flow<List<PlayStatEntity>>

    @Query(
        """INSERT INTO play_stats(songId, playCount, lastPlayed, skipCount) VALUES(:songId, 1, :now, 0)
           ON CONFLICT(songId) DO UPDATE SET playCount = playCount + 1, lastPlayed = :now"""
    )
    suspend fun recordPlay(songId: Long, now: Long)

    @Query(
        """INSERT INTO play_stats(songId, playCount, lastPlayed, skipCount) VALUES(:songId, 0, 0, 1)
           ON CONFLICT(songId) DO UPDATE SET skipCount = skipCount + 1"""
    )
    suspend fun recordSkip(songId: Long)
}

@Database(
    entities = [PlaylistEntity::class, PlaylistEntryEntity::class, LikedEntity::class, PlayStatEntity::class, LyricsEntity::class, PodcastEntity::class, EpisodeEntity::class, ResumeEntity::class, MetadataOverrideEntity::class, PlayEventEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class LocalfyDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun liked(): LikedDao
    abstract fun stats(): StatsDao
    abstract fun lyrics(): LyricsDao
    abstract fun podcasts(): PodcastDao
    abstract fun metadata(): MetadataDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): LocalfyDatabase =
            Room.databaseBuilder(context, LocalfyDatabase::class.java, "localfy.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyrics` (`songId` INTEGER NOT NULL, `synced` TEXT, `plain` TEXT, " +
                        "`source` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "`notFound` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
                )
            }
        }
    }
}

/** Copied from Room's exported schema (schemas/.../3.json) so the migration matches exactly. */
private val MIGRATION_2_3_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `podcasts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feedUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `description` TEXT NOT NULL, `artworkUrl` TEXT, `lastRefreshed` INTEGER NOT NULL, `subscribedAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feedUrl` ON `podcasts` (`feedUrl`)",
    "CREATE TABLE IF NOT EXISTS `episodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `podcastId` INTEGER NOT NULL, `guid` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `audioUrl` TEXT NOT NULL, `mimeType` TEXT, `pubDate` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUrl` TEXT, `downloadId` INTEGER, `localPath` TEXT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_episodes_podcastId_guid` ON `episodes` (`podcastId`, `guid`)",
    "CREATE TABLE IF NOT EXISTS `resume` (`mediaKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `played` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaKey`))",
)

private val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `metadata_overrides` (`songId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, `albumArtist` TEXT, `genre` TEXT, `year` INTEGER, `track` INTEGER, `disc` INTEGER, `source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
    "ALTER TABLE `podcasts` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'podcast'",
    "ALTER TABLE `episodes` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0",
)

private val MIGRATION_4_5_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `play_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, `source` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_play_events_songId` ON `play_events` (`songId`)",
    "CREATE INDEX IF NOT EXISTS `index_play_events_startedAt` ON `play_events` (`startedAt`)",
)
