package com.slowva.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** The user's genre for one song, by its media id. */
@Entity(tableName = "song_genre_override")
data class SongGenreOverride(
    @PrimaryKey val trackId: String,
    val genre: String,
    val at: Long,
)

/** Genre per artist, lowercase name; [source] is "user" when he picked it. */
@Entity(tableName = "artist_genre")
data class ArtistGenre(
    @PrimaryKey val artist: String,
    val genre: String,
    val source: String,
    val at: Long,
)

/** The user's edited copy of a genre preset; the bundled one stays in assets. */
@Entity(tableName = "genre_presets")
data class GenrePresetEntity(
    @PrimaryKey val presetKey: String,
    /** ten graphic EQ gains in dB, comma separated */
    val bands: String,
    val highpassHz: Float,
    val loudness: Boolean,
    val tape: Float,
    val compress: Float,
    val at: Long,
)

/** Correction for one output device: AutoEQ filters and crossfeed. */
@Entity(tableName = "device_presets")
data class DevicePresetEntity(
    @PrimaryKey val deviceKey: String,
    val name: String,
    val type: Int,
    val eqOn: Boolean,
    /** "PK 105 -2.3 0.7;LSC ..." */
    val filters: String,
    val preampDb: Float,
    val eqName: String?,
    val crossfeed: String?,
    val at: Long,
)

/** ReplayGain per track, from its tags or measured once. */
@Entity(tableName = "track_loudness")
data class TrackLoudness(
    @PrimaryKey val trackId: String,
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val trackPeak: Float?,
    val albumPeak: Float?,
    val lufs: Float?,
    /** "tag", "measured" or "failed" */
    val source: String,
    val at: Long,
)

@Dao
interface SoundDao {
    @Query("SELECT genre FROM song_genre_override WHERE trackId = :trackId")
    suspend fun songGenre(trackId: String): String?

    @Upsert
    suspend fun setSongGenre(row: SongGenreOverride)

    @Query("SELECT genre FROM artist_genre WHERE artist = :artist")
    suspend fun artistGenre(artist: String): String?

    @Upsert
    suspend fun setArtistGenre(row: ArtistGenre)

    @Query("SELECT genre FROM tracks WHERE id = :trackId")
    suspend fun savedGenre(trackId: String): String?

    @Query("SELECT * FROM genre_presets")
    suspend fun genrePresets(): List<GenrePresetEntity>

    @Upsert
    suspend fun saveGenrePreset(row: GenrePresetEntity)

    @Query("DELETE FROM genre_presets WHERE presetKey = :key")
    suspend fun deleteGenrePreset(key: String)

    @Query("SELECT * FROM device_presets")
    suspend fun devicePresets(): List<DevicePresetEntity>

    @Upsert
    suspend fun saveDevicePreset(row: DevicePresetEntity)

    @Query("SELECT * FROM track_loudness WHERE trackId = :trackId")
    suspend fun loudness(trackId: String): TrackLoudness?

    @Upsert
    suspend fun saveLoudness(row: TrackLoudness)
}
