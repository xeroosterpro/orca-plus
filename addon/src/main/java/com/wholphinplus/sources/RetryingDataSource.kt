package com.wholphinplus.sources

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The player's connection to a file, made to ride out a server that fails at random. Some
 * servers' storage answers a range request with 404 (or a 5xx) now and then and the same request
 * a moment later with the file (Silo, 2026-10-08: up to half of all opens). Media3 treats that
 * 404 as final, so a direct-played movie died mid-way or at a resume point and fell back to a
 * transcode the server may not offer. Here a failed open is tried again, quietly, a few times
 * within ~7 s before the player hears of it.
 */
@OptIn(UnstableApi::class)
class RetryingDataSource(
    private val upstream: DataSource,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RetryingDataSource(upstream.createDataSource())
    }

    override fun open(dataSpec: DataSpec): Long =
        withRetries(isTransient = { e -> isTransient(e).also { if (it) StreamHealth.failed((e as? HttpDataSource.InvalidResponseCodeException)?.responseCode) } }, sleep = sleep, onRetry = { attempt, e ->
            Timber.w("Stream open failed (%s), try %d: %s", LogScrub.text(e.message), attempt + 1, dataSpec.uri.path)
            try {
                upstream.close()
            } catch (_: IOException) {
            }
        }) { upstream.open(dataSpec) }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        /** Tries in all (the first included), and the wait before each retry. */
        const val MAX_TRIES = 8

        fun delayMs(retry: Int): Long = minOf(250L * retry, 1500L)

        /** Answers a flaky server gives now and then for a file it has. */
        fun isTransientCode(code: Int): Boolean = code == 404 || code == 408 || code == 429 || code in 500..504

        private fun isTransient(e: IOException): Boolean = e is HttpDataSource.InvalidResponseCodeException && isTransientCode(e.responseCode)

        /**
         * Runs [block], again after a growing pause while it fails with an exception
         * [isTransient] accepts, up to [MAX_TRIES] in all. An interrupt (the player cancelling
         * the load) ends it at once.
         */
        fun <T> withRetries(
            isTransient: (IOException) -> Boolean,
            sleep: (Long) -> Unit,
            onRetry: (attempt: Int, IOException) -> Unit = { _, _ -> },
            block: () -> T,
        ): T {
            var attempt = 0
            while (true) {
                try {
                    return block()
                } catch (e: IOException) {
                    if (e is InterruptedIOException || !isTransient(e) || attempt + 1 >= MAX_TRIES || Thread.currentThread().isInterrupted) throw e
                    onRetry(attempt, e)
                    attempt++
                    try {
                        sleep(delayMs(attempt))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException().apply { initCause(e) }
                    }
                }
            }
        }
    }
}
