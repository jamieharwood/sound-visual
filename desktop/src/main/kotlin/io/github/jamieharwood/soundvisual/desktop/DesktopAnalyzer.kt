package io.github.jamieharwood.soundvisual.desktop

import io.github.jamieharwood.soundvisual.SpectrumBands
import io.github.jamieharwood.soundvisual.SpectrumFeed
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Captures audio on a background thread and keeps a rolling history of band levels for each
 * stereo channel, exactly like the phone app's analyzer: row 0 of a [snapshot] is the newest.
 *
 * Audio comes from a recorder process ([AudioBackend]: PipeWire's `pw-record`, or `parec`
 * inside a snap) writing 32-bit float stereo, so the microphone, the computer's output and
 * single apps all work the same way. Without either it falls back to Java Sound for the
 * microphone only.
 */
class DesktopAnalyzer(
    override val bandCount: Int = 64,
    override val historyRows: Int = 64,
) : SpectrumFeed {
    val sampleRate = 48_000
    private val fftSize = 2048
    private val hopSize = 1024

    private val bands = SpectrumBands(sampleRate, fftSize, bandCount)
    private val bandsRight = SpectrumBands(sampleRate, fftSize, bandCount)

    @Volatile var stereo = false; private set
    val rowsPerSecond = sampleRate.toFloat() / hopSize

    override val floorDb get() = bands.floorDb
    override val ceilDb get() = bands.ceilDb
    override val minHz get() = bands.minHz
    override val maxHz get() = bands.maxHz
    override fun bandPosition(hz: Float) = bands.bandPosition(hz)
    override val historySeconds get() = historyRows / rowsPerSecond

    /** Manual level adjustment in dB. */
    @Volatile var gainDb = 0f
    /** Keep the loudest recent band just under the top of the grid instead of using [gainDb]. */
    @Volatile var autoLevel = true
    @Volatile override var effectiveGainDb = 0f; private set
    private var refDb = -200f

    private val lock = Any()
    private val ringLeft = FloatArray(bandCount * historyRows)
    private val ringRight = FloatArray(bandCount * historyRows)
    private var head = 0
    private val smoothedLeft = FloatArray(bandCount)
    private val smoothedRight = FloatArray(bandCount)

    @Volatile var peakHz = 0f; private set
    @Volatile var rmsDb = bands.floorDb; private set
    @Volatile var paused = false
    /** [System.currentTimeMillis] of the last block that wasn't silence. */
    @Volatile var lastSoundAtMs = 0L; private set
    /** [System.currentTimeMillis] when the current source started. */
    @Volatile var startedAtMs = 0L; private set

    /** Called on the capture thread when a source ends without [stop] (e.g. the app closed). */
    @Volatile var onCaptureLost: ((AudioSource) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var line: TargetDataLine? = null

    /** Start capturing [source]. Returns an error message, or null on success. */
    fun start(source: AudioSource): String? {
        stop()
        val input: InputStream
        val channels: Int
        val float32: Boolean
        val backend = AudioBackend.current
        if (backend.available) {
            val cmd = backend.recordCommand(source, sampleRate)
            val proc = try {
                ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            } catch (e: Exception) {
                return "Couldn't start ${cmd.first().substringAfterLast('/')}: ${e.message}"
            }
            process = proc
            input = proc.inputStream
            channels = 2
            float32 = true
        } else {
            if (source != AudioSource.Microphone) return "${source.label} needs PipeWire (pw-record) or PulseAudio (parec)"
            val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
            val l = try {
                AudioSystem.getTargetDataLine(format).apply { open(format, hopSize * 4 * 4); start() }
            } catch (e: Exception) {
                return "No microphone: ${e.message}"
            }
            line = l
            input = object : InputStream() {
                override fun read() = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int) = l.read(b, off, len).let { if (it <= 0 && !l.isOpen) -1 else it }
            }
            channels = 2
            float32 = false
        }
        refDb = -200f
        lastSoundAtMs = System.currentTimeMillis()
        startedAtMs = lastSoundAtMs
        running = true
        thread = Thread({ loop(input, channels, float32, source) }, "audio-analyzer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        return null
    }

    fun stop() {
        running = false
        process?.let { it.destroy(); it.waitFor() }
        process = null
        line?.let { it.stop(); it.close() }
        line = null
        thread?.join(500)
        thread = null
    }

    private fun loop(input: InputStream, channels: Int, float32: Boolean, source: AudioSource) {
        val bytesPerSample = if (float32) 4 else 2
        val raw = ByteArray(hopSize * channels * bytesPerSample)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val data = DataInputStream(input)
        val windowLeft = FloatArray(fftSize)
        val windowRight = FloatArray(fftSize)
        var identicalBlocks = 0
        var lost = false
        try {
            while (running) {
                data.readFully(raw)
                if (!running) break
                val tail = fftSize - hopSize
                System.arraycopy(windowLeft, hopSize, windowLeft, 0, tail)
                System.arraycopy(windowRight, hopSize, windowRight, 0, tail)
                var differs = false
                for (i in 0 until hopSize) {
                    val l: Float
                    val r: Float
                    if (float32) {
                        l = buf.getFloat(i * 8); r = buf.getFloat(i * 8 + 4)
                    } else {
                        l = buf.getShort(i * 4) / 32768f; r = buf.getShort(i * 4 + 2) / 32768f
                    }
                    windowLeft[tail + i] = l
                    windowRight[tail + i] = r
                    if (l != r) differs = true
                }
                // A mono mic copied to both channels shows as identical blocks: report mono.
                identicalBlocks = if (differs) 0 else identicalBlocks + 1
                stereo = identicalBlocks < IDENTICAL_BLOCKS_FOR_MONO
                if (stereo) processBlock(windowLeft, windowRight) else processBlock(windowLeft, null)
            }
        } catch (_: EOFException) {
            lost = running
        } catch (e: Exception) {
            if (running) { System.err.println("sound-visual: capture failed: $e"); lost = true }
        }
        if (lost) {
            running = false
            onCaptureLost?.invoke(source)
        }
    }

    private fun processBlock(leftSamples: FloatArray, rightSamples: FloatArray?) {
        if (paused) return
        bands.process(leftSamples)
        val r = if (rightSamples != null) bandsRight.also { it.process(rightSamples) } else bands
        val floor = floorDb
        val span = ceilDb - floor
        var loudest = floor
        for (db in bands.bandDb) if (db > loudest) loudest = db
        for (db in r.bandDb) if (db > loudest) loudest = db
        refDb = maxOf(loudest, refDb - AUTO_RELEASE_DB_PER_SEC / rowsPerSecond, floor + span * 0.5f)
        val gain = if (autoLevel) (ceilDb - AUTO_HEADROOM_DB - refDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else gainDb
        effectiveGainDb = gain
        val louder = if (r.rmsDb > bands.rmsDb) r else bands
        peakHz = louder.peakHz
        rmsDb = louder.rmsDb
        if (rmsDb > floor + 3f) lastSoundAtMs = System.currentTimeMillis()
        synchronized(lock) {
            head = (head + 1) % historyRows
            val base = head * bandCount
            for (i in 0 until bandCount) {
                ringLeft[base + i] = smooth(smoothedLeft, i, (bands.bandDb[i] + gain - floor) / span)
                ringRight[base + i] = smooth(smoothedRight, i, (r.bandDb[i] + gain - floor) / span)
            }
        }
    }

    private fun smooth(state: FloatArray, i: Int, raw: Float): Float {
        val s = maxOf(raw.coerceIn(0f, 1f), state[i] - RELEASE_PER_HOP)
        state[i] = s
        return s
    }

    override fun snapshot(out: FloatArray) {
        val perSide = bandCount * historyRows
        synchronized(lock) {
            for (r in 0 until historyRows) {
                val src = ((head - r + historyRows) % historyRows) * bandCount
                System.arraycopy(ringLeft, src, out, r * bandCount, bandCount)
                System.arraycopy(ringRight, src, out, perSide + r * bandCount, bandCount)
            }
        }
    }

    companion object {
        private const val RELEASE_PER_HOP = 0.06f
        private const val IDENTICAL_BLOCKS_FOR_MONO = 20
        private const val AUTO_HEADROOM_DB = 3f
        private const val AUTO_RELEASE_DB_PER_SEC = 3f
        const val MIN_GAIN_DB = -30f
        const val MAX_GAIN_DB = 30f
    }
}
