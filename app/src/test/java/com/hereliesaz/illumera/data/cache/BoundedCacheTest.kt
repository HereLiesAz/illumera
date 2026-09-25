package com.hereliesaz.illumera.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedCacheTest {

    @Test
    fun `evicts the least recently used entry past the cap`() {
        val cache = boundedCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        cache["a"]            // touch a, so b is now the oldest
        cache["c"] = 3

        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(2, cache.size)
    }
}
