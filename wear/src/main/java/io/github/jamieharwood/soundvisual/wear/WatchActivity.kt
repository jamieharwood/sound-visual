package io.github.jamieharwood.soundvisual.wear

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import io.github.jamieharwood.soundvisual.GridRenderer
import io.github.jamieharwood.soundvisual.TiltController
import io.github.jamieharwood.soundvisual.WearProtocol
import kotlin.math.exp

/**
 * Wear OS companion: shows the graph the phone app is currently drawing.
 *
 *  - Tilt view (default): tilt and turn your wrist to look around the graph.
 *  - Auto view: the camera tours the graph by itself.
 *  - Touch: drag to rotate.
 * Tap to switch view, double-tap to reset, turn the crown (or rotating bezel) to zoom.
 */
class WatchActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private enum class Mode(val label: String) { TILT("Tilt view"), AUTO("Auto view"), TOUCH("Touch") }

    private val feed = WatchFeed()
    private lateinit var renderer: GridRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var tilt: TiltController
    private lateinit var status: TextView

    private val messages by lazy { Wearable.getMessageClient(this) }
    private val nodes by lazy { Wearable.getNodeClient(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.TILT
    private var modeMessageUntilMs = 0L
    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = GridRenderer(feed)
        tilt = TiltController(this, renderer) { display?.rotation ?: Surface.ROTATION_0 }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isFocusable = true
            isFocusableInTouchMode = true
        }
        status = TextView(this).apply {
            setTextColor(Color.argb(220, 220, 230, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), dp(22))
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setMode(Mode.entries[(mode.ordinal + 1) % Mode.entries.size], announce = true)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                renderer.resetCamera()
                if (mode == Mode.TILT) tilt.recenter()
                return true
            }
        })
        glView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) renderer.userInteracted()
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> {
                    val k = 180f / glView.width.coerceAtLeast(1)
                    if (mode == Mode.TILT) {
                        tilt.baseYaw -= (e.x - lastX) * k
                        tilt.basePitch = (tilt.basePitch + (e.y - lastY) * k)
                            .coerceIn(TiltController.MIN_PITCH, TiltController.MAX_PITCH)
                    } else {
                        renderer.yawDeg -= (e.x - lastX) * k
                        renderer.pitchDeg = (renderer.pitchDeg + (e.y - lastY) * k).coerceIn(-85f, 85f)
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            true
        }
        // Crown / rotating bezel zooms.
        glView.setOnGenericMotionListener { _, e ->
            if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                val factor = exp(-e.getAxisValue(MotionEvent.AXIS_SCROLL) * CROWN_ZOOM)
                if (mode == Mode.TILT) {
                    tilt.baseDistance = (tilt.baseDistance * factor).coerceIn(renderer.minDistance, renderer.maxDistance)
                } else {
                    renderer.distance = (renderer.distance * factor).coerceIn(renderer.minDistance, renderer.maxDistance)
                }
                renderer.userInteracted()
                true
            } else {
                false
            }
        }

        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        })

        val saved = prefs.getString(KEY_MODE, null)
        setMode(Mode.entries.firstOrNull { it.name == saved } ?: Mode.TILT, announce = false)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        glView.requestFocus() // needed to receive crown events
        messages.addListener(this)
        if (mode == Mode.TILT) tilt.start()
        handler.post(subscribeTicker)
        handler.post(statusTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(subscribeTicker)
        handler.removeCallbacks(statusTicker)
        tilt.stop()
        messages.removeListener(this)
        sendToPhones(WearProtocol.PATH_UNSUBSCRIBE)
        glView.onPause()
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_ROWS) return
        val batch = WearProtocol.decode(event.data) ?: return
        feed.add(batch)
        renderer.palette = feed.palette
    }

    private fun setMode(m: Mode, announce: Boolean) {
        mode = m
        renderer.autoCamera = m == Mode.AUTO
        if (m == Mode.TILT) tilt.start() else tilt.stop()
        prefs.edit().putString(KEY_MODE, m.name).apply()
        if (announce) modeMessageUntilMs = SystemClock.elapsedRealtime() + 1500
        updateStatus()
    }

    /** Keep asking the phone for data while we're on screen (it forgets silent watches). */
    private val subscribeTicker = object : Runnable {
        override fun run() {
            sendToPhones(WearProtocol.PATH_SUBSCRIBE)
            handler.postDelayed(this, SUBSCRIBE_EVERY_MS)
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateStatus() {
        val now = SystemClock.elapsedRealtime()
        status.text = when {
            now < modeMessageUntilMs -> mode.label
            feed.lastDataAtMs == 0L || now - feed.lastDataAtMs > NO_DATA_MS ->
                "Open Sound Visual on your phone"
            else -> ""
        }
    }

    private fun sendToPhones(path: String) {
        nodes.connectedNodes.addOnSuccessListener { list ->
            for (node in list) messages.sendMessage(node.id, path, ByteArray(0))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_MODE = "view_mode"
        private const val SUBSCRIBE_EVERY_MS = 2_000L
        private const val NO_DATA_MS = 3_000L
        /** Zoom per crown scroll unit (log scale). */
        private const val CROWN_ZOOM = 0.15f
    }
}
