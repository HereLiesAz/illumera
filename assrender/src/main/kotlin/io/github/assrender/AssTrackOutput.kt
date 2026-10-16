package io.github.assrender

import android.util.Log
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Implements [TrackOutput] directly (not ForwardingTrackOutput) to ensure
 * we capture ALL data before forwarding to the delegate.
 */
@UnstableApi
internal class AssTrackOutput(
    private val delegate: TrackOutput,
    private val handler: AssHandler,
    private val trackId: Int
) : TrackOutput {

    companion object {
        private const val TAG = "assrender"

        /** How much of the codec private data is searched for the ASS section markers. */
        private const val HEADER_SCAN_BYTES = 2048

        /**
         * Whether [data] is an ASS/SSA script header. Skips a UTF-8 BOM and leading
         * comments/blank lines by searching a window rather than only the first bytes.
         */
        private fun looksLikeAssHeader(data: ByteArray): Boolean {
            val preview = String(data, 0, minOf(HEADER_SCAN_BYTES, data.size), Charsets.UTF_8)
                .removePrefix("\uFEFF")
            return preview.contains("[Script Info]", ignoreCase = true) ||
                preview.contains("ScriptType:", ignoreCase = true)
        }
    }

    private var isAssTrack = false
    private val pendingData = ByteArrayOutputStream()

    override fun format(format: Format) {
        val mimeType = format.sampleMimeType

        isAssTrack = mimeType == MimeTypes.TEXT_SSA || mimeType == "text/x-ssa" || mimeType == "text/x-ass"

        val header = format.initializationData.firstOrNull { looksLikeAssHeader(it) }
        if (header != null) isAssTrack = true
        if (isAssTrack && header != null) {
            handler.onTrackHeader(trackId, header, format)
        }

        Log.d(TAG, "Track[$trackId] format: mime=$mimeType, isAss=$isAssTrack")
        delegate.format(format)
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int
    ) {
        if (isAssTrack) {
            val pos = data.position
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pendingData.write(bytes)
            data.setPosition(pos)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (isAssTrack) {
            val bytes = ByteArray(length)
            val bytesRead = input.read(bytes, 0, length)
            if (bytesRead > 0) {
                pendingData.write(bytes, 0, bytesRead)
                // Forward the data we already read via ParsableByteArray
                val pba = ParsableByteArray(bytes, bytesRead)
                delegate.sampleData(pba, bytesRead, sampleDataPart)
            }
            return bytesRead
        }
        return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAssTrack && pendingData.size() > 0) {
            handler.onSubtitleSample(trackId, timeUs, pendingData.toByteArray())
            pendingData.reset()
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
