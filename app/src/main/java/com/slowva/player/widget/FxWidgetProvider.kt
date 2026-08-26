package com.slowva.player.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.slowva.player.player.PlayerHub

class FxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        SlowvaWidgets.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        PlayerHub.init(context)
        when (intent.action) {
            ACTION_SLOW_DOWN -> PlayerHub.setRate((PlayerHub.rate.value - 0.05f).coerceAtLeast(0.5f))
            ACTION_SLOW_UP -> PlayerHub.setRate((PlayerHub.rate.value + 0.05f).coerceAtMost(1.25f))
            ACTION_REVERB_DOWN -> PlayerHub.setReverb((PlayerHub.reverb.value - 0.05f).coerceAtLeast(0f))
            ACTION_REVERB_UP -> PlayerHub.setReverb((PlayerHub.reverb.value + 0.05f).coerceAtMost(1f))
            else -> return
        }
        SlowvaWidgets.updateFx(context)
    }

    companion object {
        const val ACTION_SLOW_DOWN = "com.slowva.player.widget.SLOW_DOWN"
        const val ACTION_SLOW_UP = "com.slowva.player.widget.SLOW_UP"
        const val ACTION_REVERB_DOWN = "com.slowva.player.widget.REVERB_DOWN"
        const val ACTION_REVERB_UP = "com.slowva.player.widget.REVERB_UP"
    }
}
