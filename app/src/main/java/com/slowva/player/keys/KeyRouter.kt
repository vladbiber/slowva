package com.slowva.player.keys

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.slowva.player.SlowvaApp
import com.slowva.player.player.PlayerHub
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Turns hardware key presses into [KeyAction]s; called from MainActivity.dispatchKeyEvent. */
object KeyRouter {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** UI actions for the screens to handle; keys pass through while nothing collects. */
    private val _uiActions = MutableSharedFlow<KeyAction>(extraBufferCapacity = 8)
    val uiActions: SharedFlow<KeyAction> = _uiActions

    /** true while a [typingField] has focus */
    val textFocused: Boolean get() = typing.isNotEmpty()
    private val typing = HashSet<Any>()

    /** set by the binding capture dialog so its keys are not acted on */
    @Volatile var capturing = false

    // down keys we consumed, so their up events are swallowed too
    private val consumed = HashSet<Int>()
    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        KeyBindings.init(context)
        DeviceProfiles.init(context)
        val p = prefs()
        _enabled.value = if (p != null && p.contains(KEY_ON)) p.getBoolean(KEY_ON, true) else defaultOn(context)
    }

    /** on when a hardware keyboard is attached or a keyboard phone is detected */
    fun defaultOn(context: Context) =
        context.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY || DeviceProfiles.detected.isKeyboardPhone

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs()?.edit()?.putBoolean(KEY_ON, on)?.apply()
    }

    internal fun setTyping(token: Any, focused: Boolean) {
        if (focused) typing.add(token) else typing.remove(token)
    }

    /** true when the event was used and must not reach the views */
    fun handle(activity: Activity, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return consumed.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!_enabled.value || capturing || textFocused || KeyCombo.isModifier(event.keyCode)) return false
        val action = KeyBindings.map.value.lookup(KeyCombo.of(event.keyCode, event.metaState)) ?: return false
        if (event.repeatCount > 0 && !action.repeats) return consumed.contains(event.keyCode)
        val done = run(activity, action)
        if (done) consumed.add(event.keyCode)
        return done
    }

    private fun run(activity: Activity, action: KeyAction): Boolean {
        when (action) {
            KeyAction.PLAY_PAUSE -> PlayerHub.togglePlay()
            KeyAction.NEXT -> PlayerHub.next()
            KeyAction.PREVIOUS -> PlayerHub.previous()
            KeyAction.SEEK_FORWARD -> seekBy(SEEK_MS)
            KeyAction.SEEK_BACK -> seekBy(-SEEK_MS)
            KeyAction.VOLUME_UP -> volume(activity, AudioManager.ADJUST_RAISE)
            KeyAction.VOLUME_DOWN -> volume(activity, AudioManager.ADJUST_LOWER)
            KeyAction.LIKE -> like(activity)
            // back runs through the dispatcher so every BackHandler keeps working
            KeyAction.CLOSE -> (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: return false
            else -> {
                if (_uiActions.subscriptionCount.value == 0) return false
                return _uiActions.tryEmit(action)
            }
        }
        return true
    }

    private fun seekBy(deltaMs: Long) {
        val c = PlayerHub.remote ?: return
        val dur = c.duration
        val raw = c.currentPosition.coerceAtLeast(0L)
        // reverse mode plays the file backwards; work in the shown position
        val pos = if (PlayerHub.reverseOn.value && dur > 0) (dur - raw).coerceAtLeast(0L) else raw
        var target = (pos + deltaMs).coerceAtLeast(0L)
        if (dur > 0) target = target.coerceAtMost(dur - 500).coerceAtLeast(0L)
        PlayerHub.seek(target.toInt())
    }

    private fun volume(context: Context, dir: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun like(context: Context) {
        val id = PlayerHub.ui.value.currentTrackId ?: return
        (context.applicationContext as SlowvaApp).repository.toggleFavorite(id)
    }

    private fun prefs() = app?.getSharedPreferences(KeyBindings.PREFS, Context.MODE_PRIVATE)

    private const val KEY_ON = "enabled"
    private const val SEEK_MS = 10_000L
}
