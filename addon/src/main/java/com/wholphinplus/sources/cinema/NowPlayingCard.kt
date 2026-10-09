package com.wholphinplus.sources.cinema

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.wholphinplus.sources.NowPlaying
import com.wholphinplus.sources.PickSession
import com.wholphinplus.sources.PlayInfo
import com.wholphinplus.sources.PlayInfoPrefs
import com.wholphinplus.sources.PlayedFormats
import com.wholphinplus.sources.copyKey
import com.wholphinplus.sources.ui.sourceHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private val DirectInk = Color(0xFF7CE0B4)
private val TranscodeInk = Color(0xFFFFC069)
private val CardShape = RoundedCornerShape(16.dp)

/** How long the card stays up once playback has started. */
private const val SHOWN_MS = 6_000L

/**
 * A moment after a title starts playing (and again when it switches copy or method), a small
 * card in the top right says how it plays: direct play or transcode, the picture, the sound and
 * the server. Settings → Playback switches it, or any of its parts, off.
 */
@Composable
fun NowPlayingCard(
    session: PickSession,
    player: Player,
    itemId: UUID?,
    playMethod: String?,
    modifier: Modifier = Modifier,
) {
    val hook = androidx.compose.ui.platform.LocalContext.current.sourceHook()
    val prefs by hook.store.playInfoPrefs.collectAsState()
    if (!prefs.on || itemId == null || playMethod == null) return
    if (!(prefs.method || prefs.quality || prefs.audio || prefs.server)) return
    val (copy, isMain) = remember(itemId, playMethod) { session.playingCopy(itemId) }
    var info by remember { mutableStateOf<PlayInfo?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, copy?.let(::copyKey), playMethod) {
        visible = false
        // Once the picture is really moving: playing, with a decoded frame (a start can buffer a while)
        withTimeoutOrNull(60_000L) { while (!player.isPlaying || player.videoSize.width <= 0) delay(250) } ?: return@LaunchedEffect
        delay(700)
        info = NowPlaying.info(playMethod, copy, isMain, player.playedFormats())
        visible = true
        delay(SHOWN_MS)
        visible = false
    }
    val shown = info ?: return
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(420, easing = CinemaEase)) + slideInHorizontally(tween(560, easing = CinemaEase)) { it / 3 },
        exit = fadeOut(tween(520, easing = CinemaFade)) + slideOutHorizontally(tween(520, easing = CinemaFade)) { it / 4 },
        modifier = modifier,
    ) {
        PlayInfoCard(shown, prefs)
    }
}

@Composable
private fun PlayInfoCard(
    i: PlayInfo,
    prefs: PlayInfoPrefs,
) {
    Column(
        Modifier
            .widthIn(min = 300.dp, max = 440.dp)
            .width(IntrinsicSize.Max)
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Color(0xF019191E), Color(0xF00D0D10))))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CardShape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("+", color = Plus, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(5.dp))
            Text("NOW PLAYING", color = InkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
            if (prefs.method && i.method.isNotEmpty()) {
                Spacer(Modifier.width(18.dp))
                Spacer(Modifier.weight(1f))
                MethodPill(i)
            }
        }
        if (prefs.quality && i.quality.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(i.quality, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, maxLines = 1)
                if (i.hdr.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        i.hdr.uppercase(),
                        color = Ink.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (i.videoCodec.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(i.videoCodec, color = InkDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
        if (prefs.audio && i.audio.isNotBlank()) {
            Spacer(Modifier.height(if (prefs.quality) 2.dp else 12.dp))
            Text(i.audio, color = Ink.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (prefs.server && i.server.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Plus))
                Spacer(Modifier.width(9.dp))
                Text(
                    "From ${i.server}",
                    color = InkDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "DIRECT PLAY" in green, "TRANSCODE" in amber: the one fact most people look for. */
@Composable
private fun MethodPill(i: PlayInfo) {
    val ink = if (i.transcoding) TranscodeInk else DirectInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(ink))
        Spacer(Modifier.width(6.dp))
        Text(i.method.uppercase(), color = ink, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
    }
}

/** The video and audio the player chose, as the card needs them. */
@OptIn(UnstableApi::class)
internal fun Player.playedFormats(): PlayedFormats {
    var video: Format? = null
    var audio: Format? = null
    currentTracks.groups.forEach { g ->
        if (!g.isSelected) return@forEach
        for (t in 0 until g.length) {
            if (!g.isTrackSelected(t)) continue
            when (g.type) {
                C.TRACK_TYPE_VIDEO -> if (video == null) video = g.getTrackFormat(t)
                C.TRACK_TYPE_AUDIO -> if (audio == null) audio = g.getTrackFormat(t)
            }
        }
    }
    return PlayedFormats(
        width = video?.width?.coerceAtLeast(0) ?: 0,
        height = video?.height?.coerceAtLeast(0) ?: 0,
        videoMime = video?.sampleMimeType.orEmpty(),
        hdrTransfer = video?.colorInfo?.colorTransfer ?: -1,
        audioMime = audio?.sampleMimeType.orEmpty(),
        audioChannels = audio?.channelCount?.coerceAtLeast(0) ?: 0,
    )
}
