package com.eight87.whisperboy.playback

/**
 * Phase N.5 — custom session commands surfaced to Android Auto as action buttons
 * + accepted from any controller (the phone-side player surface too, eventually).
 *
 * The strings are stable identifiers in the session-command namespace; Auto's UI
 * picks them up via [androidx.media3.session.SessionCommand] registration in
 * [WhisperboyLibrarySessionCallback.onConnectAsync]. Bundle keys live alongside so
 * the parse / dispatch sides agree on the payload shape.
 *
 * Real handler implementations gate on Phase G (sleep timer) + Phase J (skip
 * silence / gain) landing — for now the callback registers + accepts the
 * commands and either dispatches to the wired backend (timer + speed are wired
 * today) or returns success with a logged no-op so the car UI doesn't show an
 * error toast (Phase J wires the rest).
 */
internal object CustomCommands {

    /** Action id (Phase G — wired to [SleepTimerCommands.arm] / [SleepTimerCommands.cancel]). */
    const val SET_SLEEP_TIMER = "com.eight87.whisperboy.SET_SLEEP_TIMER"

    /** Action id (wired today via [androidx.media3.common.Player.setPlaybackSpeed]). */
    const val SET_SPEED = "com.eight87.whisperboy.SET_SPEED"

    /** Action id (Phase J — wired to `ExoPlayer.skipSilenceEnabled` on the session side). */
    const val SET_SKIP_SILENCE = "com.eight87.whisperboy.SET_SKIP_SILENCE"

    /** Action id (Phase J — wired to [VolumeGainAudioProcessor.setGainDb] on the session side). */
    const val SET_GAIN_DB = "com.eight87.whisperboy.SET_GAIN_DB"

    /** Bundle keys. */
    const val EXTRA_DURATION_MS = "durationMs"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_GAIN_DB = "gainDb"
}
