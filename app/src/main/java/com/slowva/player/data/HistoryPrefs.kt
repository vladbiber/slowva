package com.slowva.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app remembers about your listening, and which history shelves Home shows. All on by default. */
object HistoryPrefs {
    enum class Key(val pref: String) {
        SHOW_RECENT("show_recent"), SHOW_MOST("show_most"),
        KEEP_PLAYS("keep_plays"), KEEP_SEARCHES("keep_searches"),
    }

    private var prefs: SharedPreferences? = null
    private val state = Key.entries.associateWith { MutableStateFlow(true) }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("history_prefs", Context.MODE_PRIVATE)
        prefs = p
        state.forEach { (k, f) -> f.value = p.getBoolean(k.pref, true) }
    }

    fun flow(key: Key): StateFlow<Boolean> = state.getValue(key).asStateFlow()

    operator fun get(key: Key): Boolean = state.getValue(key).value

    fun set(key: Key, on: Boolean) {
        state.getValue(key).value = on
        prefs?.edit()?.putBoolean(key.pref, on)?.apply()
    }
}
