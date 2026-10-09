package com.wholphinplus.sources.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.cinema.CinemaEase
import com.wholphinplus.sources.cinema.CinemaFade
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Plus

/** The first screen's fastest way in: a QR code for the phone, and the code to type if needed. */
@Composable
internal fun PhoneSetupCard(modifier: Modifier = Modifier) {
    val code by PhoneSetup.code.collectAsState()
    val offline by PhoneSetup.offline.collectAsState()
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.clip(shape).background(Glass).border(1.dp, GlassLine, shape).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            val c = code
            if (c != null) AsyncImage(model = c.qr, contentDescription = "QR code for your phone", modifier = Modifier.size(142.dp)) else Text(if (offline) "—" else "…", color = Color.Black, fontSize = 22.sp)
        }
        Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = Plus, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(5.dp))
                Text("FASTEST", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
            }
            Text("Set up with your phone", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Scan with your phone's camera and fill in your server and sign-in there. Nothing to type with the remote.",
                color = InkDim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            val c = code
            when {
                c != null -> {
                    // The whole address, on two lines if need be (cut off, it couldn't be typed)
                    Text("No camera? Open ${c.url.removePrefix("https://")}", color = InkDim.copy(alpha = 0.8f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
                }
                offline -> Text("Can't reach the Orca+ cloud right now. Set up on this TV instead.", color = InkDim, fontSize = 13.sp)
            }
        }
    }
}

/**
 * While the phone's details are being used: what's happening (connecting, signing in, bringing
 * the setup back) or what went wrong and that it's fixed on the phone.
 */
@Composable
internal fun PhoneSetupBanner(modifier: Modifier = Modifier) {
    val details by PhoneSetup.details.collectAsState()
    val phase by PhoneSetup.phase.collectAsState()
    val problem by PhoneSetup.problem.collectAsState()
    val d = details
    AnimatedVisibility(
        visible = d != null && phase != PhoneSetup.Phase.DONE,
        enter = fadeIn(tween(360, easing = CinemaEase)) + slideInVertically(tween(420, easing = CinemaEase)) { -it / 3 },
        exit = fadeOut(tween(300, easing = CinemaFade)),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(16.dp)
        val p = problem
        Row(
            Modifier.widthIn(max = 430.dp).clip(shape).background(Color(0xF0141418)).border(1.dp, GlassLine, shape).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (p != null) Color(0xFFFF8A80) else Plus))
            Column {
                Text("FROM YOUR PHONE", color = InkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                Text(
                    when {
                        p != null -> "$p. Fix it on your phone and send again."
                        phase == PhoneSetup.Phase.CONNECTING -> "Connecting to ${d?.server}…"
                        phase == PhoneSetup.Phase.SIGNING_IN -> "Signing in as ${d?.username}…"
                        phase == PhoneSetup.Phase.RESTORING -> "Bringing back your Orca+ setup…"
                        else -> "Waiting for your phone…"
                    },
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "Use my phone" from a step that asks for an address or a password (owner, 2026-10-09: always
 * offer the phone there): the first screen's QR card over the step. What the phone sends goes
 * through the welcome's usual phone handling (server, then sign-in); Back closes it.
 */
@Composable
internal fun PhoneOverlay(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { PhoneSetup.ensureStarted(context.welcomeHook()) }
    androidx.activity.compose.BackHandler { onClose() }
    val back = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xE6070709)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            PhoneSetupCard()
            PillButton("Back", primary = false, modifier = Modifier.focusRequester(back), onClick = onClose)
        }
        PhoneSetupBanner(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 40.dp))
    }
}
