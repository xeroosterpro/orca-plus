package com.wholphinplus.sources.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wholphinplus.sources.PickSession

/** Shown by Wholphin's PlaybackPage while [PickSession.pick] waits for a choice. */
@Composable
fun SourcePickerOverlay(session: PickSession) {
    val ui by session.ui.collectAsState()
    val current = ui ?: return
    // One look: the picker in Cinema's style (the classic Material dialog left with the classic home)
    com.wholphinplus.sources.cinema.CinemaSourcePicker(current)
}

