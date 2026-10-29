package com.slowva.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Every schema change bumps [VERSION] and adds a real migration; schemas are exported to app/schemas. */
@Database(
    entities = [
        TrackEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        PlaybackSnapshotEntity::class,
        DiagnosticIssueEntity::class,
        DiagnosticEventEntity::class,
        SongGenreOverride::class,
        ArtistGenre::class,
        GenrePresetEntity::class,
        DevicePresetEntity::class,
        TrackLoudness::class,
        PlayEntity::class,
        RatingEntity::class,
        RatingTagEntity::class,
    ],
    version = SlowvaDatabase.VERSION,
    exportSchema = true,
)
abstract class SlowvaDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun diagnostics(): DiagnosticsDao
    abstract fun sound(): SoundDao
    abstract fun ratings(): RatingsDao

    companion object {
        const val NAME = "slowva.db"
        const val VERSION = 1

        fun open(context: Context): SlowvaDatabase =
            Room.databaseBuilder(context.applicationContext, SlowvaDatabase::class.java, NAME).build()
    }
}
