package com.eight87.whisperboy.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Pure-function tests for [VolumeGainAudioProcessor]. No Android framework deps required —
 * `BaseAudioProcessor` is pure JVM code in `media3-common`, so plain JUnit suffices.
 */
class VolumeGainAudioProcessorTest {

    private fun pcm16Format(channelCount: Int = 1, sampleRate: Int = 44_100): AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)

    /** `BaseAudioProcessor.configure()` only stores the format as *pending*; `flush()` promotes it. */
    private fun VolumeGainAudioProcessor.configureAndFlush(format: AudioProcessor.AudioFormat) {
        configure(format)
        flush()
    }

    private fun bufferOf(vararg samples: Short): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) bb.putShort(s)
        bb.flip()
        return bb
    }

    private fun readShorts(bb: ByteBuffer): ShortArray {
        val out = ShortArray(bb.remaining() / 2)
        val view = bb.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in out.indices) out[i] = view.get(i)
        return out
    }

    private fun processSamples(p: VolumeGainAudioProcessor, samples: ShortArray): ShortArray {
        p.queueInput(bufferOf(*samples))
        return readShorts(p.output)
    }

    @Test
    fun `isActive true at zero gainDb so later gain changes apply without reconfigure`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        // gainDb defaults to 0. The processor stays active (bit-exact pass-through) so a later
        // setGainDb from the UI takes effect without waiting for a sink reconfigure.
        assertTrue("0 dB on PCM 16-bit must stay active", p.isActive)
    }

    @Test
    fun `queueInput is a bit-exact pass-through at zero gainDb`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        val samples = shortArrayOf(0, 1, -1, 1234, -1234, Short.MAX_VALUE, Short.MIN_VALUE)
        assertEquals(samples.toList(), processSamples(p, samples).toList())
    }

    @Test
    fun `isActive true when gainDb non-zero and encoding is PCM 16-bit`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        p.setGainDb(6f)
        assertTrue("+6 dB on PCM_16BIT should be active", p.isActive)
        p.setGainDb(-3f)
        assertTrue("-3 dB on PCM_16BIT should be active", p.isActive)
    }

    @Test
    fun `queueInput doubles magnitude at plus 6 dB within 1 percent`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        p.setGainDb(6f)
        // 10^(6/20) = 1.9952623... Choose magnitudes well below clipping.
        val inputs = shortArrayOf(1000, -1000, 500, -2500, 0)
        val out = processSamples(p, inputs)
        val expectedRatio = 1.9952623
        for (i in inputs.indices) {
            if (inputs[i].toInt() == 0) {
                assertEquals(0, out[i].toInt())
                continue
            }
            val actualRatio = out[i].toDouble() / inputs[i].toDouble()
            assertTrue(
                "sample $i: ratio=$actualRatio expected~=$expectedRatio",
                abs(actualRatio - expectedRatio) / expectedRatio < 0.01,
            )
        }
    }

    @Test
    fun `queueInput halves magnitude at minus 6 dB within 1 percent`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        p.setGainDb(-6f)
        // 10^(-6/20) = 0.5011872...
        val inputs = shortArrayOf(10_000, -10_000, 8_000, -4_000)
        val out = processSamples(p, inputs)
        val expectedRatio = 0.5011872
        for (i in inputs.indices) {
            val actualRatio = out[i].toDouble() / inputs[i].toDouble()
            assertTrue(
                "sample $i: ratio=$actualRatio expected~=$expectedRatio",
                abs(actualRatio - expectedRatio) / expectedRatio < 0.01,
            )
        }
    }

    @Test
    fun `queueInput clips to Short MAX_VALUE rather than wrapping at plus 12 dB`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format())
        p.setGainDb(12f)
        // 10^(12/20) ~= 3.98. Any input |x| >= ~8230 scales beyond Short.MAX_VALUE.
        val inputs = shortArrayOf(
            Short.MAX_VALUE,
            (Short.MAX_VALUE / 2).toShort(),
            Short.MIN_VALUE,
            (Short.MIN_VALUE / 2).toShort(),
            20_000.toShort(),
            (-20_000).toShort(),
        )
        val out = processSamples(p, inputs)
        // MAX_VALUE * 3.98 would overflow signed-16 → must clip to MAX_VALUE (not wrap negative).
        assertEquals(Short.MAX_VALUE, out[0])
        // Min/2 * 3.98 ~= -65190 < Short.MIN_VALUE → must clip to MIN_VALUE.
        assertEquals(Short.MIN_VALUE, out[2])
        // No sign-flip / wrap: anything that exceeds positive range stays positive.
        for ((i, sample) in inputs.withIndex()) {
            if (sample > 0) {
                assertTrue(
                    "positive input must produce non-negative output, got out[$i]=${out[i]}",
                    out[i] >= 0,
                )
            } else if (sample < 0) {
                assertTrue(
                    "negative input must produce non-positive output, got out[$i]=${out[i]}",
                    out[i] <= 0,
                )
            }
        }
        // Sanity: out[4] (20_000 * ~3.98 = ~79600) clips to MAX.
        assertEquals(Short.MAX_VALUE, out[4])
        assertEquals(Short.MIN_VALUE, out[5])
    }

    @Test
    fun `stereo interleaved samples are both scaled`() {
        val p = VolumeGainAudioProcessor()
        p.configureAndFlush(pcm16Format(channelCount = 2))
        p.setGainDb(6f)
        // Interleaved L,R,L,R — the processor doesn't care about channels; it scales every sample.
        val inputs = shortArrayOf(1000, 2000, -1500, -3000)
        val out = processSamples(p, inputs)
        val expectedRatio = 1.9952623
        for (i in inputs.indices) {
            val actualRatio = out[i].toDouble() / inputs[i].toDouble()
            assertTrue(
                "channel-interleaved sample $i: ratio=$actualRatio expected~=$expectedRatio",
                abs(actualRatio - expectedRatio) / expectedRatio < 0.01,
            )
        }
        // Left and right should not collide / cross.
        assertNotEquals(out[0], out[1])
    }
}
