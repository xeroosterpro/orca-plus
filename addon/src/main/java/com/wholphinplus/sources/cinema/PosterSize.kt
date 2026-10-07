package com.wholphinplus.sources.cinema

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onPlaced
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import timber.log.Timber
import java.util.Collections
import java.util.WeakHashMap

/**
 * How big card pictures are fetched (Settings → Home & Look → Poster size). Small is 300 px wide:
 * a card is drawn ~416 px, so it's a little softer, but some servers only offer 300 px or the
 * 1280 px original (TMDB's sizes behind a CDN), so asking 480 downloads ~9x the bytes. On a slow
 * connection that queues every card. Auto starts sharp and goes small once card pictures are seen
 * arriving slowly; the verdict is kept a day, then sharp is tried again.
 */
internal object PosterSize {
    const val AUTO = "auto"
    const val SHARP = "sharp"
    const val FAST = "fast"

    private const val SMALL = 300

    /** The setting; set by ConnectionStore. */
    var mode by mutableStateOf(AUTO)

    /** Auto's verdict: card pictures were arriving slowly. */
    var slow by mutableStateOf(false)
        private set

    /** When [slow] was decided (wall clock), for keeping it across starts. */
    var slowSince = 0L
        private set

    /** Auto just went small while browsing: Home says so once ([PosterNotice]). */
    var justSwitched by mutableStateOf(false)

    /** Saves the verdict; set by ConnectionStore. */
    var onVerdict: (Long) -> Unit = {}

    val small: Boolean get() = mode == FAST || (mode == AUTO && slow)

    /** Auto picked again in Settings: start sharp and measure afresh. */
    @Synchronized
    fun retest() {
        recent.clear()
        slow = false
        slowSince = 0L
        onVerdict(0L)
    }

    fun restore(since: Long) {
        if (since > 0 && System.currentTimeMillis() - since < KEEP_MS) {
            slow = true
            slowSince = since
        }
    }

    /** [url] at card size: the server's or TMDB's 300 px picture when [small]. */
    fun fit(url: String): String {
        if (!small) return url
        return if (url.contains("image.tmdb.org/t/p/w")) {
            TMDB.replace(url) { m -> if (m.groupValues[1].toInt() > SMALL) "/t/p/w$SMALL/" else m.value }
        } else {
            JELLYFIN.replace(url) { m -> if (m.groupValues[1].toInt() > SMALL) "maxWidth=$SMALL" else m.value }
        }
    }

    private val TMDB = Regex("/t/p/w(\\d+)/")
    private val JELLYFIN = Regex("maxWidth=(\\d+)")

    // ---- Auto: how long card pictures take from asked for to on screen (queue included)

    private const val KEEP_MS = 24 * 60 * 60_000L
    private const val SLOW_MS = 1_200L
    private const val SAMPLES = 16
    private const val ENOUGH = 10

    private val started = Collections.synchronizedMap(WeakHashMap<ImageRequest, Long>())
    private val recent = ArrayDeque<Long>()

    /** One object for every card request, so a recomposed card's request stays equal. */
    val timing =
        object : ImageRequest.Listener {
            override fun onStart(request: ImageRequest) {
                if (mode == AUTO && !slow) started[request] = SystemClock.uptimeMillis()
            }

            override fun onSuccess(
                request: ImageRequest,
                result: SuccessResult,
            ) {
                val at = started.remove(request) ?: return
                // Only downloads: the memory and disk caches say nothing about the connection
                if (result.dataSource == DataSource.NETWORK) record(SystemClock.uptimeMillis() - at)
            }

            override fun onError(
                request: ImageRequest,
                result: ErrorResult,
            ) {
                started.remove(request)
                Timber.w("Card picture failed: %s (%s)", result.throwable.toString().take(160), request.data.toString().substringBefore("api_key").takeLast(120))
                // Nothing arrived for the whole read timeout: as slow as it gets
                if (result.throwable is java.net.SocketTimeoutException && mode == AUTO) record(SLOW_MS * 10)
            }

