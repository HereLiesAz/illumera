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
 * A sample it can't rewrite (encrypted, carrying supplemental data, or not fully buffered)
 * is forwarded unchanged, and filtering resumes with the next sample; other codecs pass
 * straight through.
 */
private class MvcStripTrackOutput(private val inner: TrackOutput) : TrackOutput {
    private var filtering = false
    /** The current sample is being forwarded unchanged. */
    private var passthrough = false
    private var pending = ByteArray(256 * 1024)
    private var pendingSize = 0
    private val scratch = ParsableByteArray()
    /**
     * The H.264 format, held back until the first sample shows whether the stream is MVC,
     * so the decoder is chosen knowing that (MvcStats.codecSelector).
     */
    private var heldFormat: Format? = null

    override fun format(format: Format) {
        flushPending()
        releaseFormat()
        filtering = format.sampleMimeType == MimeTypes.VIDEO_H264
        passthrough = false
        if (filtering) {
            MvcStats.onFormat(format.frameRate)
            if (!MvcStats.mvcDetected) {
                heldFormat = format
                return
            }
        }
        inner.format(format)
    }

    private fun releaseFormat() {
        heldFormat?.let { heldFormat = null; inner.format(it) }
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!buffering(sampleDataPart)) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
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
        if (!buffering(sampleDataPart)) {
            inner.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(pendingSize + length)
        data.readBytes(pending, pendingSize, length)
        pendingSize += length
    }

    /** Whether data for [part] goes to the buffer; otherwise it is forwarded as-is. */
    private fun buffering(part: Int): Boolean {
        if (!filtering) return false
        if (part != TrackOutput.SAMPLE_DATA_PART_MAIN && !passthrough) {
            // Keep byte order: what is buffered goes first, then this sample passes through.
            flushPending()
            releaseFormat()
            passthrough = true
        }
        return !passthrough
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!filtering) {
            releaseFormat()
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val end = pendingSize - offset
        val start = end - size
        if (passthrough || cryptoData != null || start < 0) {
            // Not ours to rewrite: everything already went (or now goes) to inner unchanged.
            flushPending()
            releaseFormat()
            passthrough = false
            MvcStats.passedThrough.incrementAndGet()
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val newSize = stripMvcNalUnits(pending, start, end)
        MvcStats.samples.incrementAndGet()
        if (newSize < size) {
            MvcStats.stripped.incrementAndGet()
            MvcStats.mvcDetected = true
        }
        releaseFormat()
        scratch.reset(pending, start + newSize)
        scratch.setPosition(start)
        inner.sampleData(scratch, newSize, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Bytes after this sample (offset > 0) belong to the next one; keep them.
        System.arraycopy(pending, end, pending, 0, pendingSize - end)
        pendingSize -= end
        inner.sampleMetadata(timeUs, flags, newSize, 0, null)
    }

    private fun flushPending() {
        if (pendingSize == 0) return
        releaseFormat()
        scratch.reset(pending, pendingSize)
        inner.sampleData(scratch, pendingSize, TrackOutput.SAMPLE_DATA_PART_MAIN)
        pendingSize = 0
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity > pending.size) pending = pending.copyOf(maxOf(capacity, pending.size * 2))
    }
}

/**
 * Counters for the player's 3D readout: H.264 samples seen, samples that carried MVC units,
 * and samples forwarded unchanged. Reset per playback; the last stream wins.
 */
object MvcStats {
    val samples = java.util.concurrent.atomic.AtomicLong()
    val stripped = java.util.concurrent.atomic.AtomicLong()
    val passedThrough = java.util.concurrent.atomic.AtomicLong()
    @Volatile var frameRate: Float = Format.NO_VALUE.toFloat()
    @Volatile var decoderName: String? = null
    /** The current stream carries MVC (second-eye) data. */
    @Volatile var mvcDetected: Boolean = false

    /**
     * Prefers software H.264 decoders once a stream is known to be MVC. Some TV-box
     * hardware decoders (Rockchip) mishandle Blu-ray 3D, from flickering between the eyes
     * to taking the whole device down; the software decoder shows the stripped base view.
     */
    val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
        val infos = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, secure, tunneling)
        if (mvcDetected && mimeType == MimeTypes.VIDEO_H264 && !secure) {
            infos.sortedByDescending { it.softwareOnly }
        } else infos
    }

    fun onFormat(rate: Float) {
        frameRate = rate
    }

    fun reset() {
        samples.set(0); stripped.set(0); passedThrough.set(0)
        frameRate = Format.NO_VALUE.toFloat(); decoderName = null; mvcDetected = false
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
