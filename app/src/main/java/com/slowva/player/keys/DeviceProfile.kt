package com.slowva.player.keys

import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Screen and keyboard shape the layouts can adapt to. AUTO is only a setting, never the active profile. */
enum class DeviceProfile(val label: String, val isSquare: Boolean, val isKeyboardPhone: Boolean) {
    AUTO("Automatic", false, false),
    OFF("Off", false, false),
    TITAN("Titan", true, true),
    TITAN_POCKET("Titan Pocket", true, true),
    TITAN_SLIM("Titan Slim", false, true),
    TITAN_2("Titan 2", true, true),
    TITAN_2_ELITE("Titan 2 Elite", true, true),
    GENERIC_SQUARE("Square screen", true, false);

    companion object {
        /** Unihertz Titan models by name, else a near-square screen, else OFF. */
        fun detect(manufacturer: String?, model: String?, widthPx: Int, heightPx: Int): DeviceProfile {
            val mf = manufacturer.orEmpty()
            val m = model.orEmpty().lowercase()
            if (mf.contains("unihertz", ignoreCase = true) && "titan" in m) {
                return when {
                    "pocket" in m -> TITAN_POCKET
                    "slim" in m -> TITAN_SLIM
                    "elite" in m -> TITAN_2_ELITE
                    Regex("titan\\s*2").containsMatchIn(m) -> TITAN_2
                    else -> TITAN
                }
            }
            if (widthPx <= 0 || heightPx <= 0) return OFF
            val r = widthPx.toFloat() / heightPx
            return if (r in 0.85f..1.18f) GENERIC_SQUARE else OFF
        }
    }
}

/** The active profile (never AUTO) for layouts; provided at the activity root. */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile.OFF }

/** The chosen profile, what AUTO found, and the active result. */
object DeviceProfiles {
    private val _selected = MutableStateFlow(DeviceProfile.AUTO)
    val selected: StateFlow<DeviceProfile> = _selected
    private val _active = MutableStateFlow(DeviceProfile.OFF)
    val active: StateFlow<DeviceProfile> = _active
    var detected = DeviceProfile.OFF
        private set
    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val dm = context.resources.displayMetrics
        detected = DeviceProfile.detect(Build.MANUFACTURER, Build.MODEL, dm.widthPixels, dm.heightPixels)
        val saved = prefs()?.getString(KEY, null)
        _selected.value = DeviceProfile.entries.firstOrNull { it.name == saved } ?: DeviceProfile.AUTO
        _active.value = resolve(_selected.value)
    }

    fun select(p: DeviceProfile) {
        _selected.value = p
        _active.value = resolve(p)
        prefs()?.edit()?.putString(KEY, p.name)?.apply()
    }

    private fun resolve(p: DeviceProfile) = if (p == DeviceProfile.AUTO) detected else p

    private fun prefs() = app?.getSharedPreferences(KeyBindings.PREFS, Context.MODE_PRIVATE)

    private const val KEY = "device_profile"
}
