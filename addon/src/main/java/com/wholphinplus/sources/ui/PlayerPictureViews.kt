package com.wholphinplus.sources.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.wholphinplus.sources.PlayerPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A chapter card's picture borrowed from another copy (see [PlayerPictures]), only when the server
 * has none of its own ([ownTag] null). Null: Wholphin's own picture.
 */
@Composable
fun rememberBorrowedChapter(
    itemId: UUID,
    startMs: Long,
    ownTag: String?,
): String? {
    val borrowed by PlayerPictures.state.collectAsState()
    if (ownTag != null) return null
    return remember(borrowed, itemId, startMs) { PlayerPictures.chapterImage(borrowed, itemId, startMs) }
}

/**
 * The seek preview from a borrowed thumbnail track, where the server has no trickplay: the same
 * size and frame as Wholphin's tiles. Keeps the last picture up while the next one decodes.
 */
@Composable
fun BorrowedThumbnail(
    seekProgressMs: Long,
    modifier: Modifier = Modifier,
) {
    val borrowed by PlayerPictures.state.collectAsState()
    val playing by PlayerPictures.playing.collectAsState()
    // Only the title on screen's track (a late download for the title before must never show)
    val bif = borrowed?.takeIf { it.itemId == playing }?.thumbnails ?: return
    val index = bif.indexAt(seekProgressMs)
    var image by remember(bif) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bif, index) {
        withContext(Dispatchers.IO) {
            bif.picture(index)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        }?.let { image = it }
    }
    val shown = image ?: return
    Image(
        bitmap = shown,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            modifier
                .height(160.dp)
                .aspectRatio(shown.width.toFloat() / shown.height.coerceAtLeast(1))
                .border(1.5.dp, MaterialTheme.colorScheme.border)
                .background(Color.Black),
    )
}
