package com.slowva.player.keys

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/** Put on text fields: shortcuts pause while it has focus so letters type normally. */
fun Modifier.typingField(): Modifier = composed {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { KeyRouter.setTyping(token, false) } }
    onFocusChanged { KeyRouter.setTyping(token, it.isFocused) }
}

/** Focusable list row with an accent ring; Enter or D-pad center runs [onActivate], Shift+Enter [onSecondary]. */
fun Modifier.keyNavigable(onActivate: () -> Unit, onSecondary: (() -> Unit)? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
    onFocusChanged { focused = it.isFocused }
        .then(ring)
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (e.key != Key.Enter && e.key != Key.NumPadEnter && e.key != Key.DirectionCenter) return@onKeyEvent false
            if (e.isShiftPressed && onSecondary != null) onSecondary() else onActivate()
            true
        }
        .focusable()
}
