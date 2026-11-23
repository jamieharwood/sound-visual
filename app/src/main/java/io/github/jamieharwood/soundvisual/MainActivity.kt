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
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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
    private lateinit var viewButton: TextView
    private lateinit var tilt: TiltController
    private lateinit var ar: ArTracker
    private lateinit var wear: WearBridge
    /** Off screen but still analysing because a watch is watching (see [StreamService]). */
    private var streamingForWatch = false
    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false   // between onResume and onPause
    private var arUnavailableShown = false

    /** How the camera is driven. */
    private enum class ViewMode(val button: String, val menu: String) {
        TOUCH("View ▾", "Touch – drag to rotate, pinch to zoom"),
        AUTO("Auto view ▾", "Auto view – tour the graph by itself"),
        TILT("Tilt view ▾", "Tilt view – move the phone to look around"),
    }
    private var viewMode = ViewMode.TOUCH
    private lateinit var levelControl: LinearLayout
    private lateinit var levelSlider: SeekBar
    private lateinit var levelText: TextView
    private lateinit var autoToggle: TextView
    private lateinit var levelExpanded: LinearLayout
    private lateinit var levelCollapsed: TextView
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var source: CaptureSource = CaptureSource.Microphone
    private var projection: MediaProjection? = null
    /** App (uid + name) the user picked while we wait for capture consent; uid null = all apps. */
    private var pendingTarget: Pair<Int?, String>? = null

    private var lastX = 0f
    private var lastY = 0f
    private var dragPointerId = MotionEvent.INVALID_POINTER_ID

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analyzer = AudioAnalyzer(this)
        analyzer.onCaptureLost = { lostSource ->
            runOnUiThread { onCaptureLost(lostSource) }
        }
        renderer = GridRenderer(analyzer)
        wear = WearBridge(this, analyzer) { renderer.palette }
        analyzer.onRow = { left, right -> wear.onRow(left, right) }
        wear.start()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 4)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = LabelOverlayView(this, renderer, analyzer)
        sourceButton = chip("Source ▾") { showSourceDialog() }
        renderer.palette = Palette.fromName(prefs.getString(KEY_PALETTE, null))
        // Reopen on the last source that doesn't need fresh consent.
        if (prefs.getString(KEY_SOURCE, null) == "output") source = CaptureSource.PhoneOutput
        levelControl = buildLevelControl()
        val topBar = buildTopBar()
        tilt = TiltController(this, renderer) { currentDisplayRotation() }
        ar = ArTracker(this) { forward -> tilt.setPushPull(forward) }
        renderer.onCameraTextureCreated = { id -> ar.onGlTextureCreated(id) }
        renderer.onFrameStart = { ar.onGlFrame() }
        overlay.zoomStatus = { if (viewMode == ViewMode.TILT) ar.statusText ?: "off (no camera)" else null }
        setViewMode(savedViewMode(), save = false)

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (viewMode == ViewMode.TILT) {
                    // Tilt view owns the distance (push/pull zoom); pinch moves its baseline.
                    tilt.baseDistance = (tilt.baseDistance / d.scaleFactor)
                        .coerceIn(renderer.minDistance, renderer.maxDistance)
                } else {
                    renderer.distance = (renderer.distance / d.scaleFactor)
                        .coerceIn(renderer.minDistance, renderer.maxDistance)
                }
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                analyzer.paused = !analyzer.paused; return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                renderer.resetCamera()
                if (viewMode == ViewMode.TILT) {
                    // Current pose and position become the default view.
                    tilt.recenter()
                    ar.recenter()
                }
                return true
            }
        })
        glView.setOnTouchListener { _, e ->
            // Any touch hands the camera to the user; Auto view resumes after a pause.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) renderer.userInteracted()
            scale.onTouchEvent(e)
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragPointerId = e.getPointerId(0)
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // A finger lifted (usually the end of a pinch): carry on from a finger that's
                    // still down. Using the lifted finger's position made the next move look like
                    // a finger-spread-wide drag and spun the view up to ~90°.
                    val keep = if (e.actionIndex == 0) 1 else 0
                    dragPointerId = e.getPointerId(keep)
                    lastX = e.getX(keep); lastY = e.getY(keep)
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress) {
                    val i = e.findPointerIndex(dragPointerId)
                    if (i < 0) {
                        // Lost track of the finger: restart from wherever it is now, without moving.
                        dragPointerId = e.getPointerId(0)
                        lastX = e.getX(0); lastY = e.getY(0)
                        return@setOnTouchListener true
                    }
                    val x = e.getX(i)
                    val y = e.getY(i)
                    val k = 180f / glView.width.coerceAtLeast(1)
                    if (viewMode == ViewMode.TILT) {
                        // The sensor owns the camera; dragging shifts the centre it steers around.
                        tilt.baseYaw -= (x - lastX) * k
                        tilt.basePitch = (tilt.basePitch + (y - lastY) * k)
                            .coerceIn(TiltController.MIN_PITCH, TiltController.MAX_PITCH)
                    } else {
                        renderer.yawDeg -= (x - lastX) * k
                        // Negative pitch looks up from below, at the right channel.
                        renderer.pitchDeg = (renderer.pitchDeg + (y - lastY) * k).coerceIn(-85f, 85f)
                    }
                    lastX = x; lastY = y
                }
            }
            true
        }

        val margin = dp(16)
        overlay.onInsetsChanged = { _, top, right, bottom ->
            (topBar.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = margin + top
                rightMargin = margin + right
            }
            topBar.requestLayout()
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
            addView(topBar, FrameLayout.LayoutParams(
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
        stopStreamingForWatch(stopAnalyzer = false) // on screen again: back to normal
        levelText.removeCallbacks(levelTicker)
        levelText.post(levelTicker)
        glView.onResume()
        isActive = true
        if (viewMode == ViewMode.TILT) tilt.start()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
            if (viewMode == ViewMode.TILT) startAr()
        } else {
            overlay.message = "Microphone permission needed"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    override fun onPause() {
        levelText.removeCallbacks(levelTicker)
        isActive = false
        tilt.stop()
        // Keep capturing other apps' audio while we're in the background (the foreground
        // service keeps us alive). If we stopped, Android would remove the capture route,
        // and a player started meanwhile (e.g. pressing play in Spotify) would bypass it
        // for as long as that stream lives.
        if (source !is CaptureSource.Playback) {
            if (wear.hasSubscribers && !isFinishing) {
                // A watch is showing the graph: keep listening off screen. The service has to
                // start now, while we're still visible – Android won't allow it later.
                StreamService.start(this)
                streamingForWatch = true
                handler.postDelayed(watchCheck, WATCH_CHECK_MS)
            } else {
                analyzer.stop()
            }
        }
        glView.onPause()
        ar.pause() // after the GL thread has stopped calling into ARCore
        super.onPause()
    }

    override fun onDestroy() {
        CaptureService.onProjection = null
        analyzer.stop()
        releaseProjection()
        ar.close()
        stopStreamingForWatch(stopAnalyzer = false)
        wear.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (viewMode == ViewMode.TILT && isActive) startAr()
            } else {
                toast("Without the camera, Tilt view can turn but not zoom")
            }
            return
        }
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startAudio()
            if (viewMode == ViewMode.TILT) startAr()
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

    /** Rounded translucent chip used for the on-screen buttons. */
    private fun chipBackground() = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.argb(170, 30, 40, 60))
        setStroke(dp(1), Color.argb(120, 190, 205, 230))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = chipBackground()
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Top-right buttons: Auto view, Colours and Source. */
    private fun buildTopBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        viewButton = chip("View ▾") { showViewDialog() }
        addView(viewButton)
        addView(chip("Colours ▾") { showPaletteDialog() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(10) })
        addView(sourceButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(10) })
    }

    // --- View (camera) mode -------------------------------------------------------------

    private fun showViewDialog() {
        val modes = ViewMode.entries
        AlertDialog.Builder(this)
            .setTitle("Camera")
            .setItems(modes.map { if (it == viewMode) "${it.menu}  ✓" else it.menu }.toTypedArray()) { _, which ->
                val mode = modes[which]
                if (mode == ViewMode.TILT && !tilt.available) {
                    toast("This device has no rotation sensor for Tilt view")
                } else {
                    setViewMode(mode, save = true)
                }
            }
            .show()
    }

    /**
     * Start ARCore position tracking for Tilt view's push/pull zoom: asks for the camera
     * the first time, and may offer to install Google Play Services for AR.
     */
    private fun startAr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        when (ar.resume()) {
            ArTracker.Status.RUNNING, ArTracker.Status.INSTALL_REQUESTED -> Unit
            ArTracker.Status.UNSUPPORTED, ArTracker.Status.FAILED -> if (!arUnavailableShown) {
                arUnavailableShown = true
                toast("AR tracking isn't available, so Tilt view can turn but not zoom")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    /** While off screen for a watch: stop once no watch has asked for a while. */
    private val watchCheck = object : Runnable {
        override fun run() {
            if (!streamingForWatch) return
            val idle = SystemClock.elapsedRealtime() - wear.lastRequestMs
            if (idle > WATCH_GRACE_MS) {
                stopStreamingForWatch(stopAnalyzer = true)
            } else {
                handler.postDelayed(this, WATCH_CHECK_MS)
            }
        }
    }

    private fun stopStreamingForWatch(stopAnalyzer: Boolean) {
        handler.removeCallbacks(watchCheck)
        if (!streamingForWatch) return
        streamingForWatch = false
        if (stopAnalyzer && source !is CaptureSource.Playback) analyzer.stop()
        StreamService.stop(this)
    }

    private fun savedViewMode(): ViewMode {
        val name = prefs.getString(KEY_VIEW_MODE, null)
            ?: if (prefs.getBoolean(KEY_AUTO_VIEW_LEGACY, false)) ViewMode.AUTO.name else ViewMode.TOUCH.name
        val mode = ViewMode.entries.firstOrNull { it.name == name } ?: ViewMode.TOUCH
        return if (mode == ViewMode.TILT && !tilt.available) ViewMode.TOUCH else mode
    }

    /** Touch (default), Auto view (camera tour) or Tilt view (phone orientation). */
    private fun setViewMode(mode: ViewMode, save: Boolean) {
        viewMode = mode
        renderer.autoCamera = mode == ViewMode.AUTO
        if (mode == ViewMode.TILT) {
            tilt.start()
            if (isActive) startAr()
        } else {
            tilt.stop()
            glView.queueEvent { ar.pause() } // on the GL thread, so no frame update races it
        }
        if (save) prefs.edit().putString(KEY_VIEW_MODE, mode.name).apply()
        val active = mode != ViewMode.TOUCH
        viewButton.text = mode.button
        viewButton.background = if (active) {
            GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(120, 200, 255))
            }
        } else {
            chipBackground()
        }
        viewButton.setTextColor(if (active) Color.rgb(10, 20, 35) else Color.WHITE)
    }

    // --- Colours -----------------------------------------------------------------------

    private fun showPaletteDialog() {
        val palettes = Palette.entries
        val adapter = object : BaseAdapter() {
            override fun getCount() = palettes.size
            override fun getItem(position: Int) = palettes[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val p = palettes[position]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    addView(View(context).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT, p.previewColors,
                        ).apply { cornerRadius = dp(4).toFloat() }
                    }, LinearLayout.LayoutParams(dp(140), dp(22)))
                    addView(TextView(context).apply {
                        text = if (p == renderer.palette) "${p.label}  ✓" else p.label
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setPadding(dp(20), 0, 0, 0)
                    })
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Graph colours")
            .setAdapter(adapter) { _, which ->
                renderer.palette = palettes[which]
                prefs.edit().putString(KEY_PALETTE, palettes[which].name).apply()
            }
            .show()
    }

    // --- Level ---------------------------------------------------------------------------

    /**
     * Level panel (bottom right): Auto toggle, current level, a slider that shifts every bar
     * by -30..+30 dB, and ✕ to collapse it to a small "Level ▴" chip. Manual level and Auto
     * are remembered per source; collapsed/expanded is remembered globally.
     */
    private fun buildLevelControl(): LinearLayout {
        levelText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = dp(105)
        }
        autoToggle = TextView(this).apply {
            text = "Auto"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { setAuto(!analyzer.autoLevel, save = true) }
        }
        levelSlider = SeekBar(this).apply {
            max = (AudioAnalyzer.MAX_GAIN_DB - AudioAnalyzer.MIN_GAIN_DB).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(120, 200, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val gain = progress + AudioAnalyzer.MIN_GAIN_DB
                    analyzer.gainDb = gain
                    updateLevelText()
                    if (fromUser) prefs.edit().putFloat(gainKey(source), gain).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val hide = TextView(this).apply {
            text = "✕"
            contentDescription = "Hide level control"
            setTextColor(Color.argb(200, 220, 230, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            isClickable = true
            setOnClickListener { setLevelHidden(true) }
        }
        levelExpanded = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(autoToggle)
            addView(levelText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(10) })
            addView(levelSlider, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hide)
        }
        levelCollapsed = TextView(this).apply {
            text = "Level ▴"
            contentDescription = "Show level control"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(6), dp(6))
            background = chipBackground()
            addView(levelExpanded)
            addView(levelCollapsed)
            setOnClickListener { if (levelCollapsed.visibility == View.VISIBLE) setLevelHidden(false) }
            setLevelHidden(prefs.getBoolean(KEY_LEVEL_HIDDEN, false), container = this)
        }
    }

    private fun setLevelHidden(hidden: Boolean, container: LinearLayout = levelControl) {
        levelExpanded.visibility = if (hidden) View.GONE else View.VISIBLE
        levelCollapsed.visibility = if (hidden) View.VISIBLE else View.GONE
        container.isClickable = hidden
        prefs.edit().putBoolean(KEY_LEVEL_HIDDEN, hidden).apply()
    }

    private fun gainKey(s: CaptureSource) = "gain_" + sourceKey(s)
    private fun autoKey(s: CaptureSource) = "auto_" + sourceKey(s)
    private fun sourceKey(s: CaptureSource) = when (s) {
        CaptureSource.Microphone -> "mic"
        CaptureSource.PhoneOutput -> "output"
        is CaptureSource.Playback -> "capture"
    }

    /** Load this source's saved manual level and Auto setting. */
    private fun restoreLevel() {
        val gain = prefs.getFloat(gainKey(source), 0f)
            .coerceIn(AudioAnalyzer.MIN_GAIN_DB, AudioAnalyzer.MAX_GAIN_DB)
        analyzer.gainDb = gain
        levelSlider.progress = (gain - AudioAnalyzer.MIN_GAIN_DB).toInt()
        // Phone output's data is normalised by Android, so Auto is the sensible default there.
        setAuto(prefs.getBoolean(autoKey(source), source is CaptureSource.PhoneOutput), save = false)
    }

    private fun setAuto(on: Boolean, save: Boolean) {
        analyzer.autoLevel = on
        if (save) prefs.edit().putBoolean(autoKey(source), on).apply()
        autoToggle.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) setColor(Color.rgb(120, 200, 255)) else setStroke(dp(1), Color.argb(160, 190, 205, 230))
        }
        autoToggle.setTextColor(if (on) Color.rgb(10, 20, 35) else Color.WHITE)
        levelSlider.isEnabled = !on
        levelSlider.alpha = if (on) 0.35f else 1f
        updateLevelText()
    }

    private fun updateLevelText() {
        val gain = if (analyzer.autoLevel) analyzer.effectiveGainDb else analyzer.gainDb
        levelText.text = "%s %+d dB".format(if (analyzer.autoLevel) "Auto" else "Level", Math.round(gain))
    }

    /** While Auto is on, refresh the displayed level a few times a second. */
    private val levelTicker = object : Runnable {
        override fun run() {
            if (analyzer.autoLevel) updateLevelText()
            levelText.postDelayed(this, 250)
        }
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
            restoreLevel()
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
        private const val REQ_CAMERA = 3
        private const val WATCH_CHECK_MS = 5_000L
        /** Keep listening this long after the watch stops asking, so it can be reopened. */
        private const val WATCH_GRACE_MS = 60_000L
        private const val KEY_SOURCE = "source"
        private const val KEY_PALETTE = "palette"
        private const val KEY_LEVEL_HIDDEN = "level_hidden"
        private const val KEY_VIEW_MODE = "view_mode"
        /** Older builds stored only an on/off Auto view flag. */
        private const val KEY_AUTO_VIEW_LEGACY = "auto_view"
    }
}
