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
 * Records from the microphone on a background thread and keeps a rolling
 * history of band levels: [historyRows] rows x [bandCount] columns.
 * Row 0 of a [snapshot] is always the newest.
 */
class AudioAnalyzer(
    private val context: Context,
    val bandCount: Int = 64,
    val historyRows: Int = 64,
) {
    val sampleRate = 44_100
    val fftSize = 2048
    val hopSize = 1024

    val bands = SpectrumBands(sampleRate, fftSize, bandCount)

    /** New history rows per second for the current source. */
    @Volatile private var rowsPerSecond = sampleRate.toFloat() / hopSize

    /** dBFS mapped to the bottom / top of a bar for the current source. */
    @Volatile var floorDb = bands.floorDb; private set
    @Volatile var ceilDb = bands.ceilDb; private set

    /**
     * True when bar height is relative to the loudest recent band ([floorDb]..[ceilDb] are
     * then "dB below peak"). Used for Phone output, whose Visualizer data is gain-normalised
     * by Android, so absolute dBFS would just pin every bar to the top.
     */
    @Volatile var relativeLevels = false; private set

    /** User level adjustment in dB, added to every band before it's mapped to bar height. */
    @Volatile var gainDb = 0f

    /** Reference (loudest recent band) for relative levels; falls slowly, rises instantly. */
    private var refDb = MIN_REF_DB

    /** Seconds of audio covered by the history (front row to back row) for the current source. */
    val historySeconds: Float get() = historyRows / rowsPerSecond

    private val lock = Any()
    private val ring = FloatArray(bandCount * historyRows)
    private var head = 0 // index of newest row in ring
    private val smoothed = FloatArray(bandCount)

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
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val bufferBytes = maxOf(minBuf, hopSize * 2 * 4)
        val record = try {
            when (source) {
                is CaptureSource.Microphone -> AudioRecord(
                    pickMicSource(),
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                is CaptureSource.Playback -> buildPlaybackRecord(source, bufferBytes) ?: return false
                CaptureSource.PhoneOutput -> return false // handled by startVisualizer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed for ${source.label}", e); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized for ${source.label}")
            record.release(); return false
        }
        Log.i(TAG, "capture started: ${source.label}" +
            (if (source is CaptureSource.Playback) " (uid=${source.uid ?: "all"})" else ""))

        lastSoundAtMs = SystemClock.elapsedRealtime()
        rowsPerSecond = sampleRate.toFloat() / hopSize
        floorDb = bands.floorDb
        ceilDb = bands.ceilDb
        relativeLevels = false
        running = true
        thread = Thread({ loop(record, source) }, "audio-analyzer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun buildPlaybackRecord(source: CaptureSource.Playback, bufferBytes: Int): AudioRecord? {
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
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
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
                        processBlock(vizBands, samples, CaptureSource.PhoneOutput, relative = true)
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
        floorDb = -RELATIVE_SPAN_DB
        ceilDb = 0f
        relativeLevels = true
        refDb = MIN_REF_DB
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

    /** Analyse one block and push a history row. Called from the capture thread. */
    private fun processBlock(
        b: SpectrumBands,
        samples: FloatArray,
        source: CaptureSource,
        relative: Boolean = false,
    ) {
        if (paused) return
        b.process(samples)
        if (relative) {
            var loudest = b.floorDb
            for (db in b.bandDb) if (db > loudest) loudest = db
            refDb = maxOf(loudest, refDb - REF_RELEASE_DB_PER_ROW, MIN_REF_DB)
        }
        peakHz = b.peakHz
        rmsDb = b.rmsDb
        val now = SystemClock.elapsedRealtime()
        if (rmsDb > b.floorDb + 3f) lastSoundAtMs = now
        if (now - lastLogMs > 5000) {
            lastLogMs = now
            Log.d(TAG, "${source.label}: rms=%.1f dBFS peak=%.0f Hz".format(rmsDb, peakHz))
        }
        synchronized(lock) {
            head = (head + 1) % historyRows
            val base = head * bandCount
            for (i in 0 until bandCount) {
                val db = b.bandDb[i] + gainDb
                val level = if (relative) {
                    ((db - (refDb - RELATIVE_SPAN_DB)) / RELATIVE_SPAN_DB).coerceIn(0f, 1f)
                } else {
                    b.dbToLevel(db)
                }
                // Instant attack, gentle release so bars don't flicker.
                val s = maxOf(level, smoothed[i] - RELEASE_PER_HOP)
                smoothed[i] = s
                ring[base + i] = s
            }
        }
    }

    @Volatile private var lastLogMs = 0L

    /** Copy the history into [out] (size bandCount * historyRows), newest row first. */
    fun snapshot(out: FloatArray) {
        synchronized(lock) {
            for (r in 0 until historyRows) {
                val src = ((head - r + historyRows) % historyRows) * bandCount
                System.arraycopy(ring, src, out, r * bandCount, bandCount)
            }
        }
    }

    private fun pickMicSource(): Int {
        // UNPROCESSED / VOICE_RECOGNITION avoid AGC, which would flatten the volume axis.
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun loop(record: AudioRecord, source: CaptureSource) {
        val pcm = ShortArray(hopSize)
        val window = FloatArray(fftSize)
        var lost = false
        try {
            record.startRecording()
            Log.i(TAG, "recording state=${record.recordingState} routed=${record.routedDevice?.type}")
            while (running) {
                var read = 0
                while (read < hopSize && running) {
                    val n = record.read(pcm, read, hopSize - read)
                    if (n < 0) { Log.e(TAG, "read error $n on ${source.label}"); lost = true; running = false; break }
                    read += n
                }
                if (!running) break

                // Slide the analysis window by one hop.
                System.arraycopy(window, hopSize, window, 0, fftSize - hopSize)
                for (i in 0 until hopSize) window[fftSize - hopSize + i] = pcm[i] / 32768f

                processBlock(bands, window, source)
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
        private const val VISUALIZER_FLOOR_DB = -80f
        private const val VISUALIZER_CEIL_DB = 0f
        /** dB shown from the bottom to the top of the grid in relative mode. */
        private const val RELATIVE_SPAN_DB = 40f
        /** Lowest reference, so near-silence stays flat instead of being scaled up. */
        private const val MIN_REF_DB = VISUALIZER_FLOOR_DB + RELATIVE_SPAN_DB
        /** ~3 dB/s at 20 rows/s: quiet passages slowly fill the height again. */
        private const val REF_RELEASE_DB_PER_ROW = 0.15f
    }
}
