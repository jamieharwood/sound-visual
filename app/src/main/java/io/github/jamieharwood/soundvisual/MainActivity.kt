package io.github.jamieharwood.soundvisual

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {

    private lateinit var analyzer: AudioAnalyzer
    private lateinit var renderer: GridRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: LabelOverlayView

    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analyzer = AudioAnalyzer(this)
        renderer = GridRenderer(analyzer)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 4)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = LabelOverlayView(this, renderer, analyzer)

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.distance = (renderer.distance / d.scaleFactor).coerceIn(35f, 260f)
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                analyzer.paused = !analyzer.paused; return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                renderer.resetCamera(); return true
            }
        })
        glView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress) {
                    val k = 180f / glView.width.coerceAtLeast(1)
                    renderer.yawDeg -= (e.x - lastX) * k
                    renderer.pitchDeg = (renderer.pitchDeg + (e.y - lastY) * k).coerceIn(3f, 88f)
                    lastX = e.x; lastY = e.y
                }
            }
            true
        }

        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(this@MainActivity.overlay)
        })
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            overlay.message = "Microphone permission needed"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    override fun onPause() {
        analyzer.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            overlay.message = "Microphone permission denied – enable it in Settings"
        }
    }

    private fun startAudio() {
        overlay.message = if (analyzer.start()) null else "Could not open the microphone"
    }

    companion object {
        private const val REQ_AUDIO = 1
    }
}
