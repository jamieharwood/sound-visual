# Sound Visual

An Android app that listens through the microphone and draws the sound as a live
**3D matrix grid of bars**:

| Axis | Meaning |
|---|---|
| **X** | Frequency – 64 log-spaced bands from 30 Hz to 16 kHz |
| **Y** | Volume – bar height and colour (blue → red), in dBFS (-90 to -10) |
| **Z** | Time – the newest spectrum is the front row; older rows scroll back (~1.5 s of history) |

A readout in the corner shows the dominant (peak) frequency and the overall RMS level.

## Controls

- **Drag** to orbit the camera
- **Pinch** to zoom
- **Tap** to pause/resume
- **Double-tap** to reset the view

## How it works

- `AudioAnalyzer` – records 44.1 kHz mono PCM with `AudioRecord` (using the `UNPROCESSED`
  source when the device supports it, otherwise `VOICE_RECOGNITION`, to avoid automatic gain
  control flattening the volume axis). A 2048-sample Hann-windowed FFT runs every 1024 samples
  (~43 rows/second) and pushes each row into a ring buffer.
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
