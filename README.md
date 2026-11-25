# Sound Visual

An Android app (with a Wear OS companion and an Ubuntu desktop version) that listens through
the microphone and draws the sound as a live **3D matrix grid of bars**:

| Axis | Meaning |
|---|---|
| **X** | Frequency – 64 log-spaced bands from 30 Hz to 16 kHz |
| **Y** | Volume – bar height and colour, in dBFS: the **left channel rises above** the centre plane (+) and the **right channel hangs below** it (−) |
| **Z** | Time – the newest spectrum is the front row; older rows scroll back (~1.5 s of history) |

A readout in the corner shows the dominant (peak) frequency, the overall RMS level, and whether
the source is really stereo. Drag (or tilt the phone in Tilt view) below the centre plane to look
up at the right channel.

**Stereo by source:** the microphone records true stereo on phones with two mics (using the
camcorder input, as stereo video does); app capture records the stereo mix; **Phone output is
mono** (Android's Visualizer only provides a mono mix), so both halves mirror each other. If a
device delivers "stereo" that's really one mic copied twice, the readout says Mono.

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

Level, Auto (per source), the colour scheme, the View mode and whether the panel is hidden are
all remembered.

## Controls

- **Drag** to orbit the camera
- **Pinch** to zoom
- **Tap** to pause/resume
- **Double-tap** to reset the view
- **View ▾** (top right) chooses how the camera moves:
  - **Touch** – drag and pinch only (default)
  - **Auto view** – the camera tours the graph by itself, slowly orbiting, tilting, zooming and
    panning between random viewpoints; touching takes over and the tour resumes 8 s later
  - **Tilt view** – move the phone to look around, as if through a window: turn it left/right to
    swing around the graph, tip it back (as if looking down at a table) to rise above it, twist
    it like a steering wheel and the graph stays level with the room, and
    pull the phone towards you to zoom in or push it away to zoom out (2× per 20 cm). The
    pose you're holding when you switch it on is the centre; drag to adjust, pinch to change the
    base zoom, double-tap to re-centre on your current pose.
    - Turning uses the game rotation vector sensor (200 Hz).
    - Push/pull zoom tracks the phone's real position with **ARCore** (rear camera + motion
      sensors, metric and drift-free). It asks for camera permission the first time and needs
      Google Play Services for AR; without either, Tilt view still turns but doesn't zoom.
      Camera frames are only used for tracking – never shown, saved or sent anywhere – and the
      camera only runs while Tilt view is open. The rear camera needs to see the room; the
      readout's **Zoom** line says when tracking pauses (too dark, camera covered, moving too
      fast). Zoom stops at a closest distance that keeps the camera outside the graph.
- **Source ▾** to change the sound source, **Colours ▾** for the colour scheme
- **Level** panel to raise/lower the bars or turn on **Auto**; **✕** hides it

## Wear OS companion

The `wear` module is a Wear OS app that mirrors whatever the phone app is showing – including
Spotify via Phone output – on your watch.

- Open Sound Visual on the phone, then on the watch. The watch asks the phone for data every
  couple of seconds while it's on screen, and the phone streams the graph (32 bands per channel,
  ~20 rows/s, in ~10 small messages a second) over the Wearable Data Layer, along with the
  colour scheme and whether the source is stereo. Nothing is sent while the watch isn't looking.
- **Tilt view** (default): tilt and turn your wrist to look around the graph. **Tap** to switch
  to Auto view or Touch; **double-tap** to reset; turn the **crown / rotating bezel** to zoom.
- Open Sound Visual on the phone once; after that you can lock the phone or switch apps. If
  a watch is watching when the phone app leaves the screen, it keeps listening in the
  background (a "Sending the graph to your watch" notification shows) and stops 60 s after the
  watch stops asking. Android doesn't allow starting the microphone from the background, so
  once it has stopped, reopen the phone app; until then the watch shows "Open Sound Visual on
  your phone".
- Both apps share the renderer and tilt control via the `shared` module (spectrum maths,
  palettes and the watch protocol live in the plain-Kotlin `core` module), and use the same
  application id and signing key (required by the Data Layer).

## Ubuntu desktop version

The `desktop` module is the same grid in a desktop window (Ubuntu 22.04+ or any Linux with
PipeWire and OpenGL 3.3). It shares the spectrum maths and palettes with the phone app through
`core`.

**Sound sources** (Source ▾ or `S`):

- **Microphone**: the default input device.
- **Computer output (all apps)**: everything the computer is playing, taken from the default
  output's monitor. No setup needed.
- **One app**: any app that's playing right now (a browser, Spotify, a game). Only that app is
  captured. If it stops playing, the source falls back to Computer output.

Audio comes from PipeWire's `pw-record` (package `pipewire-bin`, installed by default on
Ubuntu 22.10+). Without it, only the microphone works, through Java Sound.

**Controls:** left-drag to rotate, right-drag to pan, scroll to zoom, double-click or `R` to
reset. Use **View** (`V`) to switch to Auto view's random tour. **Colours** (`C`) holds the
same palettes as the phone. The **Level** panel has Auto (`A`), a slider (`+` / `−`) and ✕ to
hide it (`L`). `Space` pauses, `F` / `F11` goes full screen, and `H` lists every key. Choices
are remembered between runs.

Build and run:

```bash
./gradlew :desktop:run          # run from source
./gradlew :desktop:deb          # desktop/build/jpackage/sound-visual_1.0.0_amd64.deb
sudo apt install ./desktop/build/jpackage/sound-visual_1.0.0_amd64.deb
```

The `.deb` bundles its own Java runtime, installs to `/opt/sound-visual` and adds a
"sound-visual" entry to the app menu. Building it needs a full JDK 21 (for `jpackage`),
`dpkg-deb` and `fakeroot`.

## How it works

- `AudioAnalyzer` – for the microphone and capture sources, records 44.1 kHz mono PCM with
  `AudioRecord` (mic uses the `UNPROCESSED` source when supported, otherwise `VOICE_RECOGNITION`,
  to avoid automatic gain control flattening the volume axis). A 2048-sample Hann-windowed FFT
  runs every 1024 samples (~43 rows/second) and pushes each row into a ring buffer. For Phone
  output it reads 1024-sample waveforms from `Visualizer(0)` instead.
- `CaptureService` – the `mediaProjection` foreground service Android requires while capturing
  other apps' audio.
- `SpectrumBands` / `Fft` (in `core`) – pure-Kotlin radix-2 FFT and log-band grouping (unit-tested).
- `GridRenderer` – OpenGL ES 3.0. All 64 × 64 bars are drawn in a single instanced draw call;
  per-bar levels are uploaded each frame.
- `LabelOverlayView` – projects axis tick positions from 3D and draws the labels with Canvas.

## Build

Requires the Android SDK (compileSdk 35) and a full JDK 17+.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test            # FFT / band / protocol tests
./gradlew installDebug         # install on a connected device
./gradlew :wear:assembleDebug  # Wear OS companion: wear/build/outputs/apk/debug/wear-debug.apk
```

Min Android version: 8.0 (API 26), OpenGL ES 3.0. The app asks for microphone permission on
first launch.
