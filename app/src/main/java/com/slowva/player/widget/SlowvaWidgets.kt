package com.slowva.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import com.slowva.player.MainActivity
import com.slowva.player.R
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.PlayerService
import kotlin.math.roundToInt

/** Rendering and click wiring shared by both Android home widgets. */
object SlowvaWidgets {

    fun updateAll(context: Context) {
        PlayerHub.init(context)
        updateCava(context, PlayerHub.local.spectrum.value)
        updateFx(context)
    }

    fun updateCava(context: Context, bars: FloatArray) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CavaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = PlayerHub.playback.value
        val views = RemoteViews(context.packageName, R.layout.widget_cava).apply {
            setImageViewBitmap(R.id.widget_cava_image, renderBars(bars))
            setTextViewText(R.id.widget_cava_title, state?.track?.name ?: "slowva CAVA")
            setTextViewText(R.id.widget_cava_artist, state?.track?.artist ?: "Connect this phone to begin")
            setTextViewText(R.id.widget_cava_play, if (state?.isPlaying == true) "Ⅱ" else "▶")
            setOnClickPendingIntent(R.id.widget_cava_root, openApp(context))
            setOnClickPendingIntent(
                R.id.widget_cava_previous,
                playerAction(context, PlayerService.ACTION_PREV, 101),
            )
            setOnClickPendingIntent(
                R.id.widget_cava_play,
                playerAction(context, PlayerService.ACTION_TOGGLE, 102),
            )
            setOnClickPendingIntent(
                R.id.widget_cava_next,
                playerAction(context, PlayerService.ACTION_NEXT, 103),
            )
        }
        manager.updateAppWidget(ids, views)
    }

    fun updateFx(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FxWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val rate = PlayerHub.rate.value
        val reverb = PlayerHub.reverb.value
        val state = PlayerHub.playback.value
        val local = PlayerHub.localDeviceId.value
        val onPhone = local != null && state?.deviceId == local
        val views = RemoteViews(context.packageName, R.layout.widget_fx).apply {
            setTextViewText(R.id.widget_fx_track, state?.track?.name ?: "slowva")
            setTextViewText(R.id.widget_slow_value, "${"%.2f".format(rate)}×")
            setProgressBar(
                R.id.widget_slow_progress,
                75,
                ((rate.coerceIn(0.5f, 1.25f) - 0.5f) * 100f).roundToInt(),
                false,
            )
            setTextViewText(R.id.widget_reverb_value, "${(reverb * 100).roundToInt()}%")
            setProgressBar(
                R.id.widget_reverb_progress,
                100,
                (reverb.coerceIn(0f, 1f) * 100).roundToInt(),
                false,
            )
            setTextViewText(
                R.id.widget_connect,
                when {
                    PlayerHub.connecting.value -> "CONNECTING…"
                    onPhone -> "✓ PLAYING ON THIS PHONE"
                    else -> "CONNECT TO THIS PHONE"
                },
            )
            setOnClickPendingIntent(R.id.widget_fx_root, openApp(context))
            setOnClickPendingIntent(R.id.widget_slow_down, widgetAction(context, FxWidgetProvider.ACTION_SLOW_DOWN, 201))
            setOnClickPendingIntent(R.id.widget_slow_up, widgetAction(context, FxWidgetProvider.ACTION_SLOW_UP, 202))
            setOnClickPendingIntent(R.id.widget_reverb_down, widgetAction(context, FxWidgetProvider.ACTION_REVERB_DOWN, 203))
            setOnClickPendingIntent(R.id.widget_reverb_up, widgetAction(context, FxWidgetProvider.ACTION_REVERB_UP, 204))
            setOnClickPendingIntent(
                R.id.widget_connect,
                playerAction(context, PlayerService.ACTION_CONNECT, 205),
            )
        }
        manager.updateAppWidget(ids, views)
    }

    private fun renderBars(bars: FloatArray): Bitmap {
        // Stay comfortably below Binder's 1 MiB transaction ceiling even on
        // launchers that copy the bitmap instead of sharing its pixel memory.
        val width = 560
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(Color.rgb(22, 16, 31), Color.rgb(13, 13, 18)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 34f, 34f, background)

        val values = if (bars.isEmpty()) FloatArray(24) else bars
        val gap = 8f
        val barWidth = (width - gap * (values.size + 1)) / values.size
        val baseline = height - 38f
        val maximum = height * 0.64f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                baseline - maximum,
                0f,
                baseline,
                Color.rgb(218, 174, 255),
                Color.rgb(122, 80, 167),
                Shader.TileMode.CLAMP,
            )
        }
        values.forEachIndexed { index, raw ->
            val barHeight = (raw.coerceIn(0.03f, 1f) * maximum).coerceAtLeast(8f)
            val left = gap + index * (barWidth + gap)
            canvas.drawRoundRect(
                RectF(left, baseline - barHeight, left + barWidth, baseline),
                barWidth / 2,
                barWidth / 2,
                paint,
            )
        }
        return bitmap
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        100,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun playerAction(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, request, intent, flags)
        } else {
            PendingIntent.getService(context, request, intent, flags)
        }
    }

    private fun widgetAction(context: Context, action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, FxWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
