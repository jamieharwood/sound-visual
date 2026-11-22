package io.github.jamieharwood.soundvisual

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the spectrum history as a 3D matrix of bars:
 *   X = frequency band (log scale, low on the left)
 *   Y = volume (bar height + colour)
 *   Z = time (newest row at the front, older rows recede)
 */
class GridRenderer(private val analyzer: AudioAnalyzer) : GLSurfaceView.Renderer {

    /** A text label anchored to a point in world space; the overlay draws it. */
    class Label(@Volatile var text: String, val x: Float, val y: Float, val z: Float, val kind: Kind) {
        enum class Kind { TICK, TITLE }
    }

    private val cols = analyzer.bandCount
    private val rows = analyzer.historyRows
    private val halfW = cols / 2f
    private val halfD = rows / 2f
    private val zFront = halfD
    private val xLeft = -halfW - 1f
    private val xRight = halfW + 1f

    // Camera, driven from the UI thread.
    @Volatile var yawDeg = DEFAULT_YAW
    @Volatile var pitchDeg = DEFAULT_PITCH
    @Volatile var distance = DEFAULT_DISTANCE

    fun resetCamera() { yawDeg = DEFAULT_YAW; pitchDeg = DEFAULT_PITCH; distance = DEFAULT_DISTANCE }

    // Filled in by buildLabels(), so they must be declared before `labels`.
    private var historyLabel: Label? = null
    private var volumeTitle: Label? = null
    /** Volume tick labels with their fixed height (0..1), relabelled by [setDbRange]. */
    private val dbLabels = mutableListOf<Pair<Label, Float>>()

    val labels: List<Label> = buildLabels()
    /** x, y, visible(1/0) per label in screen pixels; guarded by [labelLock]. */
    val labelScreen = FloatArray(labels.size * 3)
    val labelLock = Any()

    private val levels = FloatArray(cols * rows)
    private lateinit var levelBuffer: FloatBuffer

