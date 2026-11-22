package io.github.jamieharwood.soundvisual

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tilt view: steer the camera by moving the phone, like looking at the graph through a window.
 *
 * Turning uses the direction the back of the phone points (world frame, from the rotation-vector
 * sensor). That direction is well defined whether the phone is held upright or tipped back,
 * and doesn't depend on portrait/landscape. Relative to the pose when Tilt view starts:
 *  - turning the phone left/right orbits the camera around the graph,
 *  - tipping it back to look down (as at a table) raises the camera; holding it upright
 *    gives a low, side-on view.
 *
 * Zoom comes from [setPushPull], fed with the phone's real movement measured by [ArTracker]:
 * moving the phone away from you zooms in, pulling it back zooms out.
 */
class TiltController(context: Context, private val renderer: GridRenderer) : SensorEventListener {

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
    private var running = false

    fun start() {
        if (running || sensor == null) return
        recenter()
        // ~200 Hz keeps the smoothing delay under 10 ms.
        sensorManager.registerListener(this, sensor, FAST_PERIOD_US)
        running = true
    }

    fun stop() {
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
        // Back of the phone = device -Z; in world coordinates that's minus the third column.
        val x = -rotation[2]   // east
        val y = -rotation[5]   // north
        val z = -rotation[8]   // up
        val horizontal = sqrt(x * x + y * y)
        val elevation = Math.toDegrees(asin(z.coerceIn(-1f, 1f).toDouble())).toFloat()
        // Lying almost flat, the heading is undefined; keep the last one until it's usable.
        val haveHeading = horizontal > 0.15f
        val azimuth = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

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

        // Turning right swings the view right; looking down (negative elevation) raises the camera.
        renderer.yawDeg = baseYaw - smoothYaw
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
        /** ~200 Hz: the fastest allowed without an extra permission. */
        private const val FAST_PERIOD_US = 5_000
        /** Per sample at ~200 Hz: under 10 ms of smoothing delay. */
        private const val SMOOTHING = 0.5f
        /** ln(2) per 20 cm: moving the phone 20 cm doubles or halves the zoom. */
        private const val ZOOM_PER_METRE = 3.47f
        const val MIN_PITCH = 3f
        const val MAX_PITCH = 88f
        const val MIN_DISTANCE = 35f
        const val MAX_DISTANCE = 260f
    }
}
