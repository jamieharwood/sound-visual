package io.github.jamieharwood.soundvisual.desktop

import io.github.jamieharwood.soundvisual.Palette
import io.github.jamieharwood.soundvisual.SpectrumFeed
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Desktop port of the Android GridRenderer: the spectrum history as a 3D matrix of bars.
 *   X = frequency band (log scale), Y = volume (left channel up, right channel down),
 *   Z = time (newest row at the front). Call [init] once a GL 3.3 context is current.
 */
class DesktopGridRenderer(private val feed: SpectrumFeed) {

    class Label(@Volatile var text: String, val x: Float, val y: Float, val z: Float, val title: Boolean)

    private val cols = feed.bandCount
    private val rows = feed.historyRows
    val heightScale = 24f
    private val defaultDistance = 150f
    val minDistance = 60f
    val maxDistance = 280f
    private val halfW = cols / 2f
    private val halfD = rows / 2f
    private val zFront = halfD
    private val xLeft = -halfW - 1f
    private val xRight = halfW + 1f

    @Volatile var palette: Palette = Palette.SPECTRUM

    var yawDeg = DEFAULT_YAW
    var pitchDeg = DEFAULT_PITCH
    var distance = defaultDistance
    var panX = 0f
    var panZ = 0f

    fun resetCamera() {
        yawDeg = DEFAULT_YAW; pitchDeg = DEFAULT_PITCH; distance = defaultDistance
        panX = 0f; panZ = 0f
        tourSegmentMs = 0L
    }

    // --- Auto view: a slow random tour around the grid ---------------------------------
    var autoCamera = false
        set(value) { field = value; tourSegmentMs = 0L }
    private var tourPausedUntilMs = 0L
    private var tourStartMs = 0L
    private var tourSegmentMs = 0L
    private val tourFrom = FloatArray(5)
    private val tourTo = FloatArray(5)

    /** The user moved the view: hand them control and resume the tour after a pause. */
    fun userInteracted() {
        tourPausedUntilMs = System.currentTimeMillis() + TOUR_RESUME_AFTER_MS
        tourSegmentMs = 0L
    }

    private fun updateTour() {
        if (!autoCamera) return
        val now = System.currentTimeMillis()
        if (now < tourPausedUntilMs) return
        if (tourSegmentMs == 0L || now - tourStartMs >= tourSegmentMs) {
            tourFrom[0] = yawDeg; tourFrom[1] = pitchDeg; tourFrom[2] = distance
            tourFrom[3] = panX; tourFrom[4] = panZ
            tourTo[0] = Random.nextFloat() * 140f - 70f
            tourTo[1] = -40f + Random.nextFloat() * 100f
            tourTo[2] = 70f + Random.nextFloat() * 90f
            val roam = ((160f - tourTo[2]) / 90f).coerceIn(0f, 1f)
            tourTo[3] = (Random.nextFloat() * 2f - 1f) * halfW * 0.6f * roam
            tourTo[4] = (Random.nextFloat() * 2f - 1f) * halfD * 0.5f * roam
            tourStartMs = now
            tourSegmentMs = TOUR_MIN_SEGMENT_MS + Random.nextLong(TOUR_EXTRA_SEGMENT_MS)
        }
        val t = ((now - tourStartMs).toFloat() / tourSegmentMs).coerceIn(0f, 1f)
        val e = (0.5 - 0.5 * cos(PI * t)).toFloat()
        fun lerp(i: Int) = tourFrom[i] + (tourTo[i] - tourFrom[i]) * e
        yawDeg = lerp(0); pitchDeg = lerp(1); distance = lerp(2); panX = lerp(3); panZ = lerp(4)
    }

    private var historyLabel: Label? = null
    private val dbLabels = mutableListOf<Pair<Label, Float>>()
    val labels: List<Label> = buildLabels()
    /** x, y, visible(1/0) per label in framebuffer pixels, updated by [draw]. */
    val labelScreen = FloatArray(labels.size * 3)

    private val levels = FloatArray(2 * cols * rows)
    private val levelBuffer: FloatBuffer = MemoryUtil.memAllocFloat(levels.size)

