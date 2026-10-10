package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wholphinplus.sources.ServerBrands
import com.wholphinplus.sources.core.LibraryTitle
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.TmdbItem
import com.wholphinplus.sources.core.TmdbType
import com.wholphinplus.sources.core.label
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The server's own name, without its software in front (its tile shows the logo). */
internal val ServerConnection.plainName: String
    get() = label.removePrefix(serverKind.label + " ").ifBlank { label }

/** A server tile's id ([PageTile.id]): "server:" + [SERVER_MAIN] or a connection id. */
internal fun serverTileId(server: String) = "server:$server"

internal fun serverOfTile(tileId: String): String? = tileId.removePrefix("server:").takeIf { tileId.startsWith("server:") }

/**
 * Titles from an extra server's library (the My Servers tab). Their cards carry an id made up
 * here; [of] tells what it stands for when one is opened. Kept for the app's life, like the
 * tab's pages (never saved on the device: [PageSnapshots] leaves the tab out).
 */
internal object ServerTitles {
    class Title(
        val connectionId: String,
        val title: LibraryTitle,
    )

    private val byId = ConcurrentHashMap<UUID, Title>()

    fun of(id: UUID): Title? = byId[id]

    fun item(
        c: ServerConnection,
        t: LibraryTitle,
    ): CinemaItem {
        val id = UUID.nameUUIDFromBytes("orca-server:${c.connectionId}:${t.id}".toByteArray())
        byId[id] = Title(c.connectionId, t)
        val kind = if (t.series) BaseItemKind.SERIES else BaseItemKind.MOVIE
        val length =
            if (t.series) {
                t.seasons?.let { if (it == 1) "1 Season" else "$it Seasons" }
            } else {
                t.minutes?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
            }
        return CinemaItem(
            id = id,
            kind = kind,
            detailsId = id,
            detailsKind = kind,
            title = t.name,
            subtitle = null,
            meta = listOfNotNull(t.year?.toString(), length, c.plainName),
            rating = null,
            overview = t.overview,
            backdropUrl = t.backdropUrl,
            cleanCardUrl = if (t.cardHasTitleArt) null else t.cardUrl,
            cardUrl = t.cardUrl,
            cardHasTitleArt = t.cardHasTitleArt,
            logoUrl = null,
            badge = null,
            resumeMs = 0L,
            progress = null,
            tmdbId = t.tmdbId,
            tmdbTv = t.series,
            posterUrl = t.posterUrl,
            captionDate = t.year?.toString(),
            captionLength = length,
        )
    }

    /** The title as TMDB knows it, for "is it on the main server" and the copies sheet. */
    fun tmdbItem(
        t: LibraryTitle,
        tmdbId: Int,
    ) = TmdbItem(
        id = tmdbId,
        type = if (t.series) TmdbType.TV else TmdbType.MOVIE,
        title = t.name,
        year = t.year,
        overview = t.overview,
        posterPath = null,
        backdropPath = null,
        rating = 0.0,
        popularity = 0.0,
        voteCount = 0,
    )
}

/** A server's tile: its software's logo, its name and how many libraries it brings. */
@Composable
internal fun ServerFace(tile: PageTile) {
    val kind = ServerKind.entries.firstOrNull { it.name == tile.brand } ?: ServerKind.JELLYFIN
    val main = serverOfTile(tile.id) == SERVER_MAIN
    val brand = remember(tile.brand, main) { mutableStateOf(ServerBrands.known(kind, if (main) ServerBrands.mainUrl() else "")) }
    if (main) LaunchedEffect(Unit) { brand.value = ServerBrands.check(kind, ServerBrands.mainUrl()) }
    val trouble = tile.note?.startsWith("⚠") == true
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF26232E), Color(0xFF18161D)))),
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tile.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            tile.note?.let {
                Text(it, color = if (trouble) Color(0xFFFFC46B) else InkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        brand.value?.let { b ->
            Image(
                painterResource(ServerBrands.logo(b)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp).height(26.dp).widthIn(max = 120.dp),
            )
        }
        if (main) {
            Text(
                "MAIN",
                color = InkDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
            )
        }
    }
}

/**
 * One server's page, opened from its tile: all its libraries as rows, its name on top. Built
 * from the My Servers tab's own load ([tab]); Back closes it.
 */
@Composable
internal fun ServerPageView(
    tileId: String,
    tab: CinemaHomeData?,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onClose: () -> Unit,
    rollUp: Boolean,
    lastInput: MutableLongState,
    covered: Boolean = false,
) {
    val server = serverOfTile(tileId)
    BackHandler(enabled = !covered, onBack = onClose)
    val tile = tab?.rows?.firstOrNull()?.tiles?.firstOrNull { it.id == tileId }
    if (server == null || tab == null || tile == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // On the server's own page its name is on top: the rows are just the libraries ("4K Movies")
    val rows = remember(tab, server) { tab.rows.filter { it.server == server }.map { it.copy(title = it.title.removePrefix(tile.name + " · ")) } }
    val data = remember(rows) { CinemaHomeData(featured = rows.flatMap { it.items.take(4) }.filter { it.backdropUrl != null }.shuffled().take(8), rows = rows, shows = null, movies = null) }
    var grab by remember(tileId) { mutableStateOf(!covered) }
    val holder = remember { FocusRequester() }
    LaunchedEffect(tileId) {
        if (rows.isEmpty()) {
            for (i in 0 until 10) {
                androidx.compose.runtime.withFrameNanos {}
                if (runCatching { holder.requestFocus() }.getOrDefault(false)) break
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Stage)) {
        if (rows.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nothing to show from ${tile.name}", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tile.note?.takeIf { it.startsWith("⚠") }?.removePrefix("⚠ ")?.let { troubleText(it) }
                        ?: "Its movie and show libraries are empty or switched off in Settings → Servers & Copies → Extra servers.",
                    color = InkDim,
                    fontSize = 14.sp,
                )
            }
            Box(Modifier.size(1.dp).focusRequester(holder).focusable())
        } else {
            CinemaScreen(data, "server:$server", onOpen, onPlay, grabFocus = grab && !covered, rollUp = rollUp, lastInput = lastInput, onGrabbed = { grab = false })
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp).height(TopNavHeight - 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tile.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            tile.note?.let { Text("  ·  $it", color = InkDim, fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** What a server tile's trouble means, for its page ([ServerHealth.Trouble] reasons and the tab's own). */
private fun troubleText(reason: String): String =
    when {
        "sign-in" in reason -> "Its sign-in expired. Sign in again in Settings → Servers & Copies → Extra servers."
        "offline" in reason || "couldn't reach" in reason -> "Orca+ couldn't reach it just now. Check that it's on, or its address in Settings → Servers & Copies → Extra servers."
        "slow" in reason -> "It's answering too slowly right now. Try again in a little while."
        else -> "It answered with an error just now. Try again in a little while."
    }
