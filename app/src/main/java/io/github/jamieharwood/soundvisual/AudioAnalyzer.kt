package io.github.jamieharwood.soundvisual

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

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

    /** Seconds of audio covered by the history (front row to back row). */
    val historySeconds: Float get() = historyRows * hopSize.toFloat() / sampleRate

    private val lock = Any()
    private val ring = FloatArray(bandCount * historyRows)
    private var head = 0 // index of newest row in ring
    private val smoothed = FloatArray(bandCount)

    @Volatile var peakHz = 0f; private set
    @Volatile var rmsDb = bands.floorDb; private set
    @Volatile var paused = false

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                pickSource(),
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, hopSize * 2 * 4),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }

        running = true
        thread = Thread({ loop(record) }, "audio-analyzer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    /** Copy the history into [out] (size bandCount * historyRows), newest row first. */
    fun snapshot(out: FloatArray) {
        synchronized(lock) {
            for (r in 0 until historyRows) {
                val src = ((head - r + historyRows) % historyRows) * bandCount
                System.arraycopy(ring, src, out, r * bandCount, bandCount)
            }
        }
    }

    private fun pickSource(): Int {
        // UNPROCESSED / VOICE_RECOGNITION avoid AGC, which would flatten the volume axis.
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun loop(record: AudioRecord) {
        val pcm = ShortArray(hopSize)
        val window = FloatArray(fftSize)
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < hopSize && running) {
                    val n = record.read(pcm, read, hopSize - read)
                    if (n < 0) { Log.e(TAG, "read error $n"); running = false; break }
                    read += n
                }
                if (!running) break

                // Slide the analysis window by one hop.
                System.arraycopy(window, hopSize, window, 0, fftSize - hopSize)
                for (i in 0 until hopSize) window[fftSize - hopSize + i] = pcm[i] / 32768f

                if (paused) continue
                bands.process(window)
                peakHz = bands.peakHz
                rmsDb = bands.rmsDb

                synchronized(lock) {
                    head = (head + 1) % historyRows
                    val base = head * bandCount
                    for (b in 0 until bandCount) {
                        val level = bands.dbToLevel(bands.bandDb[b])
                        // Instant attack, gentle release so bars don't flicker.
                        val s = maxOf(level, smoothed[b] - RELEASE_PER_HOP)
                        smoothed[b] = s
                        ring[base + b] = s
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop failed", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    companion object {
        private const val TAG = "AudioAnalyzer"
        private const val RELEASE_PER_HOP = 0.06f
    }
}
