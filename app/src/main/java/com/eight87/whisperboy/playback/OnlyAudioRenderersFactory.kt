package com.eight87.whisperboy.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * RenderersFactory that omits the video pipeline entirely — whisperboy is audio-only.
 *
 * Smaller APK (no video decoder linkage) and no surface management overhead. Voice analog:
 * `voice.core.playback.player.OnlyAudioRenderersFactory`.
 *
 * Phase J.3 also pipes a [VolumeGainAudioProcessor] into the audio sink so per-book volume
 * pre-amp lives inside Media3's audio path (rather than an external `LoudnessEnhancer`). The
 * processor is held by the caller (typically [PlayerHolder]) and its `setGainDb` knob is driven
 * by [PlaybackController] / [WhisperboyLibrarySessionCallback]; this factory just hands the
 * same instance to the [DefaultAudioSink] builder. The processor stays active at 0 dB (a
 * bit-exact pass-through) so later gain changes apply without a sink reconfigure.
 */
@OptIn(UnstableApi::class)
class OnlyAudioRenderersFactory(
    context: Context,
    private val volumeGain: VolumeGainAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Intentionally empty — no video.
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(volumeGain))
            .build()
    }
}
