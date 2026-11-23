package io.github.jamieharwood.soundvisual

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams the graph to the Wear OS companion over the Wearable Data Layer (see [WearProtocol]).
 *
 * Watches ask for data by sending a subscribe message every couple of seconds while they're on
 * screen; rows are only sent to watches heard from in the last few seconds, so nothing is sent
 * while nobody is looking. Rows are merged to the watch's 32 bands, combined in pairs when the
 * source is faster than ~25 rows/s, and batched into ~10 messages a second.
 */
class WearBridge(
    context: Context,
    private val analyzer: AudioAnalyzer,
    private val palette: () -> Palette,
) : MessageClient.OnMessageReceivedListener {

    private val messages = Wearable.getMessageClient(context)
    /** Watch node id → last time it asked for data (elapsedRealtime ms). */
    private val subscribers = ConcurrentHashMap<String, Long>()

    private val pending = ByteArray(MAX_ROWS_PER_BATCH * WearProtocol.ROW_BYTES)
    private var pendingRows = 0
    private var lastSendMs = 0L
    // Pairing of rows for fast sources: the first row of a pair waits here.
    private val heldLeft = FloatArray(WearProtocol.BANDS)
    private val heldRight = FloatArray(WearProtocol.BANDS)
    private var holding = false

    /** [SystemClock.elapsedRealtime] of the most recent request from any watch (0 = none). */
    @Volatile var lastRequestMs = 0L; private set

    /** True if a watch has asked for data within the last few seconds. */
    val hasSubscribers: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            subscribers.entries.removeIf { now - it.value > SUBSCRIBER_TIMEOUT_MS }
            return subscribers.isNotEmpty()
        }

    fun start() {
        messages.addListener(this)
    }

    fun stop() {
        messages.removeListener(this)
        subscribers.clear()
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.PATH_SUBSCRIBE -> {
                lastRequestMs = SystemClock.elapsedRealtime()
                if (subscribers.put(event.sourceNodeId, lastRequestMs) == null) {
                    Log.i(TAG, "watch ${event.sourceNodeId} subscribed")
                }
            }
            WearProtocol.PATH_UNSUBSCRIBE -> {
                subscribers.remove(event.sourceNodeId)
                Log.i(TAG, "watch ${event.sourceNodeId} unsubscribed")
            }
        }
    }

    /** Capture thread: one new row from the analyzer (64 bands per channel). */
    fun onRow(left: FloatArray, right: FloatArray) {
        if (subscribers.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        subscribers.entries.removeIf { now - it.value > SUBSCRIBER_TIMEOUT_MS }
        if (subscribers.isEmpty()) return

        val pairRows = analyzer.rowsPerSecond > MAX_WATCH_ROWS_PER_SECOND
        val l = FloatArray(WearProtocol.BANDS)
        val r = FloatArray(WearProtocol.BANDS)
        merge(left, l)
        merge(right, r)
        if (pairRows) {
            if (!holding) {
                l.copyInto(heldLeft); r.copyInto(heldRight); holding = true
                return
            }
            for (i in 0 until WearProtocol.BANDS) {
                l[i] = maxOf(l[i], heldLeft[i]); r[i] = maxOf(r[i], heldRight[i])
            }
            holding = false
        }

        val base = pendingRows * WearProtocol.ROW_BYTES
        for (i in 0 until WearProtocol.BANDS) {
            pending[base + i] = WearProtocol.levelToByte(l[i])
            pending[base + WearProtocol.BANDS + i] = WearProtocol.levelToByte(r[i])
        }
        pendingRows++
        if (pendingRows >= MAX_ROWS_PER_BATCH || now - lastSendMs >= SEND_INTERVAL_MS) send(pairRows, now)
    }

    private fun send(paired: Boolean, now: Long) {
        val rps = analyzer.rowsPerSecond / if (paired) 2f else 1f
        val data = WearProtocol.encode(analyzer.stereo, palette(), rps, pending, pendingRows)
        pendingRows = 0
        lastSendMs = now
        for (node in subscribers.keys) {
            messages.sendMessage(node, WearProtocol.PATH_ROWS, data)
                .addOnFailureListener { Log.d(TAG, "send to $node failed: ${it.message}") }
        }
    }

    /** 64 bands → 32: each watch band shows the louder of two neighbouring phone bands. */
    private fun merge(src: FloatArray, dst: FloatArray) {
        val ratio = src.size / dst.size
        for (i in dst.indices) {
            var m = 0f
            for (k in 0 until ratio) m = maxOf(m, src[i * ratio + k])
            dst[i] = m
        }
    }

    companion object {
        private const val TAG = "WearBridge"
        private const val SUBSCRIBER_TIMEOUT_MS = 6_000L
        private const val SEND_INTERVAL_MS = 100L
        private const val MAX_ROWS_PER_BATCH = 4
        private const val MAX_WATCH_ROWS_PER_SECOND = 25f
    }
}
