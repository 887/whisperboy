package com.eight87.whisperboy.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Owns the singleton [ExoPlayer] for the app. Built once in [com.eight87.whisperboy.AppGraph],
 * released on app exit.
 *
 * Key configuration:
 * - Audio attributes: USAGE_MEDIA + CONTENT_TYPE_SPEECH (audiobook-correct; affects routing
 *   and equalisation on devices that distinguish music from voice content).
 * - `handleAudioFocus = true` — Media3 manages focus requests / ducking / pause-on-loss
 *   internally; no manual [android.media.AudioFocusRequest] needed.
 * - `setHandleAudioBecomingNoisy(true)` — auto-pause on headphone unplug.
 * - `setWakeMode(WAKE_MODE_LOCAL)` — keep CPU alive during local playback (no network wake).
 * - Audio-only renderers via [OnlyAudioRenderersFactory] — no video pipeline.
 *
 * Phase J — exposes [volumeGain] and [exoPlayer]. The session-callback layer needs the concrete
 * [ExoPlayer] (not just the [androidx.media3.common.Player] facet) to call
 * `setSkipSilenceEnabled`. [volumeGain] is the same instance handed to
 * [OnlyAudioRenderersFactory] so the session callback's `setGainDb` flow can just call
 * `volumeGain.setGainDb(...)` and the next audio frame picks up the new multiplier.
 */
@OptIn(UnstableApi::class)
class PlayerHolder(context: Context) {

    val volumeGain: VolumeGainAudioProcessor = VolumeGainAudioProcessor()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, OnlyAudioRenderersFactory(context, volumeGain))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** Back-compat alias — most existing callers want the [androidx.media3.common.Player] facet. */
    val player: Player get() = exoPlayer

    fun release() {
        exoPlayer.release()
    }
}
