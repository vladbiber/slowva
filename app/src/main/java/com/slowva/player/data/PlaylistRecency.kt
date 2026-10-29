package com.slowva.player.data

import android.content.Context
import android.content.SharedPreferences

/** When each playlist last got songs, so pickers list the ones in use first. */
object PlaylistRecency {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("playlist_recency", Context.MODE_PRIVATE)
    }

    fun touch(name: String) {
        prefs?.edit()?.putLong(name, System.currentTimeMillis())?.apply()
    }

    fun rename(old: String, new: String) {
        val p = prefs ?: return
        val at = p.getLong(old, 0L)
        p.edit().remove(old).apply { if (at > 0) putLong(new, at) }.apply()
    }

    /** Most recently used first; playlists never added to keep their own order after those. */
    fun order(playlists: List<Playlist>): List<Playlist> {
        val p = prefs ?: return playlists
        return playlists.withIndex().sortedWith(compareByDescending<IndexedValue<Playlist>> { p.getLong(it.value.name, 0L) }.thenBy { it.index }).map { it.value }
    }
}
