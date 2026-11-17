package za.munnik.tapoatv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * The TS extractor for Tapo video. Tapo puts its sound in stream type 0x90 (G.711 A-law, 8 kHz mono, like go2rtc's
 * StreamTypePCMATapo — measured 8000 bytes/s on the C530WS and the H500), which ExoPlayer skips by itself.
 */
@OptIn(UnstableApi::class)
object TapoTs {
    private const val TAPO_PCMA = 0x90

    fun extractor(): TsExtractor {
        // Tapo H.264 has no access-unit delimiters, so ExoPlayer must find frame borders itself or it buffers forever.
        val std = DefaultTsPayloadReaderFactory(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val withSound = object : TsPayloadReader.Factory {
            override fun createInitialPayloadReaders() = std.createInitialPayloadReaders()
            override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
                if (streamType == TAPO_PCMA) PesReader(AlawReader()) else std.createPayloadReader(streamType, esInfo)
        }
        return TsExtractor(TsExtractor.MODE_SINGLE_PMT, TimestampAdjuster(0), withSound)
    }

    /** One PES packet = one A-law sample buffer; the box's G.711 decoder does the rest. */
    private class AlawReader : ElementaryStreamReader {
        private lateinit var out: TrackOutput
        private var timeUs = C.TIME_UNSET
        private var size = 0

        override fun seek() { timeUs = C.TIME_UNSET; size = 0 }

        override fun createTracks(output: ExtractorOutput, id: TsPayloadReader.TrackIdGenerator) {
            id.generateNewId()
            out = output.track(id.trackId, C.TRACK_TYPE_AUDIO)
            out.format(Format.Builder().setId(id.formatId).setSampleMimeType(MimeTypes.AUDIO_ALAW)
                .setChannelCount(1).setSampleRate(8000).build())
        }

        override fun packetStarted(pesTimeUs: Long, flags: Int) { timeUs = pesTimeUs; size = 0 }

        override fun consume(data: ParsableByteArray) {
            val n = data.bytesLeft()
            out.sampleData(data, n)
            size += n
        }

        override fun packetFinished(isEndOfInput: Boolean) {
            if (size > 0 && timeUs != C.TIME_UNSET) out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
            size = 0
        }
    }
}
