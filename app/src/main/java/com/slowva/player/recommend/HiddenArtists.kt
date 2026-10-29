package com.slowva.player.recommend

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Artists the listener hid from recommendations, kept as typed; matched by [RadioMixer.artistKey]. */
object HiddenArtists {
    private const val KEY = "names"
    private var prefs: SharedPreferences? = null
    private val _names = MutableStateFlow<Set<String>>(emptySet())
    val names: StateFlow<Set<String>> = _names.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hidden_artists", Context.MODE_PRIVATE)
        prefs = p
        _names.value = p.getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    fun hide(name: String) {
        if (_names.value.none { RadioMixer.artistKey(it) == RadioMixer.artistKey(name) }) save(_names.value + name)
    }

    fun unhide(name: String) = save(_names.value.filterNot { RadioMixer.artistKey(it) == RadioMixer.artistKey(name) }.toSet())

    private fun save(names: Set<String>) {
        _names.value = names
        prefs?.edit()?.putStringSet(KEY, names)?.apply()
    }

    /** [list] without hidden artists; callers cut to size after this, so the next candidates fill the gaps. */
    fun <T> filter(list: List<T>, hidden: Set<String>, artist: (T) -> String): List<T> {
        if (hidden.isEmpty()) return list
        val keys = hidden.mapTo(HashSet(), RadioMixer::artistKey)
        return list.filter { RadioMixer.artistKey(artist(it)) !in keys }
    }
}
