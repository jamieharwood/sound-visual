package io.github.jamieharwood.soundvisual

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tilt view: steer the camera by moving the phone, like looking at the graph through a window.
 *
 * Turning (heading) comes from the screen's left–right edge, rotated a quarter turn in the
 * horizontal plane. That edge stays level whether the phone is upright, flat (looking down as at
 * a table) or tipped towards you, so the heading is stable at every tilt. (The direction the back
 * points goes vertical when the phone lies flat, which made the heading swing by up to 90°.)
 * Height comes from the direction the back points. Relative to the pose when Tilt view starts:
 *  - turning the phone left/right orbits the camera around the graph,
 *  - tipping it back to look down (as at a table) raises the camera; tilting the top
 *    towards you lowers it, down to looking up from below at the right channel,
 *  - twisting it like a steering wheel rolls the view so the graph stays level with the room.
 *
 * Zoom comes from [setPushPull], fed with the phone's real movement measured by [ArTracker]:
 * moving the phone away from you zooms in, pulling it back zooms out.
 */
class TiltController(
    context: Context,
    private val renderer: GridRenderer,
    /** Current display rotation (Surface.ROTATION_*), so we know which way the screen faces. */
    private val displayRotation: () -> Int,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    // Game rotation vector ignores the magnetometer, so it doesn't drift near metal or speakers.
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    /** Camera angles at the reference pose; dragging in Tilt view adjusts these. */
    var baseYaw = 0f
    var basePitch = 0f

    /** Camera distance at the reference pose; pinching in Tilt view adjusts this. */
    @Volatile var baseDistance = 0f
        set(value) { field = value; applyZoom() }

    /** Log of the push/pull zoom factor (0 = none). */
    @Volatile private var zoomLog = 0f

    private val rotation = FloatArray(9)
    private var refAzimuth = Float.NaN
    private var refElevation = 0f
    private var lastAzimuthDelta = 0f
    private var smoothYaw = 0f
    private var smoothElevation = 0f
    private var smoothRoll = 0f
    private var running = false
    private var lastDisplayRotation = -1

    fun start() {
        if (running || sensor == null) return
        recenter()
        lastDisplayRotation = displayRotation()
        // ~200 Hz keeps the smoothing delay under 10 ms.
        sensorManager.registerListener(this, sensor, FAST_PERIOD_US)
        running = true
    }

    fun stop() {
        renderer.rollDeg = 0f // other view modes are always level
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    /** Make the current phone pose the centre, keeping the current camera view (no jump). */
    fun recenter() {
        baseYaw = renderer.yawDeg
        basePitch = renderer.pitchDeg
        refAzimuth = Float.NaN
        zoomLog = 0f
        baseDistance = renderer.distance
    }

    /**
     * Zoom from how far the phone has moved forward (metres, away from you = positive) since
     * the last [recenter]. Returns the movement actually used: at a zoom limit the extra is
     * dropped, so moving back the other way responds immediately.
     */
    fun setPushPull(forwardMetres: Float): Float {
        val base = baseDistance.coerceAtLeast(1f)
        val lo = ln(MIN_DISTANCE / base)
        val hi = ln(MAX_DISTANCE / base)
        zoomLog = (-forwardMetres * ZOOM_PER_METRE).coerceIn(lo, hi)
        applyZoom()
        return -zoomLog / ZOOM_PER_METRE
    }

    private fun applyZoom() {
        if (baseDistance <= 0f) return
        renderer.distance = (baseDistance * exp(zoomLog)).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)

        // The screen flipped (e.g. turned the other way up): re-centre on the current view so
        // the change of reference edge doesn't make it jump.
        val displayRot = displayRotation()
        if (displayRot != lastDisplayRotation) {
            Log.i(TAG, "display rotation $lastDisplayRotation -> $displayRot (re-centring)")
            lastDisplayRotation = displayRot
            baseYaw = renderer.yawDeg
            basePitch = renderer.pitchDeg
            refAzimuth = Float.NaN
        }

        // Height: the back of the phone is device -Z; in world coordinates, minus column 3.
        val backUp = -rotation[8]
        val elevation = Math.toDegrees(asin(backUp.coerceIn(-1f, 1f).toDouble())).toFloat()

        // Heading: the screen's right-hand edge in device axes depends on the display rotation.
        val (dx, dy) = when (displayRot) {
            Surface.ROTATION_90 -> 0f to 1f
            Surface.ROTATION_180 -> -1f to 0f
            Surface.ROTATION_270 -> 0f to -1f
            else -> 1f to 0f
        }
        // World direction of that edge (east, north): R * (dx, dy, 0).
        val rightEast = rotation[0] * dx + rotation[1] * dy
        val rightNorth = rotation[3] * dx + rotation[4] * dy
        val rightUp = rotation[6] * dx + rotation[7] * dy
        // Roll: how far the screen's right edge is raised above level (anticlockwise twist).
        val roll = Math.toDegrees(asin(rightUp.coerceIn(-1f, 1f).toDouble())).toFloat()
        // Facing direction = right edge turned 90° anticlockwise in the horizontal plane.
        val faceEast = -rightNorth
        val faceNorth = rightEast
        // Only if the phone is rolled right over on its side is the edge near vertical.
        val haveHeading = sqrt(faceEast * faceEast + faceNorth * faceNorth) > 0.2f
        val azimuth = Math.toDegrees(atan2(faceEast.toDouble(), faceNorth.toDouble())).toFloat()

        if (refAzimuth.isNaN()) {
            if (!haveHeading) return
            refAzimuth = azimuth
            refElevation = elevation
            lastAzimuthDelta = 0f
            smoothYaw = 0f
            smoothElevation = 0f
        }
        val azimuthDelta = if (haveHeading) wrap(azimuth - refAzimuth) else lastAzimuthDelta
        lastAzimuthDelta = azimuthDelta

        // Light smoothing to hide sensor jitter without feeling laggy.
        smoothYaw += wrap(azimuthDelta - smoothYaw) * SMOOTHING
        smoothElevation += ((elevation - refElevation) - smoothElevation) * SMOOTHING
        smoothRoll += (roll - smoothRoll) * SMOOTHING

        // Turning right swings the view right; looking down (negative elevation) raises the camera.
        renderer.yawDeg = baseYaw - smoothYaw
        // Absolute roll (not relative to the start pose), so the graph is level with the room.
        // Sign checked on a real device: the graph must turn against the twist.
        renderer.rollDeg = -smoothRoll
        renderer.pitchDeg = (basePitch - smoothElevation).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    companion object {
        private const val TAG = "TiltView"
        /** ~200 Hz: the fastest allowed without an extra permission. */
        private const val FAST_PERIOD_US = 5_000
        /** Per sample at ~200 Hz: under 10 ms of smoothing delay. */
        private const val SMOOTHING = 0.5f
        /** ln(2) per 20 cm: moving the phone 20 cm doubles or halves the zoom. */
        private const val ZOOM_PER_METRE = 3.47f
        /** Negative pitch looks up from below the centre plane, at the right channel. */
        const val MIN_PITCH = -85f
        const val MAX_PITCH = 85f
        /** Closest zoom: keeps the camera outside the graph (which now extends above and below). */
        const val MIN_DISTANCE = 60f
        const val MAX_DISTANCE = 280f
    }
}
