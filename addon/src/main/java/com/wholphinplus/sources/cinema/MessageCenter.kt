package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.wholphinplus.sources.AccountActions
import com.wholphinplus.sources.Inbox
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.ui.SettingsRailItem
import com.wholphinplus.sources.ui.SettingsRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFFC46B)
private val Glass = Color(0xF0141418)

private fun Inbox.Level.tint(): Color =
    when (this) {
        Inbox.Level.GOOD -> Good
        Inbox.Level.WARN -> Warn
        Inbox.Level.INFO -> Plus
    }

private fun Inbox.Message.icon(): ImageVector =
    when (kind) {
        Inbox.Kind.UPDATE -> Icons.Filled.Star
        Inbox.Kind.WATCHING -> Icons.Filled.PlayArrow
        Inbox.Kind.LIBRARY -> Icons.Filled.List
        Inbox.Kind.SYNC -> Icons.Filled.Refresh
        Inbox.Kind.SERVERS -> if (level == Inbox.Level.WARN) Icons.Filled.Warning else Icons.Filled.CheckCircle
        Inbox.Kind.ACCOUNT -> Icons.Filled.Person
    }

/** "Today, 10:42 PM", "Yesterday, 9:05 AM", "Monday, 8:00 PM", "Oct 2, 7:15 PM". */
internal fun whenText(at: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val now = Calendar.getInstance()
    fun dayOf(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    val days = dayOf(now) - dayOf(then)
    val day =
        when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(at))
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at))
        }
    return "$day, $time"
}

/** What each kind covers, on the pop-up switches. */
private fun Inbox.Kind.about(): String =
    when (this) {
        Inbox.Kind.UPDATE -> "A new Orca+ on GitHub, with Update now"
        Inbox.Kind.WATCHING -> "Where a movie or episode stopped, and from which server"
        Inbox.Kind.LIBRARY -> "Your library finished matching (rows can show everything)"
        Inbox.Kind.SYNC -> "Changes arriving from another TV, or sync trouble"
        Inbox.Kind.SERVERS -> "A server went offline or needs a new sign-in"
        Inbox.Kind.ACCOUNT -> "Switching accounts on this TV"
    }

/**
 * The Message Center (the bell in the top bar): every message, newest first, by kind on the left;
 * OK on one does its button (Update now, Resume, Sign in again, Sync now), held OK removes it.
 * The last topic switches what pops up. Messages are marked read when it closes.
 */
