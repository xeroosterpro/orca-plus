package com.wholphinplus.sources.cinema

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith

/**
 * How Cinema screens replace each other (Wholphin's NavDisplay asks, through a hook).
 *
 * Navigation's stock change faded both screens at once over 0.7 s: two half-clear screens over
 * black, so opening a title dipped to black in the middle (owner: the "blink"). Here the screen
 * underneath stays as it is: a new one fades in over it, and going back the top one fades away
 * to show what's below. The title page shows its picture at once when the card's billboard just
 * had it (StableBackdrop), so opening a title reads as the text changing over the same picture.
 */
object CinemaNavMotion {
    /** Opening a screen: it fades in over the one before, which leaves only once it's covered. */
    fun push(): ContentTransform = fadeIn(tween(OPEN_MS, easing = CinemaFade)) togetherWith fadeOut(tween(1, delayMillis = OPEN_MS))

    /** Going back: the screen below is there at once, and the top one fades away over it. */
    fun pop(): ContentTransform = ContentTransform(EnterTransition.None, fadeOut(tween(CLOSE_MS, easing = CinemaFade)), targetContentZIndex = -1f)

    /** Wholphin's own (navigation's default), for the classic look. */
    fun classic(): ContentTransform = fadeIn(tween(700)) togetherWith fadeOut(tween(700))

    private const val OPEN_MS = 500
    private const val CLOSE_MS = 340
}
