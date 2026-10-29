package com.slowva.player.widget

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Draws a composable into a bitmap: a private virtual display shows it for a moment and its frame is read back.
 * This lets the home screen widgets show the very same skins as the player, without a second drawing of each.
 */
object Snapshot {
    private val lock = Mutex()

    /** Call on the main thread; [dpi] sets how many pixels a dp gets. Returns null if the frame never arrived. */
    suspend fun render(context: Context, width: Int, height: Int, dpi: Int, content: @Composable () -> Unit): Bitmap? = lock.withLock {
        val app = context.applicationContext
        val dm = app.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = dm.createVirtualDisplay(
            "slowva-widget", width, height, dpi, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val owner = Owner()
        var shown: Presentation? = null
        try {
            val p = Presentation(app, display.display)
            p.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            p.window?.setFormat(PixelFormat.TRANSLUCENT)
            val view = ComposeView(p.context).apply { setContent(content) }
            p.window?.decorView?.let { it.setViewTreeLifecycleOwner(owner); it.setViewTreeSavedStateRegistryOwner(owner) }
            view.setViewTreeLifecycleOwner(owner)
            view.setViewTreeSavedStateRegistryOwner(owner)
            p.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            p.show()
            shown = p
            // a few frames for layout, the first draw and fonts; the newest frame wins
            delay(450)
            val image = reader.acquireLatestImage() ?: run { delay(400); reader.acquireLatestImage() } ?: return@withLock null
            image.use {
                val plane = it.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                if (rowPixels == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            }
        } catch (e: Exception) {
            android.util.Log.w("SlowvaWidget", "snapshot failed", e)
            null
        } finally {
            runCatching { shown?.dismiss() }
            owner.destroy()
            // the display goes after the dismiss has run
            Handler(Looper.getMainLooper()).post { display.release(); reader.close() }
        }
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
