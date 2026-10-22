@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.hereliesaz.illumera.ui.player.base

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Blu-ray 3D (H.264 MVC) carries the second eye as extra NAL units inside an ordinary
 * H.264 stream. A 2D decoder should ignore them, but some TV-box decoders (Rockchip among
 * them) decode both views and present them alternately, so the picture jitters between
 * the two viewpoints. On a display that can't show 3D, these factories remove the MVC
 * units (prefix NAL 14, subset SPS 15, slice extension 20) before samples reach any
 * decoder, leaving the base (left-eye) view only. Plain H.264 never contains them.
 */
class MvcStripExtractorsFactory(private val inner: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        inner.createExtractors().map { MvcStripExtractor(it) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).map { MvcStripExtractor(it) }.toTypedArray()
}

private class MvcStripExtractor(private val inner: Extractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(MvcStripExtractorOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

private class MvcStripExtractorOutput(private val inner: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = inner.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) MvcStripTrackOutput(track) else track
    }

    override fun endTracks() = inner.endTracks()
    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

/**
 * Buffers each H.264 sample, drops MVC NAL units, then forwards the shortened sample.
 * Anything else (other codecs, encrypted or multi-part samples) passes straight through.
 */
private class MvcStripTrackOutput(private val inner: TrackOutput) : TrackOutput {
    private var filtering = false
    private var pending = ByteArray(256 * 1024)
    private var pendingSize = 0
    private val scratch = ParsableByteArray()

    override fun format(format: Format) {
        filtering = format.sampleMimeType == MimeTypes.VIDEO_H264
        inner.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!filtering || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            stopFiltering()
            return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureCapacity(pendingSize + length)
        val read = input.read(pending, pendingSize, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        pendingSize += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!filtering || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            stopFiltering()
            inner.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(pendingSize + length)
        data.readBytes(pending, pendingSize, length)
        pendingSize += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!filtering) {
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val end = pendingSize - offset
        val start = end - size
        if (cryptoData != null || start < 0) {
            // Not ours to rewrite: forward the buffered bytes unchanged and stop filtering.
            flushPending()
            filtering = false
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val newSize = stripMvcNalUnits(pending, start, end)
        scratch.reset(pending, start + newSize)
        scratch.setPosition(start)
        inner.sampleData(scratch, newSize, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Bytes after this sample (offset > 0) belong to the next one; keep them.
        System.arraycopy(pending, end, pending, 0, pendingSize - end)
        pendingSize -= end
        inner.sampleMetadata(timeUs, flags, newSize, 0, null)
    }

    private fun stopFiltering() {
        if (!filtering) return
        flushPending()
        filtering = false
    }

    private fun flushPending() {
        if (pendingSize == 0) return
        scratch.reset(pending, pendingSize)
        inner.sampleData(scratch, pendingSize, TrackOutput.SAMPLE_DATA_PART_MAIN)
        pendingSize = 0
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity > pending.size) pending = pending.copyOf(maxOf(capacity, pending.size * 2))
    }
}

/**
 * Removes NAL units of type 14, 15 and 20 from the Annex B data in [buf] between [start]
 * and [end], compacting in place. Returns the new length (from [start]).
 */
internal fun stripMvcNalUnits(buf: ByteArray, start: Int, end: Int): Int {
    // Locate start codes (00 00 01, optionally preceded by 00).
    val units = mutableListOf<Int>() // index of each start code's first byte
    var i = start
    while (i + 2 < end) {
        if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
            units += if (i > start && buf[i - 1] == 0.toByte()) i - 1 else i
            i += 3
        } else {
            i++
        }
    }
    if (units.isEmpty()) return end - start
    var write = units[0]
    // Anything before the first start code is kept as-is.
    for ((n, unitStart) in units.withIndex()) {
        val unitEnd = if (n + 1 < units.size) units[n + 1] else end
        var header = unitStart
        while (header < unitEnd && buf[header] == 0.toByte()) header++
        val type = if (header + 1 < unitEnd) buf[header + 1].toInt() and 0x1F else -1
        if (type == 14 || type == 15 || type == 20) continue
        val length = unitEnd - unitStart
        if (write != unitStart) System.arraycopy(buf, unitStart, buf, write, length)
        write += length
    }
    return write - start
}
