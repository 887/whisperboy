package com.eight87.whisperboy.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase J.3 — per-book volume pre-amp implemented as a Media3 [BaseAudioProcessor].
 *
 * Multiplies every 16-bit signed PCM sample by `10^(gainDb / 20)` before the audio sink writes
 * it to the AudioTrack. `gainDb = 0` is a bit-exact bypass (processor reports `isActive = false`).
 *
 * Range chosen at the UI layer (-3 dB .. +12 dB); this processor accepts any finite Float and
 * clips the resulting sample to the signed-16-bit range so a +12 dB pass doesn't wrap.
 *
 * Voice's spiritual analog is `LoudnessEnhancer` (system AudioFx) — we ship a custom processor
 * instead so the gain travels with the Media3 pipeline (no per-session audio-session-id wiring,
 * no `LoudnessEnhancer.setTargetGain` quirks across OEMs) and so the processor can be exercised
 * by Robolectric unit tests without an AudioFlinger.
 *
 * Only `ENCODING_PCM_16BIT` is currently scaled — that's the encoding the audio sink emits for
 * every codec we ship (AAC / Vorbis / Opus / FLAC / MP3 all decode to 16-bit PCM through the
 * default `MediaCodec` pipeline; floating-point output is opt-in and we don't opt in). Other
 * encodings pass through unchanged via `isActive = false` so the sink skips the processor
 * entirely instead of hitting an `UnhandledAudioFormatException`.
 *
 * Pipeline ordering note: Media3's `DefaultAudioSink` runs custom audio processors AFTER its
 * built-in chain (channel-count adapter, trim, Sonic for speed + skip-silence). Sonic outputs
 * 16-bit PCM in our config, so per-book speed + skip-silence still works alongside the gain
 * processor — gain is applied to Sonic's output, not its input.
 */
class VolumeGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Float = 0f

    @Volatile
    private var multiplier: Float = 1f

    fun setGainDb(db: Float) {
        if (db == gainDb) return
        gainDb = db
        multiplier = 10f.pow(db / 20f)
    }

    fun gainDb(): Float = gainDb

    // Always-active when the encoding is one we can scale. Returning `false` at
    // gainDb == 0 used to skip the processor at sink configure time; later
    // gainDb changes from the UI then had no effect until the sink reconfigured
    // (i.e. on format change), which is "slider does nothing" from the user's
    // POV. At gainDb == 0 the multiplier is exactly 1f and queueInput is a
    // bit-exact pass-through — the only cost of always-on is a sample-rate
    // multiply we'd otherwise skip, which is negligible against decode + Sonic.
    override fun isActive(): Boolean =
        inputAudioFormat.encoding == C.ENCODING_PCM_16BIT

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Unsupported encoding: declare bypass (BaseAudioProcessor.configure handles the
            // NOT_SET return as "pass through unchanged").
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val mult = multiplier
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val in16 = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val out16 = output.asShortBuffer()
        val sampleCount = size / 2
        var i = 0
        while (i < sampleCount) {
            val scaled = in16.get(i).toInt() * mult
            val clipped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), scaled))
            out16.put(i, clipped.toInt().toShort())
            i++
        }
        inputBuffer.position(limit)
        output.position(0)
        output.limit(size)
    }
}
