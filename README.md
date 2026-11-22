# Sound Visual

An Android app that listens through the microphone and draws the sound as a live
**3D matrix grid of bars**:

| Axis | Meaning |
|---|---|
| **X** | Frequency – 64 log-spaced bands from 30 Hz to 16 kHz |
| **Y** | Volume – bar height and colour (blue → red), in dBFS (-90 to -10) |
| **Z** | Time – the newest spectrum is the front row; older rows scroll back (~1.5 s of history) |

A readout in the corner shows the dominant (peak) frequency and the overall RMS level.

## Sound sources

Tap **Source ▾** (top right) to choose what to visualise:

| Source | What it hears | Notes |
|---|---|---|
| **Microphone** | The room | Real dBFS scale. Default. |
| **Phone output** | Everything the phone is playing, **including Spotify** | Uses Android's `Visualizer` on the output mix: 8-bit, ~20 rows/s (~3.2 s of history). Android normalises this signal, so it shows a 40 dB window and starts with **Auto** level on. No consent prompt. |
| **Capture one app…** | A single app's audio (Android 10+) | Higher quality (16-bit, ~43 rows/s) via `AudioPlaybackCapture`. Needs the screen-recording consent prompt each time and shows a notification while running. Keeps capturing in the background so you can switch to the other app and press play. |
| **Capture all apps** | All capturable media/game audio (Android 10+) | As above, without the per-app filter. |

**Spotify (and some other music apps) can't be captured.** They mark their audio streams with
`AUDIO_FLAG_NO_MEDIA_PROJECTION`, so Android routes them around any capture – use **Phone
output** for these. Capture only sees streams an app opens *after* capture starts.

The last Microphone / Phone output choice is remembered between launches (capture sources need
fresh consent, so they aren't).

## Level, Auto level and colours

- **Level panel** (bottom right): the slider shifts every bar by −30 … +30 dB, and the volume
  axis labels move with it so they stay accurate.
- **Auto** (on the Level panel): the app picks the level itself, keeping the loudest band of
  the last few seconds just under the top of the grid. It reacts instantly to louder sound and
  eases back up over quiet passages (~3 dB/s). On by default for Phone output.
- **✕** collapses the panel to a small **Level ▴** chip; tap the chip to bring it back.
- **Colours ▾** (top right): Spectrum, Fire, Ocean, Neon, Matrix, Sunset or Mono.

Level, Auto (per source), the colour scheme and whether the panel is hidden are all remembered.

## Controls

- **Drag** to orbit the camera
- **Pinch** to zoom
- **Tap** to pause/resume
- **Double-tap** to reset the view
- **Source ▾** to change the sound source, **Colours ▾** for the colour scheme
- **Level** panel to raise/lower the bars or turn on **Auto**; **✕** hides it

## How it works

- `AudioAnalyzer` – for the microphone and capture sources, records 44.1 kHz mono PCM with
  `AudioRecord` (mic uses the `UNPROCESSED` source when supported, otherwise `VOICE_RECOGNITION`,
  to avoid automatic gain control flattening the volume axis). A 2048-sample Hann-windowed FFT
  runs every 1024 samples (~43 rows/second) and pushes each row into a ring buffer. For Phone
  output it reads 1024-sample waveforms from `Visualizer(0)` instead.
- `CaptureService` – the `mediaProjection` foreground service Android requires while capturing
  other apps' audio.
- `SpectrumBands` / `Fft` – pure-Kotlin radix-2 FFT and log-band grouping (unit-tested).
- `GridRenderer` – OpenGL ES 3.0. All 64 × 64 bars are drawn in a single instanced draw call;
  per-bar levels are uploaded each frame.
- `LabelOverlayView` – projects axis tick positions from 3D and draws the labels with Canvas.

## Build

Requires the Android SDK (compileSdk 35) and a full JDK 17+.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # FFT / band tests
./gradlew installDebug         # install on a connected device
```

Min Android version: 8.0 (API 26), OpenGL ES 3.0. The app asks for microphone permission on
first launch.
