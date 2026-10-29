package com.slowva.player

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.slowva.player.keys.DeviceProfiles
import com.slowva.player.keys.KeyRouter
import com.slowva.player.keys.LocalDeviceProfile
import com.slowva.player.player.PlayerViewModel
import com.slowva.player.ui.SlowvaRoot
import com.slowva.player.ui.theme.Appearance
import com.slowva.player.ui.theme.SlowvaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission result — playback works regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.init(this)
        com.slowva.player.player.fx.Moods.init(this)
        KeyRouter.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SlowvaTheme {
                // the app is always dark, whatever the system theme says
                LaunchedEffect(Unit) {
                    val style = SystemBarStyle.dark(Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                val profile by DeviceProfiles.active.collectAsState()
                CompositionLocalProvider(LocalDeviceProfile provides profile) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SlowvaRoot(viewModel)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.handle(this, event) || super.dispatchKeyEvent(event)
}
