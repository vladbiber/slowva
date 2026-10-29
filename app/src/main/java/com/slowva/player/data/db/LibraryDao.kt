package com.slowva.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM tracks WHERE source = :source ORDER BY addedAt")
    suspend fun tracksBySource(source: TrackSource): List<TrackEntity>

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: String)

    @Query("SELECT trackId FROM favorites ORDER BY addedAt")
    suspend fun favoriteIds(): List<String>

    @Upsert
    suspend fun upsertFavorites(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun deleteFavorite(trackId: String)

    @Query("SELECT * FROM playlists ORDER BY position")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE name = :name")
    suspend fun playlistByName(name: String): PlaylistEntity?

    @Query("SELECT trackId FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun playlistTrackIds(playlistId: Long): List<String>

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE name = :name")
    suspend fun deletePlaylist(name: String)

    @Query("UPDATE playlists SET name = :newName WHERE name = :oldName")
    suspend fun renamePlaylist(oldName: String, newName: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearPlaylistItems(playlistId: Long)

    @Upsert
    suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

    /** Writes a playlist and its full track order, creating it if the name is new. */
    @Transaction
    suspend fun savePlaylist(name: String, coverUri: String?, position: Int, trackIds: List<String>) {
        val existing = playlistByName(name)
        val id = if (existing == null) {
            upsertPlaylist(PlaylistEntity(name = name, coverUri = coverUri, position = position))
        } else {
            upsertPlaylist(existing.copy(coverUri = coverUri, position = position))
            existing.id
        }
        clearPlaylistItems(id)
        insertPlaylistItems(trackIds.mapIndexed { i, t -> PlaylistItemEntity(id, i, t) })
    }

    @Query("SELECT * FROM playback_snapshot WHERE id = 0")
    suspend fun snapshot(): PlaybackSnapshotEntity?

    @Upsert
    suspend fun saveSnapshot(snapshot: PlaybackSnapshotEntity)

    @Insert
    suspend fun insertPlay(play: PlayEntity)

    @Query("DELETE FROM plays WHERE id NOT IN (SELECT id FROM plays ORDER BY id DESC LIMIT :keep)")
    suspend fun prunePlays(keep: Int)

    @Query("SELECT * FROM plays WHERE trackId IN (:trackIds)")
    suspend fun plays(trackIds: List<String>): List<PlayEntity>

    @Insert
    suspend fun insertPlays(plays: List<PlayEntity>)

    @Query("DELETE FROM plays WHERE trackId IN (:trackIds)")
    suspend fun deletePlays(trackIds: List<String>)

    @Query("DELETE FROM plays")
    suspend fun clearPlays()

    @Query("SELECT EXISTS(SELECT 1 FROM plays WHERE trackId IN (:trackIds))")
    suspend fun hasPlays(trackIds: List<String>): Boolean

    /** The latest play of each track, newest first. */
    @Query("SELECT * FROM plays WHERE id IN (SELECT MAX(id) FROM plays GROUP BY trackId) ORDER BY id DESC LIMIT :limit")
    fun recentPlays(limit: Int): Flow<List<PlayEntity>>

    /** Tracks played at least twice, most plays first; one play each would just repeat the recent row. */
    @Query(
        "SELECT * FROM plays WHERE id IN (SELECT MAX(id) FROM plays GROUP BY trackId HAVING COUNT(*) >= 2) " +
            "ORDER BY (SELECT COUNT(*) FROM plays p WHERE p.trackId = plays.trackId) DESC, id DESC LIMIT :limit"
    )
    fun mostPlays(limit: Int): Flow<List<PlayEntity>>
}