    private var barProgram = 0
    private var lineProgram = 0
    private var barVao = 0
    private var levelVbo = 0
    private var lineVao = 0
    private var lineVertexCount = 0
    private var uBarMvp = 0; private var uBarGrid = 0; private var uBarHeight = 0; private var uBarStops = 0
    private var uLineMvp = 0

    private val proj = Matrix4f()
    private val mvp = Matrix4f()
    private val mvpArray = FloatArray(16)
    private val tmp = Vector4f()

    fun init() {
        barProgram = buildProgram(BAR_VS, BAR_FS)
        lineProgram = buildProgram(LINE_VS, LINE_FS)
        uBarMvp = glGetUniformLocation(barProgram, "uMvp")
        uBarGrid = glGetUniformLocation(barProgram, "uGrid")
        uBarHeight = glGetUniformLocation(barProgram, "uHeight")
        uBarStops = glGetUniformLocation(barProgram, "uStops")
        uLineMvp = glGetUniformLocation(lineProgram, "uMvp")
        setupBars()
        setupLines()
    }

    /** Draw the grid into a [width] x [height] framebuffer. */
    fun draw(width: Int, height: Int) {
        glViewport(0, 0, width, height)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        proj.setPerspective(Math.toRadians(45.0).toFloat(), width.toFloat() / height.coerceAtLeast(1), 1f, 600f)

        updateTour()
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val tx = panX
        val tz = halfD * 0.2f + panZ
        val ex = tx + (distance * sin(yaw) * cos(pitch)).toFloat()
        val ey = (distance * sin(pitch)).toFloat()
        val ez = tz + (distance * cos(yaw) * cos(pitch)).toFloat()
        val sinP = sin(pitch).toFloat(); val cosP = cos(pitch).toFloat()
        val ux = -sinP * sin(yaw).toFloat(); val uz = -sinP * cos(yaw).toFloat()
        mvp.set(proj).lookAt(ex, ey, ez, tx, 0f, tz, ux, cosP, uz)
        mvp.get(mvpArray)

        feed.snapshot(levels)
        levelBuffer.clear(); levelBuffer.put(levels).flip()
        glBindBuffer(GL_ARRAY_BUFFER, levelVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, levelBuffer)

        glUseProgram(barProgram)
        glUniformMatrix4fv(uBarMvp, false, mvpArray)
        glUniform2f(uBarGrid, cols.toFloat(), rows.toFloat())
        glUniform1f(uBarHeight, heightScale)
        glUniform3fv(uBarStops, palette.stops)
        glBindVertexArray(barVao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, CUBE_VERTS, 2 * cols * rows)

        glUseProgram(lineProgram)
        glUniformMatrix4fv(uLineMvp, false, mvpArray)
        glBindVertexArray(lineVao)
        glDrawArrays(GL_LINES, 0, lineVertexCount)
        glBindVertexArray(0)

        updateDbLabels()
        labels.forEachIndexed { i, l ->
            mvp.transform(tmp.set(l.x, l.y, l.z, 1f))
            if (tmp.w <= 0.01f) { labelScreen[i * 3 + 2] = 0f; return@forEachIndexed }
            labelScreen[i * 3] = (tmp.x / tmp.w + 1f) * 0.5f * width
            labelScreen[i * 3 + 1] = (1f - tmp.y / tmp.w) * 0.5f * height
            labelScreen[i * 3 + 2] = 1f
        }
    }

    private var lastLabelKey = Int.MIN_VALUE

    private fun updateDbLabels() {
        val floor = feed.floorDb
        val span = feed.ceilDb - floor
        val gain = feed.effectiveGainDb
        val key = Math.round(floor) * 1_000_003 + Math.round(span) * 1009 + Math.round(gain)
        if (key == lastLabelKey) return
        lastLabelKey = key
        for ((label, fraction) in dbLabels) label.text = Math.round(floor + fraction * span - gain).toString()
    }

    fun setHistorySeconds(seconds: Float) { historyLabel?.text = historyText(seconds) }
    private fun historyText(seconds: Float) = "-%.1fs".format(seconds)
    private fun bandX(hz: Float): Float = feed.bandPosition(hz) - halfW
    private fun tickFraction(db: Int) = (db - TICK_FLOOR_DB) / (TICK_CEIL_DB - TICK_FLOOR_DB)

