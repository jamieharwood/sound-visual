package io.github.jamieharwood.soundvisual

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures audio on a background thread and keeps a rolling history of band levels for each
 * stereo channel: [historyRows] rows x [bandCount] columns, left and right. Row 0 of a
 * [snapshot] is always the newest. Mono sources fill both channels with the same data.
 */
class AudioAnalyzer(
    private val context: Context,
    val bandCount: Int = 64,
    val historyRows: Int = 64,
) {
    val sampleRate = 44_100
    val fftSize = 2048
    val hopSize = 1024

    /** Left channel analysis (also used for the axis layout). */
    val bands = SpectrumBands(sampleRate, fftSize, bandCount)
    private val bandsRight = SpectrumBands(sampleRate, fftSize, bandCount)

    /** True when the current source really has separate left/right channels. */
    @Volatile var stereo = false; private set

    /** New history rows per second for the current source. */
    @Volatile private var rowsPerSecond = sampleRate.toFloat() / hopSize

    /** dBFS mapped to the bottom / top of a bar for the current source. */
    @Volatile var floorDb = bands.floorDb; private set
    @Volatile var ceilDb = bands.ceilDb; private set

    /** Manual level adjustment in dB, added to every band before it's mapped to bar height. */
    @Volatile var gainDb = 0f

    /**
     * Auto level: instead of [gainDb], continuously pick the gain that puts the loudest
     * recent band just under the top of the grid. It rises at once on louder sound and eases
     * back up when things get quiet.
     */
    @Volatile var autoLevel = false

    /** Gain actually applied to the latest row (manual or auto), for the UI and axis labels. */
    @Volatile var effectiveGainDb = 0f; private set

    /** Loudest recent band for auto level; jumps up instantly, falls at [AUTO_RELEASE_DB_PER_SEC]. */
    private var refDb = -200f

    /** Seconds of audio covered by the history (front row to back row) for the current source. */
    val historySeconds: Float get() = historyRows / rowsPerSecond

    private val lock = Any()
    private val ringLeft = FloatArray(bandCount * historyRows)
    private val ringRight = FloatArray(bandCount * historyRows)
    private var head = 0 // index of newest row in the rings
    private val smoothedLeft = FloatArray(bandCount)
    private val smoothedRight = FloatArray(bandCount)

    @Volatile var peakHz = 0f; private set
    @Volatile var rmsDb = bands.floorDb; private set
    @Volatile var paused = false

    /** [SystemClock.elapsedRealtime] of the last block that wasn't digital silence. */
    @Volatile var lastSoundAtMs = 0L; private set

    /**
     * Called (on the analyzer thread) when capture ends without [stop] being called,
     * e.g. the audio server dropped the recorder. Not called for a normal [stop].
     */
    var onCaptureLost: ((CaptureSource) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var visualizer: Visualizer? = null
    private var visualizerThread: HandlerThread? = null

    /** Start capturing from [source]. Returns false if the recorder could not be created. */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start(source: CaptureSource): Boolean {
        if (running) return true
        if (source is CaptureSource.PhoneOutput) return startVisualizer()
        // Try stereo first (two mics, or a stereo app mix); fall back to mono.
        var record: AudioRecord? = null
        var channels = 0
        for (mask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val ch = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val bufferBytes = maxOf(minBuf, hopSize * 2 * ch * 4)
            val r = try {
                when (source) {
                    is CaptureSource.Microphone -> AudioRecord(
                        pickMicSource(ch == 2), sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
                    )
                    is CaptureSource.Playback -> buildPlaybackRecord(source, mask, bufferBytes)
                    CaptureSource.PhoneOutput -> null // handled by startVisualizer()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for ${source.label} ($ch ch)", e); null
            } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) { record = r; channels = ch; break }
            r.release()
        }
        if (record == null) {
            Log.e(TAG, "AudioRecord not initialized for ${source.label}")
            return false
        }
        stereo = channels == 2
        Log.i(TAG, "capture started: ${source.label} (${if (stereo) "stereo" else "mono"})" +
            (if (source is CaptureSource.Playback) " (uid=${source.uid ?: "all"})" else ""))

        lastSoundAtMs = SystemClock.elapsedRealtime()
        rowsPerSecond = sampleRate.toFloat() / hopSize
        floorDb = bands.floorDb
        ceilDb = bands.ceilDb
        refDb = -200f
        running = true
        val rec = record
        val ch = channels
        thread = Thread({ loop(rec, ch, source) }, "audio-analyzer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun buildPlaybackRecord(source: CaptureSource.Playback, channelMask: Int, bufferBytes: Int): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val config = AudioPlaybackCaptureConfiguration.Builder(source.projection).apply {
            if (source.uid != null) {
                addMatchingUid(source.uid)
            } else {
                addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                addMatchingUsage(AudioAttributes.USAGE_GAME)
                addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            }
        }.build()
        return AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    fun stop() {
        if (running) Log.i(TAG, "capture stop requested")
        running = false
        thread?.join(500)
        thread = null
        stopVisualizer()
    }

    /**
     * Read the output mix with [Visualizer] (session 0). Its waveform is 8-bit and arrives
     * ~20 times a second, so each capture becomes one history row.
     */
    private fun startVisualizer(): Boolean {
        val ht = HandlerThread("visualizer").apply { start() }
        val ready = CountDownLatch(1)
        var created: Visualizer? = null
        Handler(ht.looper).post {
            try {
                val v = Visualizer(0)
                v.enabled = false
                v.captureSize = Visualizer.getCaptureSizeRange()[1]
                v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                val n = v.captureSize
                // Visualizer output is normalised to near full scale, so use a higher range
                // than the mic's or every bar sits at the top.
                val vizBands = SpectrumBands(
                    v.samplingRate / 1000, n, bandCount,
                    floorDb = VISUALIZER_FLOOR_DB, ceilDb = VISUALIZER_CEIL_DB,
                )
                val samples = FloatArray(n)
                // The listener is delivered on this thread's looper.
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(viz: Visualizer, wave: ByteArray, rate: Int) {
                        if (!running) return
                        val m = minOf(n, wave.size)
                        for (i in 0 until m) samples[i] = ((wave[i].toInt() and 0xFF) - 128) / 128f
                        processBlock(vizBands, samples, null, null, CaptureSource.PhoneOutput)
                    }
                    override fun onFftDataCapture(viz: Visualizer, fft: ByteArray, rate: Int) = Unit
                }, Visualizer.getMaxCaptureRate(), true, false)
                v.enabled = true
                created = v
                Log.i(TAG, "capture started: Phone output (visualizer ${n} samples @ ${v.samplingRate / 1000} Hz, " +
                    "${Visualizer.getMaxCaptureRate() / 1000f}/s)")
            } catch (e: Exception) {
                Log.e(TAG, "Visualizer init failed", e)
            }
            ready.countDown()
        }
        ready.await(2, TimeUnit.SECONDS)
        val v = created
        if (v == null) { ht.quitSafely(); return false }
        visualizer = v
        visualizerThread = ht
        rowsPerSecond = Visualizer.getMaxCaptureRate() / 1000f
        stereo = false // the Visualizer only provides a mono mix
        // Visualizer data is gain-normalised by Android, so music sits near full scale:
        // show a narrower 40 dB window at the top instead of the mic's 80 dB.
        floorDb = VISUALIZER_DISPLAY_FLOOR_DB
        ceilDb = 0f
        refDb = -200f
        lastSoundAtMs = SystemClock.elapsedRealtime()
        running = true
        return true
    }

    private fun stopVisualizer() {
        val v = visualizer ?: return
        val ht = visualizerThread
        visualizer = null
        visualizerThread = null
        val done = CountDownLatch(1)
        val posted = ht != null && Handler(ht.looper).post {
            try { v.enabled = false } catch (_: Exception) {}
            v.release()
            done.countDown()
        }
        if (posted) done.await(1, TimeUnit.SECONDS) else v.release()
        ht?.quitSafely()
        Log.i(TAG, "capture ended: Phone output")
    }

    /**
     * Analyse one block per channel and push a history row. [right]/[rightSamples] null means
     * a mono source: the left result fills both channels. Called from the capture thread.
     */
    private fun processBlock(
        left: SpectrumBands,
        leftSamples: FloatArray,
        right: SpectrumBands?,
        rightSamples: FloatArray?,
        source: CaptureSource,
    ) {
        if (paused) return
        left.process(leftSamples)
        val r = if (right != null && rightSamples != null) right.also { it.process(rightSamples) } else left
        val floor = floorDb
        val span = ceilDb - floor
        // Track the loudest band in either channel; never let silence drag the reference below
        // mid-range, or auto level would amplify the noise floor up to the top.
        var loudest = left.floorDb
        for (db in left.bandDb) if (db > loudest) loudest = db
        for (db in r.bandDb) if (db > loudest) loudest = db
        refDb = maxOf(loudest, refDb - AUTO_RELEASE_DB_PER_SEC / rowsPerSecond, floor + span * 0.5f)
        val gain = if (autoLevel) {
            (ceilDb - AUTO_HEADROOM_DB - refDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        } else {
            gainDb
        }
        effectiveGainDb = gain
        val louder = if (r.rmsDb > left.rmsDb) r else left
        peakHz = louder.peakHz
        rmsDb = louder.rmsDb
        val now = SystemClock.elapsedRealtime()
        if (rmsDb > left.floorDb + 3f) lastSoundAtMs = now
        if (now - lastLogMs > 5000) {
            lastLogMs = now
            Log.d(TAG, "${source.label}: L=%.1f R=%.1f dBFS peak=%.0f Hz".format(left.rmsDb, r.rmsDb, peakHz))
        }
        synchronized(lock) {
            head = (head + 1) % historyRows
            val base = head * bandCount
            for (i in 0 until bandCount) {
                ringLeft[base + i] = smooth(smoothedLeft, i, (left.bandDb[i] + gain - floor) / span)
                ringRight[base + i] = smooth(smoothedRight, i, (r.bandDb[i] + gain - floor) / span)
            }
        }
    }

    /** Instant attack, gentle release so bars don't flicker. */
    private fun smooth(state: FloatArray, i: Int, raw: Float): Float {
        val s = maxOf(raw.coerceIn(0f, 1f), state[i] - RELEASE_PER_HOP)
        state[i] = s
        return s
    }

    @Volatile private var lastLogMs = 0L

    /**
     * Copy the history into [out] (size 2 * bandCount * historyRows), newest row first:
     * the left channel's rows, then the right channel's.
     */
    fun snapshot(out: FloatArray) {
        val perSide = bandCount * historyRows
        synchronized(lock) {
            for (r in 0 until historyRows) {
                val src = ((head - r + historyRows) % historyRows) * bandCount
                System.arraycopy(ringLeft, src, out, r * bandCount, bandCount)
                System.arraycopy(ringRight, src, out, perSide + r * bandCount, bandCount)
            }
        }
    }

    private fun pickMicSource(stereo: Boolean): Int {
        // CAMCORDER is the source phones use for stereo video, so it's the one that actually
        // combines two microphones; the others usually give one mic copied to both channels.
        if (stereo) return MediaRecorder.AudioSource.CAMCORDER
        // UNPROCESSED / VOICE_RECOGNITION avoid AGC, which would flatten the volume axis.
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun loop(record: AudioRecord, channels: Int, source: CaptureSource) {
        val samplesPerHop = hopSize * channels
        val pcm = ShortArray(samplesPerHop)
        val windowLeft = FloatArray(fftSize)
        val windowRight = FloatArray(fftSize)
        var lost = false
        var identicalBlocks = 0
        try {
            record.startRecording()
            Log.i(TAG, "recording state=${record.recordingState} routed=${record.routedDevice?.type}")
            while (running) {
                var read = 0
                while (read < samplesPerHop && running) {
                    val n = record.read(pcm, read, samplesPerHop - read)
                    if (n < 0) { Log.e(TAG, "read error $n on ${source.label}"); lost = true; running = false; break }
                    read += n
                }
                if (!running) break

                // Slide the analysis windows by one hop; stereo PCM is interleaved L, R, L, R…
                val tail = fftSize - hopSize
                System.arraycopy(windowLeft, hopSize, windowLeft, 0, tail)
                if (channels == 2) {
                    System.arraycopy(windowRight, hopSize, windowRight, 0, tail)
                    var differs = false
                    for (i in 0 until hopSize) {
                        windowLeft[tail + i] = pcm[2 * i] / 32768f
                        windowRight[tail + i] = pcm[2 * i + 1] / 32768f
                        if (pcm[2 * i] != pcm[2 * i + 1]) differs = true
                    }
                    // Some devices deliver "stereo" that is one mic copied twice: report mono.
                    identicalBlocks = if (differs) 0 else identicalBlocks + 1
                    stereo = identicalBlocks < IDENTICAL_BLOCKS_FOR_MONO
                    processBlock(bands, windowLeft, bandsRight, windowRight, source)
                } else {
                    for (i in 0 until hopSize) windowLeft[tail + i] = pcm[i] / 32768f
                    processBlock(bands, windowLeft, null, null, source)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop failed on ${source.label}", e)
            lost = true
            running = false
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            Log.i(TAG, "capture ended: ${source.label} (lost=$lost)")
        }
        if (lost) onCaptureLost?.invoke(source)
    }

    companion object {
        private const val TAG = "AudioAnalyzer"
        private const val RELEASE_PER_HOP = 0.06f
        /** ~0.5 s of bit-identical left/right audio means the "stereo" is really mono. */
        private const val IDENTICAL_BLOCKS_FOR_MONO = 20
        private const val VISUALIZER_FLOOR_DB = -80f
        private const val VISUALIZER_CEIL_DB = 0f
        private const val VISUALIZER_DISPLAY_FLOOR_DB = -40f
        /** Auto level leaves the loudest band this far below the top of the grid. */
        private const val AUTO_HEADROOM_DB = 3f
        /** How fast auto level lets quiet passages grow back to full height. */
        private const val AUTO_RELEASE_DB_PER_SEC = 3f
        const val MIN_GAIN_DB = -30f
        const val MAX_GAIN_DB = 30f
    }
}
