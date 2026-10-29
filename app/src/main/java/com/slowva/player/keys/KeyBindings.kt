package com.slowva.player.keys

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A key plus the modifiers held with it; [meta] keeps only [KeyCombo.MASK] bits. */
data class KeyCombo(val code: Int, val meta: Int = 0) {
    companion object {
        const val MASK = KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON or
            KeyEvent.META_FUNCTION_ON or KeyEvent.META_SYM_ON

        fun of(code: Int, metaState: Int) = KeyCombo(code, metaState and MASK)

        /** keys that only modify others; never bound on their own */
        fun isModifier(code: Int) = code in MODIFIERS

        private val MODIFIERS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK,
        )
    }
}

/** Immutable key to action table; all edits return a new map. */
data class KeyMap(val map: Map<KeyCombo, KeyAction>) {

    /** Exact match first; keys other than letters also match with modifiers ignored (Alt digits, Shift slash). */
    fun lookup(combo: KeyCombo): KeyAction? =
        map[combo] ?: if (combo.meta != 0 && !isLetter(combo.code)) map[KeyCombo(combo.code)] else null

    fun keysFor(action: KeyAction): List<KeyCombo> = map.filterValues { it == action }.keys.sortedBy { it.code * 64 + it.meta }

    /** the other action already on [combo], if any */
    fun conflict(action: KeyAction, combo: KeyCombo): KeyAction? = map[combo]?.takeIf { it != action }

    /** Puts [action] on [combo] alone. With [swap] the action that held [combo] takes over [action]'s old keys. */
    fun bind(action: KeyAction, combo: KeyCombo, swap: Boolean = true): KeyMap {
        val old = keysFor(action)
        val other = conflict(action, combo)
        val m = map.filterValues { it != action }.toMutableMap()
        m[combo] = action
        if (swap && other != null) old.filter { it != combo }.forEach { m[it] = other }
        return KeyMap(m)
    }

    fun unbind(action: KeyAction) = KeyMap(map.filterValues { it != action })

    /** back to the default keys for [action], skipping any another action now uses */
    fun reset(action: KeyAction): KeyMap {
        val m = map.filterValues { it != action }.toMutableMap()
        DEFAULT.map.forEach { (c, a) -> if (a == action && c !in m) m[c] = a }
        return KeyMap(m)
    }

    fun encode(): String = map.entries.joinToString(";") { (c, a) -> "${c.code}:${c.meta}=${a.name}" }

    companion object {
        val DEFAULT = KeyMap(buildMap {
            fun put(code: Int, a: KeyAction) = put(KeyCombo(code), a)
            put(KeyEvent.KEYCODE_SPACE, KeyAction.PLAY_PAUSE)
            put(KeyEvent.KEYCODE_K, KeyAction.PLAY_PAUSE)
            put(KeyEvent.KEYCODE_N, KeyAction.NEXT)
            put(KeyEvent.KEYCODE_P, KeyAction.PREVIOUS)
            put(KeyEvent.KEYCODE_L, KeyAction.SEEK_FORWARD)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, KeyAction.SEEK_FORWARD)
            put(KeyEvent.KEYCODE_J, KeyAction.SEEK_BACK)
            put(KeyEvent.KEYCODE_DPAD_LEFT, KeyAction.SEEK_BACK)
            put(KeyEvent.KEYCODE_U, KeyAction.VOLUME_UP)
            put(KeyEvent.KEYCODE_D, KeyAction.VOLUME_DOWN)
            put(KeyEvent.KEYCODE_F, KeyAction.LIKE)
            put(KeyEvent.KEYCODE_A, KeyAction.ADD_TO_PLAYLIST)
            put(KeyEvent.KEYCODE_S, KeyAction.SEARCH)
            put(KeyEvent.KEYCODE_SLASH, KeyAction.SEARCH)
            put(KeyEvent.KEYCODE_Q, KeyAction.QUEUE)
            put(KeyEvent.KEYCODE_O, KeyAction.OPEN_PLAYER)
            put(KeyEvent.KEYCODE_ESCAPE, KeyAction.CLOSE)
            put(KeyEvent.KEYCODE_1, KeyAction.TAB_1)
            put(KeyEvent.KEYCODE_2, KeyAction.TAB_2)
            put(KeyEvent.KEYCODE_3, KeyAction.TAB_3)
            put(KeyEvent.KEYCODE_4, KeyAction.TAB_4)
        })

        /** Bad entries are dropped; null (never saved) means defaults. */
        fun decode(s: String?): KeyMap {
            if (s == null) return DEFAULT
            val m = HashMap<KeyCombo, KeyAction>()
            s.split(';').forEach { e ->
                val (combo, name) = e.split('=').takeIf { it.size == 2 } ?: return@forEach
                val (code, meta) = combo.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@forEach
                val a = KeyAction.entries.firstOrNull { it.name == name } ?: return@forEach
                m[KeyCombo(code, meta and KeyCombo.MASK)] = a
            }
            return KeyMap(m)
        }

        private fun isLetter(code: Int) = code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
    }
}

/** Human name for a key: "Shift+Space", "Alt+1", "Key 289" for codes Android doesn't name. */
fun keyLabel(combo: KeyCombo, rawName: String): String {
    val mods = buildList {
        if (combo.meta and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
        if (combo.meta and KeyEvent.META_ALT_ON != 0) add("Alt")
        if (combo.meta and KeyEvent.META_SHIFT_ON != 0) add("Shift")
        if (combo.meta and KeyEvent.META_FUNCTION_ON != 0) add("Fn")
        if (combo.meta and KeyEvent.META_SYM_ON != 0) add("Sym")
    }
    return (mods + cleanKeyName(combo.code, rawName)).joinToString("+")
}

/** [raw] is KeyEvent.keyCodeToString(code). */
fun cleanKeyName(code: Int, raw: String): String = when (code) {
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    KeyEvent.KEYCODE_DPAD_UP -> "Up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
    KeyEvent.KEYCODE_SLASH -> "/"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    else -> {
        val s = raw.removePrefix("KEYCODE_")
        when {
            s.isEmpty() || s.all { it.isDigit() } && s.length > 1 -> "Key $code"
            s.length == 1 -> s
            else -> s.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/** The live bindings, saved in SharedPreferences. */
object KeyBindings {
    private val _map = MutableStateFlow(KeyMap.DEFAULT)
    val map: StateFlow<KeyMap> = _map
    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        _map.value = KeyMap.decode(prefs()?.getString(KEY, null))
    }

    fun bind(action: KeyAction, combo: KeyCombo, swap: Boolean = true) = set(_map.value.bind(action, combo, swap))
    fun reset(action: KeyAction) = set(_map.value.reset(action))
    fun resetAll() {
        _map.value = KeyMap.DEFAULT
        prefs()?.edit()?.remove(KEY)?.apply()
    }

    private fun set(m: KeyMap) {
        _map.value = m
        prefs()?.edit()?.putString(KEY, m.encode())?.apply()
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal const val PREFS = "slowva-keys"
    private const val KEY = "bindings"
}
