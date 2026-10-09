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

/** The Orca+ list row: the settings row ([SettingsRow]), so Orca+'s settings and Wholphin's look alike. */
@Composable
internal fun PlusListItem(
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = SettingsRow(
    onClick = onClick,
    headlineContent = headlineContent,
    modifier = modifier,
    supportingContent = supportingContent,
    trailingContent = trailingContent,
)

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
