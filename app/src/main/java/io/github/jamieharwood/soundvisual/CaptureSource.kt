package io.github.jamieharwood.soundvisual

import android.media.projection.MediaProjection

/** Where the analyzer gets its audio from. */
sealed class CaptureSource(val label: String) {
    /** The device microphone. */
    data object Microphone : CaptureSource("Microphone")

    /**
     * Everything the phone is playing, read with the Visualizer API on the output mix.
     * Lower resolution than [Playback], but it also sees apps that block capture (e.g. Spotify).
     */
    data object PhoneOutput : CaptureSource("Phone output")

    /**
     * Audio other apps are playing (Android 10+), captured via [projection].
     * [uid] limits capture to one app; null captures all capturable media/game audio.
     */
    class Playback(val projection: MediaProjection, val uid: Int?, label: String) : CaptureSource(label)
}
