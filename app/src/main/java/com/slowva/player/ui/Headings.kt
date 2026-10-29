package com.slowva.player.ui

import com.slowva.player.ui.mood.moodText
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slowva.player.ui.theme.TextPrimary

/** The big title at the top of a tab or full-screen page. */
@Composable
internal fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        moodText(text), color = TextPrimary, style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(vertical = 6.dp).semantics { heading() },
    )
}

/** A heading above a group of rows, chips or cards; callers pass the spacing their list needs. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        moodText(text), color = TextPrimary, style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** The one tab style of the app: a scrolling row of pills, the chosen one filled with the accent. */
@Composable
internal fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label -> PillTab(label, i == selected) { onSelect(i) } }
        // keeps the last pill clear of the screen edge when the row scrolls
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun PillTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            moodText(label),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else TextPrimary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The same top bar on every tab: big title on the left, the tab's buttons on the right. */
@Composable
internal fun TabTopBar(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ScreenTitle(title, Modifier.weight(1f))
        actions()
    }
}
