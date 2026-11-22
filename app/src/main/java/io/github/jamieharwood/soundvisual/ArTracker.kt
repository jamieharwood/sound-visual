package io.github.jamieharwood.soundvisual

import android.app.Activity
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException

/**
 * Measures how far the phone actually moves, using ARCore's camera + motion tracking, for
 * Tilt view's push/pull zoom. Camera frames are only used for tracking: never shown, saved
 * or sent anywhere.
 *
 * Each frame the phone's movement since the previous frame is projected onto the direction
 * it's pointing and accumulated, so "forward" always means towards whatever is behind the
 * phone. ARCore positions are metric and don't drift like integrated accelerometer data.
 *
 * Threading: [resume]/[pause]/[close] run on the UI thread (pause only after the GL view is
 * paused); [onGlFrame] and [onGlTextureCreated] run on the GL thread.
 */
class ArTracker(
    private val activity: Activity,
    /** Called on the GL thread with forward movement (m); returns the movement actually used. */
    private val onForwardMovement: (Float) -> Float,
) {
    enum class Status { RUNNING, INSTALL_REQUESTED, UNSUPPORTED, FAILED }

    @Volatile private var session: Session? = null
    @Volatile private var textureId = -1
    @Volatile private var textureSet = false
    @Volatile private var recenterRequested = false
    private var installRequested = false

    private var prevPos: FloatArray? = null
    private var forward = 0f

    /** Short status for the on-screen readout, e.g. "tracking" or "paused – too dark". */
    @Volatile var statusText: String? = null; private set
    private var lastLoggedStatus: String? = null

    private fun setStatus(text: String?) {
        statusText = text
        if (text != lastLoggedStatus) {
            lastLoggedStatus = text
            Log.i(TAG, "zoom tracking: ${text ?: "off"}")
        }
    }

    /** True once ARCore is installed and supported here (checked lazily). */
    fun isSupported(): Boolean = try {
        ArCoreApk.getInstance().checkAvailability(activity).isSupported
    } catch (e: Exception) {
        false
    }

    /**
     * Create (if needed) and resume the session. May open the Play Store to install Google
     * Play Services for AR, in which case it returns [Status.INSTALL_REQUESTED]; call again
     * from onResume afterwards. Needs CAMERA permission already granted.
     */
    fun resume(): Status {
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return Status.INSTALL_REQUESTED
                    }
                    ArCoreApk.InstallStatus.INSTALLED, null -> Unit
                }
                val s = Session(activity)
                s.configure(Config(s).apply {
                    // Lightest tracking setup: we only need the camera pose.
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    focusMode = Config.FocusMode.FIXED
                })
                textureSet = false
                session = s
            }
            session?.resume()
            recenterRequested = true
            Log.i(TAG, "ARCore tracking started")
            return Status.RUNNING
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore unavailable", e)
            close()
            return Status.UNSUPPORTED
        } catch (e: Exception) {
            Log.e(TAG, "ARCore failed to start", e)
            close()
            return Status.FAILED
        }
    }

    /** Release the camera. Call after the GL view has been paused. */
    fun pause() {
        session?.pause()
        setStatus(null)
    }

    fun close() {
        val s = session ?: return
        session = null
        s.close()
    }

    /** Start measuring movement from the current position again. */
    fun recenter() { recenterRequested = true }

    /** GL thread: ARCore streams camera frames into this external texture. */
    fun onGlTextureCreated(id: Int) {
        textureId = id
        textureSet = false
    }

    /** GL thread, once per drawn frame. */
    fun onGlFrame() {
        val s = session ?: return
        if (textureId < 0) return
        try {
            if (!textureSet) { s.setCameraTextureName(textureId); textureSet = true }
            val camera = s.update().camera
            if (camera.trackingState != TrackingState.TRACKING) {
                prevPos = null // resume cleanly without a jump when tracking comes back
                setStatus("paused – " + when (camera.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "too dark"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "moving too fast"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "camera can't see detail (covered?)"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "camera in use elsewhere"
                    else -> if (camera.trackingState == TrackingState.PAUSED) "starting…" else "lost"
                })
                return
            }
            setStatus("tracking")
            val pose = camera.pose
            val pos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
            if (recenterRequested) {
                recenterRequested = false
                forward = 0f
                prevPos = pos
                onForwardMovement(0f)
                return
            }
            val prev = prevPos
            prevPos = pos
            if (prev == null) return
            // The camera looks along its -Z axis: that's "forward", away from the user.
            val z = pose.zAxis
            val step = -((pos[0] - prev[0]) * z[0] + (pos[1] - prev[1]) * z[1] + (pos[2] - prev[2]) * z[2])
            forward = onForwardMovement(forward + step)
        } catch (e: Exception) {
            // e.g. session paused mid-frame, or camera briefly unavailable: skip this frame.
            Log.d(TAG, "ARCore frame skipped: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ArTracker"
    }
}