@Composable
internal fun MessageCenter(
    hook: SourceHook,
    onAction: (Inbox.Action) -> Unit,
    onClose: () -> Unit,
) {
    val messages by Inbox.messages.collectAsState()
    val popUps by Inbox.popUps.collectAsState()
    val welcome by Inbox.welcome.collectAsState()
    val index by hook.collections.indexProgress.collectAsState()
    // null: every kind; SETTINGS: the pop-up switches
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    // Right from the rail lands on the pane's first row (it took the nearest one, scrolled the top away)
    val paneFirst = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { onDispose { Inbox.markAllRead() } }
    LaunchedEffect(Unit) {
        repeat(20) {
            kotlinx.coroutines.android.awaitFrame()
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val unread = messages.count { !it.read }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15131C), Stage)))) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 44.dp)) {
            Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ORCA+", color = Plus, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Messages", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (unread > 0) "$unread new" else "All caught up", color = InkDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 18.dp))
                SettingsRailItem("All", "${messages.size} messages", filter == null, { filter = null }, Modifier.focusRequester(first))
                Inbox.Kind.entries.forEach { k ->
                    val n = messages.count { it.kind == k }
                    val new = messages.count { it.kind == k && !it.read }
                    SettingsRailItem(k.label, if (new > 0) "$new new" else if (n == 0) "None yet" else "$n", filter == k.name, { filter = k.name })
                }
                Spacer(Modifier.height(10.dp))
                SettingsRailItem("Pop-ups", "What shows up on screen", filter == SETTINGS, { filter = SETTINGS })
            }
            Spacer(Modifier.width(40.dp))
            Column(
                Modifier.weight(1f).fillMaxHeight().focusProperties {
                    onEnter = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right) runCatching { paneFirst.requestFocus() } }
                }.focusGroup(),
            ) {
                val kind = Inbox.Kind.entries.firstOrNull { it.name == filter }
                Row(Modifier.fillMaxWidth().padding(top = 58.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            filter == SETTINGS -> "Pop-ups"
                            kind != null -> kind.label
                            else -> "Everything"
                        },
                        color = Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val check = Inbox.checkUpdate
                    if (check != null && (kind == null || kind == Inbox.Kind.UPDATE) && filter != SETTINGS) {
                        HeroButton(if (checking) "Checking…" else "Check for updates", Icons.Filled.Refresh, primary = false) {
                            if (checking) return@HeroButton
                            checking = true
                            scope.launch {
                                runCatching { check() }.onFailure {
                                    Inbox.post(Inbox.Kind.UPDATE, "Couldn't check for updates", "GitHub didn't answer. Try again in a little while.", Inbox.Level.WARN, key = "update:check", popUp = false, unread = false)
                                }
                                checking = false
                            }
                        }
                    }
                    if (filter != SETTINGS && messages.isNotEmpty()) {
                        HeroButton("Clear all", Icons.Filled.Delete, primary = false) { Inbox.clear() }
                    }
                }
                if (filter == SETTINGS) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
                        item {
                            Text("Everything is always kept here. These decide what also pops up on screen (never while something plays).", color = InkDim, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
                        }
                        item {
                            SwitchRow("Welcome greeting", "A short hello when Home opens after Orca+ starts", welcome, Modifier.focusRequester(paneFirst)) { Inbox.setWelcome(!welcome) }
                        }
                        items(Inbox.Kind.entries, key = { it.name }) { k ->
                            SwitchRow(k.label, k.about(), k in popUps) { Inbox.setPopUp(k, k !in popUps) }
                        }
                    }
                } else {
                    val shown = messages.filter { kind == null || it.kind == kind }
                    val reading = index?.takeIf { kind == null || kind == Inbox.Kind.LIBRARY }
                    if (shown.isEmpty() && reading == null) {
                        Column(Modifier.padding(start = 16.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nothing here yet", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("Updates, where you left off, your library, sync and servers show up here as they happen.", color = InkDim, fontSize = 14.sp)
                        }
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        reading?.let { p ->
                            item(key = "reading") {
                                val share = if (p.total > 0) p.done.toFloat() / p.total else 0f
                                SettingsRow(
                                    onClick = {},
                                    modifier = Modifier.focusRequester(paneFirst),
                                    leadingContent = { KindDot(Icons.Filled.List, Plus) },
                                    headlineContent = { Text(if (p.first) "Getting your library ready · ${(share * 100).toInt()}%" else "Updating your library · ${(share * 100).toInt()}%", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Matching ${"%,d".format(p.total)} titles. It waits while you use the remote.", color = InkDim, fontSize = 14.sp)
                                            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
                                                Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(Plus, RoundedCornerShape(2.dp)))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        items(shown, key = { it.id }) { m -> MessageRow(m, onAction, if (reading == null && m === shown.first()) Modifier.focusRequester(paneFirst) else Modifier) }
                    }
                }
            }
        }
    }
}

private const val SETTINGS = "SETTINGS"

@Composable
private fun SwitchRow(
    title: String,
    about: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SettingsRow(
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = { Text(about) },
        trailingContent = { androidx.tv.material3.Switch(checked = on, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
    )
}

@Composable
private fun KindDot(
    icon: ImageVector,
    tint: Color,
) {
    Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MessageRow(
    m: Inbox.Message,
    onAction: (Inbox.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        onClick = { m.action?.let(onAction) },
        modifier = modifier,
        onLongClick = { Inbox.remove(m.id) },
        leadingContent = { KindDot(m.icon(), m.level.tint()) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!m.read) Box(Modifier.size(8.dp).background(Plus, CircleShape))
                Text(m.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (m.body.isNotBlank()) Text(m.body, color = InkDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${whenText(m.at)}  ·  ${m.kind.label}", color = InkDim.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        },
        trailingContent =
            m.action?.let { a ->
                {
                    Text(
                        a.label,
                        color = Stage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Ink, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            },
    )
}

/**
 * Pop-ups, top right over the billboard's picture: one at a time, ~6 s each, never focusable
 * (the remote stays where it was). What came while Home was away shows when it's back.
 */
@Composable
internal fun InboxBanner(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        Inbox.bannerHosts++
        onDispose { Inbox.bannerHosts-- }
    }
    var shown by remember { mutableStateOf<Inbox.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // What came while Home was away (a play that just stopped), then whatever comes next
        delay(800)
        for (m in Inbox.takeWaiting()) {
            if (Conductor.isPlaying) break
            shown = m
            visible = true
            delay(6_500)
            visible = false
            delay(500)
        }
        Inbox.banners.collect { m ->
            shown = m
            visible = true
            delay(6_500)
            visible = false
            delay(500)
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320, easing = CinemaEase)) + slideInHorizontally(tween(420, easing = CinemaEase)) { it / 6 },
        exit = fadeOut(tween(400, easing = CinemaFade)),
        modifier = modifier,
    ) {
        val m = shown ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(min = 300.dp, max = 440.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(Glass)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(m.level.tint()))
            Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindDot(m.icon(), m.level.tint())
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.kind.label.uppercase(), color = m.level.tint(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(m.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (m.body.isNotBlank()) Text(m.body, color = InkDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (m.action != null) Text("${m.action.label} in Messages (the bell, top right)", color = InkDim.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * The welcome (owner, 2026-10-10: "looks cool but professional"): once per start of the app, when
 * Home has its first rows. A greeting by the time of day and the profile's name, today's date,
 * and up to three lines of what's new from the Message Center. ~6 s, never takes focus.
 */
@Composable
internal fun WelcomeGreeting(
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val on by Inbox.welcome.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ready, on) {
        if (!ready || !on || Inbox.greeted) return@LaunchedEffect
        Inbox.greeted = true
        delay(700)
        if (com.wholphinplus.sources.cinema.Conductor.isPlaying) return@LaunchedEffect
        visible = true
        delay(6_500)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500, easing = CinemaEase)) + slideInVertically(tween(600, easing = CinemaEase)) { -it / 8 },
        exit = fadeOut(tween(600, easing = CinemaFade)),
        modifier = modifier,
    ) {
        val now = remember { System.currentTimeMillis() }
        val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
        val name = remember { AccountActions.who()?.user?.substringAfterLast('#')?.trim()?.takeIf { it.isNotBlank() } }
        val hello =
            when (hour) {
                in 5..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            }
        val lines = remember { greetingLines() }
        // The accent line draws across as the card arrives
        val sweep by animateFloatAsState(if (visible) 1f else 0f, tween(900, delayMillis = 250, easing = CinemaEase), label = "sweep")
        Column(
            Modifier.widthIn(min = 340.dp, max = 420.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xF21B1726), Color(0xF00E0E12))))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ORCA+", color = Plus, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.width(10.dp))
                Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)).uppercase(), color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
            }
            Text(if (name != null) "$hello, $name" else hello, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Box(
                Modifier.padding(top = 4.dp, bottom = 6.dp).fillMaxWidth().height(2.dp).graphicsLayer {
                    scaleX = sweep
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }.background(Brush.horizontalGradient(listOf(Plus, Plus.copy(alpha = 0f)))),
            )
            lines.forEach { (dot, text) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(6.dp).background(dot, CircleShape))
                    Text(text, color = Ink.copy(alpha = 0.88f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Up to three lines for the welcome, most useful first; "all caught up" when there's nothing. */
private fun greetingLines(): List<Pair<Color, String>> {
    val all = Inbox.messages.value
    val out = ArrayList<Pair<Color, String>>()
    all.firstOrNull { it.key == "updated" && System.currentTimeMillis() - it.at < 10 * 60_000L }?.let { out += Good to it.title }
    all.firstOrNull { it.kind == Inbox.Kind.UPDATE && it.action is Inbox.Action.Update }?.let { out += Good to it.title }
    all.firstOrNull { it.kind == Inbox.Kind.SERVERS && it.level == Inbox.Level.WARN }?.let { out += Warn to it.title }
    all.firstOrNull { it.kind == Inbox.Kind.WATCHING && it.body.startsWith("Stopped") }?.let { m -> out += Plus to "Pick up ${m.title}" }
    // New messages the lines above don't already say
    val unread = all.count { !it.read && out.none { (_, line) -> it.title in line } }
    if (out.size < 3 && unread > 0) out += Plus to "$unread new in Messages"
    if (out.size < 3) all.firstOrNull { it.key == "sync" && it.level != Inbox.Level.WARN }?.let { out += Good to "Synced ${whenText(it.at).replaceFirst(", ", " at ").replaceFirstChar { c -> c.lowercase() }}" }
    if (out.isEmpty()) out += Good to "All caught up. Enjoy the show."
    return out.take(3)
}
