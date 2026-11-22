package io.github.jamieharwood.soundvisual

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var analyzer: AudioAnalyzer
    private lateinit var renderer: GridRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: LabelOverlayView
    private lateinit var sourceButton: TextView
    private lateinit var levelControl: LinearLayout
    private lateinit var levelSlider: SeekBar
    private lateinit var levelText: TextView
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var source: CaptureSource = CaptureSource.Microphone
    private var projection: MediaProjection? = null
    /** App (uid + name) the user picked while we wait for capture consent; uid null = all apps. */
    private var pendingTarget: Pair<Int?, String>? = null

    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analyzer = AudioAnalyzer(this)
        analyzer.onCaptureLost = { lostSource ->
            runOnUiThread { onCaptureLost(lostSource) }
        }
        renderer = GridRenderer(analyzer)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 4)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = LabelOverlayView(this, renderer, analyzer)
        sourceButton = buildSourceButton()
        // Reopen on the last source that doesn't need fresh consent.
        if (prefs.getString(KEY_SOURCE, null) == "output") source = CaptureSource.PhoneOutput
        levelControl = buildLevelControl()

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

        val margin = dp(16)
        overlay.onInsetsChanged = { _, top, right, bottom ->
            (sourceButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = margin + top
                rightMargin = margin + right
            }
            sourceButton.requestLayout()
            (levelControl.layoutParams as FrameLayout.LayoutParams).apply {
                // Sit above the controls hint the overlay draws along the bottom edge.
                bottomMargin = margin + bottom + dp(24)
                rightMargin = margin + right
            }
            levelControl.requestLayout()
        }

        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(this@MainActivity.overlay)
            addView(sourceButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = margin; rightMargin = margin })
            addView(levelControl, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { bottomMargin = margin + dp(24); rightMargin = margin })
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
        // Keep capturing other apps' audio while we're in the background (the foreground
        // service keeps us alive). If we stopped, Android would remove the capture route,
        // and a player started meanwhile (e.g. pressing play in Spotify) would bypass it
        // for as long as that stream lives.
        if (source !is CaptureSource.Playback) analyzer.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CaptureService.onProjection = null
        analyzer.stop()
        releaseProjection()
        super.onDestroy()
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val target = pendingTarget
        pendingTarget = null
        if (resultCode != RESULT_OK || data == null || target == null) {
            toast("Audio capture not allowed – staying on ${source.label}")
            return
        }
        CaptureService.onProjection = { p -> onProjectionReady(p, target) }
        CaptureService.start(this, resultCode, data)
    }

    // --- Source selection -------------------------------------------------------------

    private fun buildSourceButton() = TextView(this).apply {
        text = "Source ▾"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(170, 30, 40, 60))
            setStroke(dp(1), Color.argb(120, 190, 205, 230))
        }
        isClickable = true
        setOnClickListener { showSourceDialog() }
    }

    /** "Level" slider: shifts every bar by -30..+30 dB. Remembered separately per source. */
    private fun buildLevelControl(): LinearLayout {
        levelText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = dp(110)
        }
        levelSlider = SeekBar(this).apply {
            max = (MAX_GAIN_DB - MIN_GAIN_DB).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(120, 200, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val gain = progress + MIN_GAIN_DB
                    applyGain(gain)
                    if (fromUser) prefs.edit().putFloat(gainKey(source), gain).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(170, 30, 40, 60))
                setStroke(dp(1), Color.argb(120, 190, 205, 230))
            }
            addView(levelText)
            addView(levelSlider, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun gainKey(s: CaptureSource) = "gain_" + when (s) {
        CaptureSource.Microphone -> "mic"
        CaptureSource.PhoneOutput -> "output"
        is CaptureSource.Playback -> "capture"
    }

    /** Load this source's saved level into the slider (which applies it). */
    private fun restoreGain() {
        val gain = prefs.getFloat(gainKey(source), 0f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val progress = (gain - MIN_GAIN_DB).toInt()
        if (levelSlider.progress == progress) applyGain(gain) else levelSlider.progress = progress
    }

    private fun applyGain(gain: Float) {
        analyzer.gainDb = gain
        levelText.text = "Level %+d dB".format(gain.toInt())
        // Bars show (dB + gain), so the axis must read gain lower to stay truthful.
        renderer.setDbRange(analyzer.floorDb - gain, analyzer.ceilDb - gain, analyzer.relativeLevels)
    }

    private fun showSourceDialog() {
        val captureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val items = mutableListOf(
            "Microphone",
            "Phone output – anything playing, incl. Spotify",
        )
        if (captureSupported) {
            items += "Capture one app (higher quality)…"
            items += "Capture all apps (higher quality)"
        }
        AlertDialog.Builder(this)
            .setTitle("Sound source")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> switchTo(CaptureSource.Microphone)
                    1 -> switchTo(CaptureSource.PhoneOutput)
                    2 -> showAppPicker()
                    3 -> requestPlaybackCapture(null, "All apps")
                }
            }
            .show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val media = info.category == ApplicationInfo.CATEGORY_AUDIO ||
                    info.category == ApplicationInfo.CATEGORY_VIDEO
                Triple(info.uid, pm.getApplicationLabel(info).toString(), media)
            }
            // Music / video apps first, then everything else, each alphabetical.
            .sortedWith(compareBy({ !it.third }, { it.second.lowercase() }))
        AlertDialog.Builder(this)
            .setTitle("Capture audio from")
            .setItems(apps.map { if (it.third) "♪  ${it.second}" else it.second }.toTypedArray()) { _, which ->
                val (uid, label, _) = apps[which]
                requestPlaybackCapture(uid, label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ask the user for capture consent; the result arrives in [onActivityResult]. */
    private fun requestPlaybackCapture(uid: Int?, label: String) {
        pendingTarget = uid to label
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Skip the "single app / entire screen" choice; we only want audio.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    private fun onProjectionReady(p: MediaProjection?, target: Pair<Int?, String>) {
        CaptureService.onProjection = null
        if (p == null) {
            toast("Couldn't start audio capture")
            CaptureService.stop(this)
            return
        }
        // Replace any previous session, but keep the foreground service running:
        // the new projection needs it, and Android refuses capture without it.
        analyzer.stop()
        releaseProjection(stopService = false)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User revoked capture (e.g. from the notification / status bar chip).
                if (projection === p) {
                    projection = null
                    CaptureService.stop(this@MainActivity)
                    switchTo(CaptureSource.Microphone)
                }
            }
        }, Handler(Looper.getMainLooper()))
        switchTo(CaptureSource.Playback(p, target.first, target.second))
    }

    /**
     * The recorder died underneath us. A playback capture can't be rebuilt on the same
     * consent (Android refuses to register its audio policy twice), so fall back to the
     * microphone and ask the user to pick the source again.
     */
    private fun onCaptureLost(lostSource: CaptureSource) {
        if (lostSource !== source) return
        if (lostSource is CaptureSource.Playback) {
            toast("Lost the ${lostSource.label} audio – pick the source again")
            switchTo(CaptureSource.Microphone)
        } else if (!isFinishing) {
            startAudio()
        }
    }

    private fun switchTo(newSource: CaptureSource) {
        analyzer.stop()
        if (newSource !is CaptureSource.Playback) releaseProjection()
        source = newSource
        when (newSource) {
            CaptureSource.Microphone -> prefs.edit().putString(KEY_SOURCE, "mic").apply()
            CaptureSource.PhoneOutput -> prefs.edit().putString(KEY_SOURCE, "output").apply()
            is CaptureSource.Playback -> Unit // needs consent each time; not restorable
        }
        startAudio()
    }

    private fun releaseProjection(stopService: Boolean = true) {
        val p = projection ?: return
        projection = null
        p.stop()
        if (stopService) CaptureService.stop(this)
    }

    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        overlay.sourceLabel = source.label
        overlay.silenceHint = when (source) {
            is CaptureSource.Playback ->
                "No audio – press play. Some apps (e.g. Spotify) block capture: use Phone output instead."
            CaptureSource.PhoneOutput -> "No audio – is anything playing?"
            CaptureSource.Microphone -> null
        }
        if (analyzer.start(source)) {
            renderer.setHistorySeconds(analyzer.historySeconds)
            restoreGain()
            overlay.message = null
        } else if (source is CaptureSource.Playback) {
            // Can't reuse this consent; go back to the mic rather than show a dead source.
            toast("Couldn't capture ${source.label} – pick the source again")
            switchTo(CaptureSource.Microphone)
        } else {
            overlay.message = "Could not open ${source.label}"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_AUDIO = 1
        private const val REQ_CAPTURE = 2
        private const val KEY_SOURCE = "source"
        private const val MIN_GAIN_DB = -30f
        private const val MAX_GAIN_DB = 30f
    }
}
