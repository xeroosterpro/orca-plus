package com.wholphinplus.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.wholphinplus.sources.PickSession
import com.wholphinplus.sources.core.ExternalSource
import kotlinx.coroutines.delay

/** Shown by Wholphin's PlaybackPage while [PickSession.pick] waits for a choice. */
@Composable
fun SourcePickerOverlay(session: PickSession) {
    val ui by session.ui.collectAsState()
    val current = ui ?: return
    // Cinema mode: the picker in Cinema's look (classic Wholphin keeps the Material dialog)
    if (com.wholphinplus.sources.cinema.cinemaModeOn()) {
        com.wholphinplus.sources.cinema.CinemaSourcePicker(current)
        return
    }
    val firstRow = remember(current.title) { FocusRequester() }
    LaunchedEffect(current.title, current.searching) {
        // Wait a frame for the list to lay out, then focus the best row (always the first).
        delay(50)
        runCatching { firstRow.requestFocus() }
    }
    Dialog(
        onDismissRequest = current.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)),
            modifier = Modifier.widthIn(min = 560.dp, max = 860.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (current.searching) {
                            "Searching ${current.serversTotal} servers… ${current.serversDone} done"
                        } else {
                            "${current.rows.size} sources · best first"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.heightIn(max = 520.dp),
                ) {
                    itemsIndexed(current.rows, key = { _, row -> row.connectionId + row.url }) { index, row ->
                        SourceRow(
                            row = row,
                            best = index == 0 && !current.searching && current.rows.size > 1,
                            onClick = { current.onSelect(row) },
                            modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceRow(
    row: ExternalSource,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dark focus with an accent outline instead of TV Material's white fill, so the white
    // badge images stay readable on the focused row.
    val accent = MaterialTheme.colorScheme.primary
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
        border =
            ListItemDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, accent), shape = shape),
            ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.serverLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (best) {
                    Text(
                        "★ BEST",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        modifier = Modifier.background(accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (row.compatible) Text("Compatible (server converts)", color = MaterialTheme.colorScheme.tertiary)
            }
        },
        supportingContent = {
            BadgeRow(
                badges = badgesFor(row),
                trailing = listOf(row.size, row.container),
                inverted = false,
                modifier = Modifier.padding(top = 6.dp),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