            override fun onCancel(request: ImageRequest) {
                started.remove(request)
            }
        }

    @Synchronized
    private fun record(ms: Long) {
        if (slow) return
        recent.addLast(ms)
        if (recent.size > SAMPLES) recent.removeFirst()
        if (recent.size < ENOUGH) return
        val median = recent.sorted()[recent.size / 2]
        if (median > SLOW_MS) {
            Timber.i("Card pictures slow (median %d ms of %d): smaller ones from now on", median, recent.size)
            recent.clear()
            slowSince = System.currentTimeMillis()
            slow = true
            justSwitched = true
            onVerdict(slowSince)
        }
    }
}

/**
 * A row card's picture request: [PosterSize.fit] and timed for Auto. Kept per card while it's
 * drawn, so Auto going small doesn't reload pictures already on screen (only a changed setting
 * does).
 */
@androidx.compose.runtime.Composable
internal fun rememberCardPicture(
    url: String,
    width: Int,
    height: Int,
    attempt: Int = 0,
): ImageRequest {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode = PosterSize.mode
    return androidx.compose.runtime.remember(url, mode, attempt) {
        ImageRequest
            .Builder(context)
            .data(PosterSize.fit(url))
            .size(width, height)
            .listener(PosterSize.timing)
            // Plain bitmaps: a hardware one is allocated and uploaded on a second GL context per
            // picture, and while posters land the frame on screen waited 50-150 ms for the GPU
            .allowHardware(false)
            // Fades in when it arrives mid-glide instead of popping; one already in memory
            // (scrolling back) shows at once, Coil skips the fade for those
            .crossfade(CARD_FADE_MS)
            // A retry must differ from the failed request, or the image won't ask again
            .apply { if (attempt > 0) memoryCacheKeyExtra("try", attempt.toString()) }
            .build()
    }
}

/** A card picture's (and its logo's) fade in, when it wasn't in memory already. */
internal const val CARD_FADE_MS = 300

/** Pauses before each new try of a card picture that failed. */
private val RETRY_AFTER_MS = longArrayOf(1_500, 4_000, 10_000)

/**
 * A row card's picture, tried again when it fails. On a slow connection the server's one HTTP/2
 * connection carries rows, logos and pictures together; while the remote is held down some
 * pictures get no bytes for the whole read timeout and used to stay blank for good.
 */
@androidx.compose.runtime.Composable
internal fun CardPicture(
    url: String,
    width: Int,
    height: Int,
    contentDescription: String?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center,
) {
    var attempt by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
    var failed by androidx.compose.runtime.remember(url) { mutableStateOf(false) }
    // On-screen pictures first. Rows build a couple of cards ahead off screen; their pictures
    // used to download at once and queue in front of the ones being looked at. Now they start
    // when the card is first placed on screen, or once the remote has rested.
    var go by androidx.compose.runtime.remember(url) { mutableStateOf(false) }
    if (!go) androidx.compose.runtime.LaunchedEffect(url) {
        Conductor.whenQuiet()
        go = true
    }
    if (failed) {
        androidx.compose.runtime.LaunchedEffect(attempt) {
            kotlinx.coroutines.delay(RETRY_AFTER_MS[attempt])
            failed = false
            attempt++
        }
    }
    androidx.compose.foundation.layout.Box(
        modifier.then(
            if (go) {
                androidx.compose.ui.Modifier
            } else {
                androidx.compose.ui.Modifier.onPlaced { go = true }
            },
        ),
    ) {
        if (go) {
            coil3.compose.AsyncImage(
                model = rememberCardPicture(url, width, height, attempt),
                contentDescription = contentDescription,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alignment = alignment,
                onError = { if (attempt < RETRY_AFTER_MS.size) failed = true },
                modifier = androidx.compose.ui.Modifier.matchParentSize(),
            )
        }
    }
}
