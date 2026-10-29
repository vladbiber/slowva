package com.slowva.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** STREAM = an online song saved to the library; it plays over the network. */
enum class TrackSource { LOCAL, DEMO, IMPORTED, STREAM }

/** Stored tracks: imported and saved online songs. Demo and device tracks are rebuilt on load. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val source: TrackSource,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val uri: String,
    val durationMs: Long,
    val genre: String? = null,
    val coverUrl: String? = null,
    val addedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val addedAt: Long,
)

@Entity(tableName = "playlists", indices = [Index(value = ["name"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverUri: String?,
    val position: Int,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("trackId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val position: Int,
    val trackId: String,
)

/** One listen of a song. Title and the rest are kept so an online song that isn't in the library can still be shown and replayed. */
@Entity(tableName = "plays", indices = [Index("trackId")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val coverUri: String?,
    val durationMs: Long,
)

/** Single row (id 0): where playback was when the app last ran. */
@Entity(tableName = "playback_snapshot")
data class PlaybackSnapshotEntity(
    @PrimaryKey val id: Int = 0,
    val trackId: String?,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val volume: Float,
)

/** One kind of failure; identical fingerprints are grouped here. */
@Entity(tableName = "diagnostic_issues")
data class DiagnosticIssueEntity(
    @PrimaryKey val fingerprint: String,
    val feature: String,
    val action: String,
    val component: String,
    val errorClass: String,
    val verdict: String,
    val count: Int,
    val distinctItems: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val resolved: Boolean = false,
)

/** A single failure with its evidence, already redacted. */
@Entity(
    tableName = "diagnostic_events",
    indices = [Index("fingerprint"), Index("at")],
)
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val at: Long,
    val item: String?,
    val attempts: String?,
    val httpStatus: Int?,
    val message: String?,
    val stackTrace: String?,
    val environment: String?,
)
