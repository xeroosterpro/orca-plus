package com.wholphinplus.sources.core

/**
 * A map that keeps at most [max] entries, dropping the least recently used: for what the app
 * remembers per title for the session (cards shown, pictures fetched, library answers, casts).
 * Unbounded, those grew with every title browsed; a 1 GB stick left on for hours felt it.
 * Thread-safe for single calls (get, put, contains, remove); don't iterate it.
 */
internal fun <K, V> lruMap(max: Int): MutableMap<K, V> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        },
    )

/** A set with [lruMap]'s limit. */
internal fun <T> lruSet(max: Int): MutableSet<T> = java.util.Collections.newSetFromMap(lruMap<T, Boolean>(max))
