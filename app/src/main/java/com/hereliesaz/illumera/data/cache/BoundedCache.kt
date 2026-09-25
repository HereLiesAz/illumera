package com.hereliesaz.illumera.data.cache

import java.util.Collections

/**
 * Thread-safe in-memory map that keeps at most [maxEntries], evicting the least recently
 * used. For lookup caches in long TV sessions, which otherwise grow for the life of the
 * process.
 */
fun <K, V> boundedCache(maxEntries: Int): MutableMap<K, V> =
    Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxEntries
    })
