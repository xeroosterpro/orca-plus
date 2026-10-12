package com.wholphinplus.sources.core

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * For `runCatching` around suspending or network work: a cancellation always goes on (the
 * screen that wanted the answer is gone; carrying on spends CPU and data for nothing), any other
 * failure is noted in the log with [what] before the caller falls back.
 */
internal fun <T> Result<T>.logged(what: String): Result<T> =
    onFailure {
        if (it is CancellationException) throw it
        Timber.w("%s failed: %s", what, it.toString().take(200))
    }

/** [logged] without the note, where a failure is expected and harmless (an optional extra). */
internal fun <T> Result<T>.cancellable(): Result<T> = onFailure { if (it is CancellationException) throw it }
