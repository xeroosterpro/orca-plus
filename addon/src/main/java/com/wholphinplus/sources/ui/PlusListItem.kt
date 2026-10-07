package com.wholphinplus.sources.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme

/** The Orca+ list row: dark focus with an accent outline (no white fill), used everywhere. */
@Composable
internal fun PlusListItem(
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    ListItem(
        selected = false,
        onClick = onClick,
        shape = ListItemDefaults.shape(shape),
        colors =
            ListItemDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.04f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = if (com.wholphinplus.sources.cinema.cinemaModeOn()) Color(0xFF2A2A2A) else Color(0xFF1E1E2A),
                focusedContentColor = Color.White,
            ),
        border = ListItemDefaults.border(focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = shape)),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        modifier = modifier,
    )
}

/** Switch colours for Orca+ settings rows: the red track the app's own switches use with the Cinema home (Classic: the default). */
@Composable
internal fun plusSwitchColors(): androidx.tv.material3.SwitchColors =
    if (com.wholphinplus.sources.cinema.cinemaModeOn()) {
        androidx.tv.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = com.wholphinplus.sources.cinema.CinemaColors.toggle,
            checkedBorderColor = com.wholphinplus.sources.cinema.CinemaColors.toggle,
        )
    } else {
        androidx.tv.material3.SwitchDefaults.colors()
    }