    private var barProgram = 0
    private var lineProgram = 0
    private var barVao = 0
    private var levelVbo = 0
    private var lineVao = 0
    private var lineVertexCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmpIn = FloatArray(4)
    private val tmpOut = FloatArray(4)
    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0.043f, 0.055f, 0.078f, 1f)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        barProgram = buildProgram(BAR_VS, BAR_FS)
        lineProgram = buildProgram(LINE_VS, LINE_FS)
        setupBars()
        setupLines()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 45f, w.toFloat() / h.coerceAtLeast(1), 1f, 600f)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Camera orbiting the centre of the grid.
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cy = HEIGHT_SCALE * 0.25f
        val ex = (distance * sin(yaw) * cos(pitch)).toFloat()
        val ey = cy + (distance * sin(pitch)).toFloat()
        val tz = halfD * 0.2f
        val ez = tz + (distance * cos(yaw) * cos(pitch)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, cy, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        // Upload newest levels.
        analyzer.snapshot(levels)
        levelBuffer.position(0)
        levelBuffer.put(levels).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, levelVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, levels.size * 4, levelBuffer)

        // Bars.
        glUseProgram(barProgram)
        glUniformMatrix4fv(glGetUniformLocation(barProgram, "uMvp"), 1, false, mvp, 0)
        glUniform2f(glGetUniformLocation(barProgram, "uGrid"), cols.toFloat(), rows.toFloat())
        glUniform1f(glGetUniformLocation(barProgram, "uHeight"), HEIGHT_SCALE)
        glBindVertexArray(barVao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, CUBE_VERTS, cols * rows)

        // Floor grid and axes.
        glUseProgram(lineProgram)
        glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        glBindVertexArray(lineVao)
        glDrawArrays(GL_LINES, 0, lineVertexCount)
        glBindVertexArray(0)

        projectLabels()
    }

    private fun projectLabels() {
        synchronized(labelLock) {
            labels.forEachIndexed { i, l ->
                tmpIn[0] = l.x; tmpIn[1] = l.y; tmpIn[2] = l.z; tmpIn[3] = 1f
                Matrix.multiplyMV(tmpOut, 0, mvp, 0, tmpIn, 0)
                val w = tmpOut[3]
                if (w <= 0.01f) { labelScreen[i * 3 + 2] = 0f; return@forEachIndexed }
                labelScreen[i * 3] = (tmpOut[0] / w + 1f) * 0.5f * width
                labelScreen[i * 3 + 1] = (1f - tmpOut[1] / w) * 0.5f * height
                labelScreen[i * 3 + 2] = 1f
            }
        }
    }


    /**
     * Relabel the volume axis when the source maps a different dB range onto bar height.
     * [relative] means the values are dB below the loudest recent band rather than dBFS.
     */
    fun setDbRange(floorDb: Float, ceilDb: Float, relative: Boolean) {
        for ((label, fraction) in dbLabels) {
            label.text = Math.round(floorDb + fraction * (ceilDb - floorDb)).toString()
        }
        volumeTitle?.text = if (relative) VOLUME_TITLE_RELATIVE else VOLUME_TITLE_ABSOLUTE
    }

    private fun historyText(seconds: Float) = "-%.1fs".format(seconds)

    /** Update the time-axis label when the source's row rate changes. */
    fun setHistorySeconds(seconds: Float) {
        historyLabel?.text = historyText(seconds)
    }

    private fun bandX(hz: Float): Float = analyzer.bands.bandPosition(hz) - halfW

    private fun buildLabels(): List<Label> {
        val out = mutableListOf<Label>()
        for (hz in FREQ_TICKS) {
            if (hz < analyzer.bands.minHz || hz > analyzer.bands.maxHz) continue
            val text = if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString()
            out += Label(text, bandX(hz), 0f, zFront + 2.5f, Label.Kind.TICK)
        }
        val b = analyzer.bands
        for (db in DB_TICKS) {
            val fraction = b.dbToLevel(db.toFloat())
            val label = Label("$db", xLeft - 2.5f, fraction * HEIGHT_SCALE, zFront, Label.Kind.TICK)
            dbLabels += label to fraction
            out += label
        }
        out += Label("now", xRight + 3f, 0f, zFront, Label.Kind.TICK)
        out += Label(historyText(analyzer.historySeconds), xRight + 3f, 0f, -halfD, Label.Kind.TICK)
            .also { historyLabel = it }

        out += Label("Frequency (Hz)", 0f, 0f, zFront + 10f, Label.Kind.TITLE)
        out += Label(VOLUME_TITLE_ABSOLUTE, xLeft, HEIGHT_SCALE + 3f, zFront, Label.Kind.TITLE)
            .also { volumeTitle = it }
        out += Label("Time", xRight + 4f, 0f, 0f, Label.Kind.TITLE)
        return out
    }

    private fun setupBars() {
        // Unit bar: x,z in -0.5..0.5, y in 0..1. Top + four sides (bottom never visible).
        val cube = floatArrayOf(
            // pos              normal
            // top
            -.5f, 1f, -.5f, 0f, 1f, 0f, -.5f, 1f, .5f, 0f, 1f, 0f, .5f, 1f, .5f, 0f, 1f, 0f,
            -.5f, 1f, -.5f, 0f, 1f, 0f, .5f, 1f, .5f, 0f, 1f, 0f, .5f, 1f, -.5f, 0f, 1f, 0f,
            // front (+z)
            -.5f, 0f, .5f, 0f, 0f, 1f, .5f, 0f, .5f, 0f, 0f, 1f, .5f, 1f, .5f, 0f, 0f, 1f,
            -.5f, 0f, .5f, 0f, 0f, 1f, .5f, 1f, .5f, 0f, 0f, 1f, -.5f, 1f, .5f, 0f, 0f, 1f,
            // back (-z)
            .5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 1f, -.5f, 0f, 0f, -1f,
            .5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 1f, -.5f, 0f, 0f, -1f, .5f, 1f, -.5f, 0f, 0f, -1f,
            // left (-x)
            -.5f, 0f, -.5f, -1f, 0f, 0f, -.5f, 0f, .5f, -1f, 0f, 0f, -.5f, 1f, .5f, -1f, 0f, 0f,
            -.5f, 0f, -.5f, -1f, 0f, 0f, -.5f, 1f, .5f, -1f, 0f, 0f, -.5f, 1f, -.5f, -1f, 0f, 0f,
            // right (+x)
            .5f, 0f, .5f, 1f, 0f, 0f, .5f, 0f, -.5f, 1f, 0f, 0f, .5f, 1f, -.5f, 1f, 0f, 0f,
            .5f, 0f, .5f, 1f, 0f, 0f, .5f, 1f, -.5f, 1f, 0f, 0f, .5f, 1f, .5f, 1f, 0f, 0f,
        )
        val cells = FloatArray(cols * rows * 2)
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = (r * cols + c) * 2
            cells[i] = c.toFloat(); cells[i + 1] = r.toFloat()
        }
        levelBuffer = floatBuffer(levels.size)

        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0); barVao = ids[0]
        glBindVertexArray(barVao)

        val vbos = IntArray(3)
        glGenBuffers(3, vbos, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbos[0])
        glBufferData(GL_ARRAY_BUFFER, cube.size * 4, floatBuffer(cube), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)

        glBindBuffer(GL_ARRAY_BUFFER, vbos[1])
        glBufferData(GL_ARRAY_BUFFER, cells.size * 4, floatBuffer(cells), GL_STATIC_DRAW)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
        glVertexAttribDivisor(2, 1)

        levelVbo = vbos[2]
        glBindBuffer(GL_ARRAY_BUFFER, levelVbo)
        glBufferData(GL_ARRAY_BUFFER, levels.size * 4, levelBuffer, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(3)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 0, 0)
        glVertexAttribDivisor(3, 1)

        glBindVertexArray(0)
    }

    private fun setupLines() {
        val v = mutableListOf<Float>()
        fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, a: Float) {
            v += listOf(x1, y1, z1, a, x2, y2, z2, a)
        }
        val back = -halfD
        val front = zFront
        // Floor: lines along time every 8 bands, and along frequency every 8 rows.
        for (c in 0..cols step 8) line(c - halfW, 0f, back, c - halfW, 0f, front, 0.18f)
        for (r in 0..rows step 8) line(-halfW, 0f, front - r, halfW, 0f, front - r, 0.18f)
        // Frequency ticks in front of the grid.
        for (hz in FREQ_TICKS) {
            if (hz < analyzer.bands.minHz || hz > analyzer.bands.maxHz) continue
            val x = bandX(hz)
            line(x, 0f, front, x, 0f, front + 1.2f, 0.7f)
        }
        // Volume axis at the front-left corner with ticks and faint back-wall guides.
        line(xLeft, 0f, front, xLeft, HEIGHT_SCALE, front, 0.7f)
        for (db in DB_TICKS) {
            val y = analyzer.bands.dbToLevel(db.toFloat()) * HEIGHT_SCALE
            line(xLeft - 0.8f, y, front, xLeft, y, front, 0.7f)
            line(-halfW, y, back, halfW, y, back, 0.10f)
        }
        // Time axis at the right edge.
        line(xRight, 0f, front, xRight, 0f, back, 0.7f)

        val data = v.toFloatArray()
        lineVertexCount = data.size / 4

        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0); lineVao = ids[0]
        glBindVertexArray(lineVao)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12)
        glBindVertexArray(0)
    }

    private fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        floatBuffer(data.size).put(data).also { it.position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = glGetShaderInfoLog(s)
                Log.e(TAG, "shader compile failed: $log")
                throw IllegalStateException(log)
            }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException(glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "GridRenderer"
        private const val CUBE_VERTS = 30
        const val HEIGHT_SCALE = 24f
        private const val VOLUME_TITLE_ABSOLUTE = "Volume (dBFS)"
        private const val VOLUME_TITLE_RELATIVE = "Volume (dB vs peak)"
        private const val DEFAULT_YAW = -28f
        private const val DEFAULT_PITCH = 26f
        private const val DEFAULT_DISTANCE = 108f

        private val FREQ_TICKS = floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
        private val DB_TICKS = intArrayOf(-80, -60, -40, -20)

        private const val BAR_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aCell;   // x = band, y = row (0 = newest)
layout(location = 3) in float aLevel; // 0..1
uniform mat4 uMvp;
uniform vec2 uGrid;
uniform float uHeight;
out vec3 vColor;

vec3 heat(float t) {
    t = clamp(t, 0.0, 1.0);
    return clamp(vec3(1.5 - abs(4.0 * t - 3.0),
                      1.5 - abs(4.0 * t - 2.0),
                      1.5 - abs(4.0 * t - 1.0)), 0.0, 1.0);
}

void main() {
    float h = max(aLevel, 0.004) * uHeight;
    float x = aCell.x - uGrid.x * 0.5 + 0.5 + aPos.x * 0.78;
    float z = uGrid.y * 0.5 - 0.5 - aCell.y + aPos.z * 0.78;
    gl_Position = uMvp * vec4(x, aPos.y * h, z, 1.0);

    vec3 light = normalize(vec3(-0.4, 1.0, 0.6));
    float shade = 0.45 + 0.55 * max(dot(aNormal, light), 0.0);
    float age = aCell.y / uGrid.y;                 // 0 = now, 1 = oldest
    float fade = 1.0 - 0.65 * age;
    // Colour by level; base of each bar a little darker for depth.
    vec3 c = heat(0.08 + aLevel * 0.92) * mix(0.55, 1.0, aPos.y);
    vColor = c * shade * fade;
}
"""
        private const val BAR_FS = """#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 fragColor;
void main() { fragColor = vec4(vColor, 1.0); }
"""
        private const val LINE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aAlpha;
uniform mat4 uMvp;
out float vAlpha;
void main() { vAlpha = aAlpha; gl_Position = uMvp * vec4(aPos, 1.0); }
"""
        private const val LINE_FS = """#version 300 es
precision mediump float;
in float vAlpha;
out vec4 fragColor;
void main() { fragColor = vec4(0.75, 0.82, 0.95, vAlpha); }
"""
    }
}
