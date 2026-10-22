package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MvcStripTest {
    private fun nal(type: Int, vararg payload: Int) =
        byteArrayOf(0, 0, 0, 1, type.toByte()) + payload.map { it.toByte() }.toByteArray()

    private fun strip(input: ByteArray): ByteArray {
        val buf = input.copyOf()
        return buf.copyOf(stripMvcNalUnits(buf, 0, buf.size))
    }

    @Test
    fun removesMvcUnitsAndKeepsBaseView() {
        val sps = nal(0x67, 1, 2)      // SPS (type 7)
        val prefix = nal(0x0E, 9)      // prefix NAL (14)
        val idr = nal(0x65, 3, 4, 5)   // IDR slice (5)
        val subsetSps = nal(0x0F, 7)   // subset SPS (15)
        val ext = nal(0x14, 8, 8)      // coded slice extension (20)
        assertArrayEquals(sps + idr, strip(sps + prefix + idr + subsetSps + ext))
    }

    @Test
    fun leavesPlainH264Untouched() {
        val sample = nal(0x67, 1) + nal(0x68, 2) + nal(0x65, 3, 0, 4)
        assertArrayEquals(sample, strip(sample))
    }
}
