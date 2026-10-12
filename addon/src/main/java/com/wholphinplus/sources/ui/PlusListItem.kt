package com.wholphinplus.sources.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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

/** Switch colours for Orca+ settings rows: the app's own switch colour. */
@Composable
internal fun plusSwitchColors(): androidx.tv.material3.SwitchColors =
    androidx.tv.material3.SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = com.wholphinplus.sources.cinema.CinemaColors.toggle,
        checkedBorderColor = com.wholphinplus.sources.cinema.CinemaColors.toggle,
    )