    private fun buildLabels(): List<Label> {
        val out = mutableListOf<Label>()
        for (hz in FREQ_TICKS) {
            if (hz < feed.minHz || hz > feed.maxHz) continue
            val text = if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString()
            out += Label(text, bandX(hz), 0f, zFront + 2.5f, false)
        }
        for (db in DB_TICKS) {
            val fraction = tickFraction(db)
            for (side in floatArrayOf(1f, -1f)) {
                val label = Label("$db", xLeft - 2.5f, side * fraction * heightScale, zFront, false)
                dbLabels += label to fraction
                out += label
            }
        }
        out += Label("Left +", xLeft - 1f, heightScale + 1.5f, zFront, true)
        out += Label("Right −", xLeft - 1f, -heightScale - 1.5f, zFront, true)
        out += Label("now", xRight + 3f, 0f, zFront, false)
        out += Label(historyText(feed.historySeconds), xRight + 3f, 0f, -halfD, false).also { historyLabel = it }
        out += Label("Frequency (Hz)", 0f, 0f, zFront + 10f, true)
        out += Label("Volume (dBFS)", xLeft, heightScale + 4.5f, zFront, true)
        out += Label("Time", xRight + 4f, 0f, 0f, true)
        return out
    }

    private fun setupBars() {
        val cube = floatArrayOf(
            -.5f, 1f, -.5f, 0f, 1f, 0f, -.5f, 1f, .5f, 0f, 1f, 0f, .5f, 1f, .5f, 0f, 1f, 0f,
            -.5f, 1f, -.5f, 0f, 1f, 0f, .5f, 1f, .5f, 0f, 1f, 0f, .5f, 1f, -.5f, 0f, 1f, 0f,
            -.5f, 0f, .5f, 0f, 0f, 1f, .5f, 0f, .5f, 0f, 0f, 1f, .5f, 1f, .5f, 0f, 0f, 1f,
            -.5f, 0f, .5f, 0f, 0f, 1f, .5f, 1f, .5f, 0f, 0f, 1f, -.5f, 1f, .5f, 0f, 0f, 1f,
            .5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 1f, -.5f, 0f, 0f, -1f,
            .5f, 0f, -.5f, 0f, 0f, -1f, -.5f, 1f, -.5f, 0f, 0f, -1f, .5f, 1f, -.5f, 0f, 0f, -1f,
            -.5f, 0f, -.5f, -1f, 0f, 0f, -.5f, 0f, .5f, -1f, 0f, 0f, -.5f, 1f, .5f, -1f, 0f, 0f,
            -.5f, 0f, -.5f, -1f, 0f, 0f, -.5f, 1f, .5f, -1f, 0f, 0f, -.5f, 1f, -.5f, -1f, 0f, 0f,
            .5f, 0f, .5f, 1f, 0f, 0f, .5f, 0f, -.5f, 1f, 0f, 0f, .5f, 1f, -.5f, 1f, 0f, 0f,
            .5f, 0f, .5f, 1f, 0f, 0f, .5f, 1f, -.5f, 1f, 0f, 0f, .5f, 1f, .5f, 1f, 0f, 0f,
        )
        val cells = FloatArray(2 * cols * rows * 3)
        for (side in 0..1) for (r in 0 until rows) for (c in 0 until cols) {
            val i = ((side * rows + r) * cols + c) * 3
            cells[i] = c.toFloat(); cells[i + 1] = r.toFloat(); cells[i + 2] = if (side == 0) 1f else -1f
        }
        barVao = glGenVertexArrays()
        glBindVertexArray(barVao)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, cube, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, cells, GL_STATIC_DRAW)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, false, 0, 0)
        glVertexAttribDivisor(2, 1)
        levelVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, levelVbo)
        glBufferData(GL_ARRAY_BUFFER, levels.size * 4L, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 1, GL_FLOAT, false, 0, 0)
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
        for (c in 0..cols step 8) line(c - halfW, 0f, back, c - halfW, 0f, front, 0.18f)
        for (r in 0..rows step 8) line(-halfW, 0f, front - r, halfW, 0f, front - r, 0.18f)
        for (hz in FREQ_TICKS) {
            if (hz < feed.minHz || hz > feed.maxHz) continue
            val x = bandX(hz)
            line(x, 0f, front, x, 0f, front + 1.2f, 0.7f)
        }
        line(xLeft, -heightScale, front, xLeft, heightScale, front, 0.7f)
        for (db in DB_TICKS) {
            val h = tickFraction(db) * heightScale
            for (y in floatArrayOf(h, -h)) {
                line(xLeft - 0.8f, y, front, xLeft, y, front, 0.7f)
                line(-halfW, y, back, halfW, y, back, 0.10f)
            }
        }
        line(xRight, 0f, front, xRight, 0f, back, 0.7f)
        val data = v.toFloatArray()
        lineVertexCount = data.size / 4
        lineVao = glGenVertexArrays()
        glBindVertexArray(lineVao)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12)
        glBindVertexArray(0)
    }

    companion object {
        private const val CUBE_VERTS = 30
        private const val TICK_FLOOR_DB = -90f
        private const val TICK_CEIL_DB = -10f
        private const val DEFAULT_YAW = -28f
        private const val DEFAULT_PITCH = 26f
        private const val TOUR_MIN_SEGMENT_MS = 6_000L
        private const val TOUR_EXTRA_SEGMENT_MS = 5_000L
        private const val TOUR_RESUME_AFTER_MS = 8_000L
        private val FREQ_TICKS = floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
        private val DB_TICKS = intArrayOf(-80, -60, -40, -20)

        fun buildProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = glCreateShader(type)
                glShaderSource(s, src)
                glCompileShader(s)
                check(glGetShaderi(s, GL_COMPILE_STATUS) != 0) { "shader compile failed: ${glGetShaderInfoLog(s)}" }
                return s
            }
            val p = glCreateProgram()
            glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
            glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(p)
            check(glGetProgrami(p, GL_LINK_STATUS) != 0) { "link failed: ${glGetProgramInfoLog(p)}" }
            return p
        }

        private const val BAR_VS = """#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aCell;   // x = band, y = row (0 = newest), z = side (+1 L, -1 R)
layout(location = 3) in float aLevel; // 0..1
uniform mat4 uMvp;
uniform vec2 uGrid;
uniform float uHeight;
uniform vec3 uStops[8];
out vec3 vColor;

vec3 palette(float t) {
    t = clamp(t, 0.0, 1.0) * 7.0;
    int i = int(min(floor(t), 6.0));
    return mix(uStops[i], uStops[i + 1], t - float(i));
}

void main() {
    float h = max(aLevel, 0.004) * uHeight;
    float x = aCell.x - uGrid.x * 0.5 + 0.5 + aPos.x * 0.78;
    float z = uGrid.y * 0.5 - 0.5 - aCell.y + aPos.z * 0.78;
    gl_Position = uMvp * vec4(x, aPos.y * h * aCell.z, z, 1.0);

    vec3 normal = vec3(aNormal.x, aNormal.y * aCell.z, aNormal.z);
    float shade = 0.45 + 0.55 * max(dot(normal, normalize(vec3(-0.4, 1.0, 0.6))), 0.0);
    if (aCell.z < 0.0) shade = 0.35 + 0.65 * max(dot(normal, normalize(vec3(-0.4, -1.0, 0.6))), 0.0);
    float fade = 1.0 - 0.65 * (aCell.y / uGrid.y);
    vColor = palette(aLevel) * mix(0.55, 1.0, aPos.y) * shade * fade;
}
"""
        private const val BAR_FS = """#version 330 core
in vec3 vColor;
out vec4 fragColor;
void main() { fragColor = vec4(vColor, 1.0); }
"""
        private const val LINE_VS = """#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aAlpha;
uniform mat4 uMvp;
out float vAlpha;
void main() { vAlpha = aAlpha; gl_Position = uMvp * vec4(aPos, 1.0); }
"""
        private const val LINE_FS = """#version 330 core
in float vAlpha;
out vec4 fragColor;
void main() { fragColor = vec4(0.75, 0.82, 0.95, vAlpha); }
"""
    }
}
