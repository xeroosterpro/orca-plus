package com.wholphinplus.sources.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One connection pool and one thread pool for all of Orca+'s own requests: extra servers, TMDB,
 * lists, ratings, collections, the Orca+ cloud. Each use takes its own timeouts with [client],
 * which shares both (seven separate clients each kept their own pool and threads; the app ran
 * 50-100 threads). Wholphin's client stays apart: it carries the main server's sign-in, which
 * must never reach another host.
 */
internal object OrcaHttp {
    val base: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
            .build()
    }

    /** [base] with these timeouts (seconds; 0 = none for the whole call). */
    fun client(
        connect: Long,
        read: Long,
        call: Long = 0,
    ): OkHttpClient =
        base.newBuilder()
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .apply { if (call > 0) callTimeout(call, TimeUnit.SECONDS) }
            .build()
}
