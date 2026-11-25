package io.github.jamieharwood.soundvisual.desktop

import io.github.jamieharwood.soundvisual.Palette
import io.github.jamieharwood.soundvisual.desktop.Overlay2D.Style
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess

fun main() {
    System.setProperty("java.awt.headless", "true")
    SoundVisualApp().run()
}

/** The desktop window: the 3D grid plus a small set of on-screen controls. */
class SoundVisualApp {
    private enum class ViewMode(val label: String) { TOUCH("Mouse"), AUTO("Auto") }
    private enum class Menu { NONE, SOURCE, COLOURS, HELP }

    /** A clickable screen area registered while drawing the current frame. */
    private class Hit(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val onPress: (Float, Float) -> Unit,
        val onDrag: ((Float, Float) -> Unit)? = null,
    ) {
        fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
    }

    private val prefs = Preferences.userRoot().node("io/github/jamieharwood/soundvisual")
    private val analyzer = DesktopAnalyzer()
    private val renderer = DesktopGridRenderer(analyzer)
    private val overlay = Overlay2D()
    private val mainThread = ConcurrentLinkedQueue<Runnable>()

    private var window = 0L
    private var fbWidth = 1
    private var fbHeight = 1
    /** Framebuffer pixels per window coordinate (cursor positions come in window coordinates). */
    private var cursorScale = 1f
    /** UI scale: the desktop's content scale (HiDPI). */
    private var ui = 1f

    private var source: AudioSource = AudioSource.SystemOutput
    private var sourceError: String? = null
    private var notice: String? = null
    private var noticeUntilMs = 0L
    private var viewMode = ViewMode.TOUCH
    private var menu = Menu.NONE
    private var levelHidden = false
    private var apps: List<AudioSource.App> = emptyList()

    private var hits = mutableListOf<Hit>()
    private var activeDrag: Hit? = null
    private var sceneDrag = 0 // 0 none, 1 orbit, 2 pan
    private var lastX = 0f
    private var lastY = 0f
    private var lastClickMs = 0L

    private var fullscreen = false
    private val windowed = IntArray(4)

    fun run() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "Couldn't initialise GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_SAMPLES, 4)
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE)
        glfwWindowHintString(GLFW_X11_CLASS_NAME, "sound-visual")
        glfwWindowHintString(GLFW_WAYLAND_APP_ID, "sound-visual")
        window = glfwCreateWindow(1280, 800, "Sound Visual", 0L, 0L)
        if (window == 0L) {
            System.err.println("sound-visual: couldn't open an OpenGL 3.3 window")
            exitProcess(1)
        }
        setIcon()
        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        GL.createCapabilities()
        glEnable(GL_MULTISAMPLE)
        glClearColor(0.043f, 0.055f, 0.078f, 1f)

        renderer.init()
        overlay.init()
        installCallbacks()
        loadSettings()
        renderer.setHistorySeconds(analyzer.historySeconds)
        analyzer.onCaptureLost = { lost -> mainThread += Runnable { captureLost(lost) } }
        startSource(source)

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            while (true) mainThread.poll()?.run() ?: break
            frame()
            glfwSwapBuffers(window)
        }
        analyzer.stop()
        glfwFreeCallbacks(window)
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    // --- Settings ------------------------------------------------------------------------

    private fun loadSettings() {
        source = when (prefs.get("source", "output")) {
            "mic" -> AudioSource.Microphone
            else -> if (PipeWire.recorder != null) AudioSource.SystemOutput else AudioSource.Microphone
        }
        renderer.palette = Palette.fromName(prefs.get("palette", null))
        viewMode = runCatching { ViewMode.valueOf(prefs.get("view_mode", "TOUCH")) }.getOrDefault(ViewMode.TOUCH)
        renderer.autoCamera = viewMode == ViewMode.AUTO
        levelHidden = prefs.getBoolean("level_hidden", false)
    }

    private fun loadLevel() {
        analyzer.gainDb = prefs.getFloat("gain_${source.key}", 0f)
        analyzer.autoLevel = prefs.getBoolean("auto_${source.key}", true)
    }

    private fun saveLevel() {
        prefs.putFloat("gain_${source.key}", analyzer.gainDb)
        prefs.putBoolean("auto_${source.key}", analyzer.autoLevel)
    }

    // --- Sources -------------------------------------------------------------------------

    private fun startSource(s: AudioSource) {
        source = s
        if (s !is AudioSource.App) prefs.put("source", s.key)
        loadLevel()
        sourceError = analyzer.start(s)
        sourceError?.let { System.err.println("sound-visual: $it") }
    }

    private fun captureLost(lost: AudioSource) {
        if (lost != source) return
        if (lost is AudioSource.App) {
            showNotice("${lost.appName} stopped playing — showing ${AudioSource.SystemOutput.label.lowercase()}")
            startSource(AudioSource.SystemOutput)
        } else {
            sourceError = "${lost.label} stopped. Press S to pick a source."
        }
    }

    private fun showNotice(text: String) {
        notice = text
        noticeUntilMs = System.currentTimeMillis() + 5000
    }

    private fun openMenu(m: Menu) {
        menu = if (menu == m) Menu.NONE else m
        if (menu == Menu.SOURCE) apps = PipeWire.playingApps()
    }

    private fun sourceChoices(): List<AudioSource> = buildList {
        add(AudioSource.Microphone)
        if (PipeWire.recorder != null) { add(AudioSource.SystemOutput); addAll(apps) }
    }

    private fun setGain(db: Float) {
        analyzer.autoLevel = false
        analyzer.gainDb = (db * 2f).roundToInt() / 2f
        analyzer.gainDb = analyzer.gainDb.coerceIn(DesktopAnalyzer.MIN_GAIN_DB, DesktopAnalyzer.MAX_GAIN_DB)
        saveLevel()
    }

    private fun setViewMode(m: ViewMode) {
        viewMode = m
        renderer.autoCamera = m == ViewMode.AUTO
        prefs.put("view_mode", m.name)
    }

    private fun setPalette(p: Palette) {
        renderer.palette = p
        prefs.put("palette", p.name)
    }

    // --- Input ---------------------------------------------------------------------------

    private fun installCallbacks() {
        glfwSetFramebufferSizeCallback(window) { _, _, _ -> updateSizes() }
        glfwSetWindowContentScaleCallback(window) { _, _, _ -> updateSizes() }
        updateSizes()

        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            val (x, y) = cursor()
            if (action == GLFW_PRESS) mousePressed(button, x, y)
            if (action == GLFW_RELEASE) { activeDrag = null; sceneDrag = 0 }
        }
        glfwSetCursorPosCallback(window) { _, wx, wy ->
            val x = wx.toFloat() * cursorScale
            val y = wy.toFloat() * cursorScale
            val dx = x - lastX
            val dy = y - lastY
            lastX = x; lastY = y
            activeDrag?.onDrag?.invoke(x, y)
            when (sceneDrag) {
                1 -> {
                    renderer.userInteracted()
                    renderer.yawDeg -= dx * 0.3f / ui
                    renderer.pitchDeg = (renderer.pitchDeg + dy * 0.3f / ui).coerceIn(-85f, 85f)
                }
                2 -> pan(dx, dy)
            }
        }
        glfwSetScrollCallback(window) { _, _, sy ->
            if (menu != Menu.NONE) return@glfwSetScrollCallback
            renderer.userInteracted()
            renderer.distance = (renderer.distance * 0.9f.pow(sy.toFloat()))
                .coerceIn(renderer.minDistance, renderer.maxDistance)
        }
        glfwSetKeyCallback(window) { _, key, _, action, mods ->
            if (action == GLFW_PRESS || action == GLFW_REPEAT) keyPressed(key, mods, action == GLFW_REPEAT)
        }
    }

    private fun updateSizes() {
        MemoryStack.stackPush().use { stack ->
            val w = stack.mallocInt(1); val h = stack.mallocInt(1)
            glfwGetFramebufferSize(window, w, h)
            fbWidth = w[0].coerceAtLeast(1); fbHeight = h[0].coerceAtLeast(1)
            glfwGetWindowSize(window, w, h)
            cursorScale = fbWidth.toFloat() / w[0].coerceAtLeast(1)
            val sx = stack.mallocFloat(1); val sy = stack.mallocFloat(1)
            glfwGetWindowContentScale(window, sx, sy)
            // On X11 the framebuffer already matches the content scale; on Wayland the
            // framebuffer is scaled separately, so use whichever is larger.
            ui = maxOf(sx[0], cursorScale, 1f)
        }
    }

    private fun cursor(): Pair<Float, Float> = MemoryStack.stackPush().use { stack ->
        val x = stack.mallocDouble(1); val y = stack.mallocDouble(1)
        glfwGetCursorPos(window, x, y)
        (x[0].toFloat() * cursorScale) to (y[0].toFloat() * cursorScale)
    }

    private fun mousePressed(button: Int, x: Float, y: Float) {
        lastX = x; lastY = y
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            val hit = hits.lastOrNull { it.contains(x, y) }
            if (hit != null) {
                hit.onPress(x, y)
                if (hit.onDrag != null) activeDrag = hit
                return
            }
            if (menu != Menu.NONE) { menu = Menu.NONE; return }
            val now = System.currentTimeMillis()
            if (now - lastClickMs < 350) {
                renderer.resetCamera(); renderer.userInteracted()
                lastClickMs = 0L
                return
            }
            lastClickMs = now
            sceneDrag = 1
        } else if (button == GLFW_MOUSE_BUTTON_RIGHT || button == GLFW_MOUSE_BUTTON_MIDDLE) {
            if (menu != Menu.NONE) { menu = Menu.NONE; return }
            sceneDrag = 2
        }
    }

    /** Drag the grid across the screen: move the camera's target along the floor. */
    private fun pan(dx: Float, dy: Float) {
        renderer.userInteracted()
        val yaw = Math.toRadians(renderer.yawDeg.toDouble())
        val k = renderer.distance * 0.0016f / ui
        val rightX = cos(yaw).toFloat(); val rightZ = -sin(yaw).toFloat()
        val fwdX = -sin(yaw).toFloat(); val fwdZ = -cos(yaw).toFloat()
        renderer.panX = (renderer.panX - (rightX * dx - fwdX * dy) * k).coerceIn(-40f, 40f)
        renderer.panZ = (renderer.panZ - (rightZ * dx - fwdZ * dy) * k).coerceIn(-40f, 40f)
    }

    private fun keyPressed(key: Int, mods: Int, repeat: Boolean) {
        val ctrl = mods and GLFW_MOD_CONTROL != 0
        if (menu == Menu.SOURCE || menu == Menu.COLOURS) {
            val n = when (key) {
                in GLFW_KEY_1..GLFW_KEY_9 -> key - GLFW_KEY_1
                in GLFW_KEY_KP_1..GLFW_KEY_KP_9 -> key - GLFW_KEY_KP_1
                else -> -1
            }
            if (n >= 0) {
                if (menu == Menu.SOURCE) sourceChoices().getOrNull(n)?.let { startSource(it) }
                else Palette.entries.getOrNull(n)?.let { setPalette(it) }
                menu = Menu.NONE
                return
            }
        }
        when (key) {
            GLFW_KEY_ESCAPE -> when {
                menu != Menu.NONE -> menu = Menu.NONE
                fullscreen -> toggleFullscreen()
            }
            GLFW_KEY_Q -> if (ctrl) glfwSetWindowShouldClose(window, true)
            GLFW_KEY_S -> if (!repeat) openMenu(Menu.SOURCE)
            GLFW_KEY_C -> if (!repeat) openMenu(Menu.COLOURS)
            GLFW_KEY_H, GLFW_KEY_F1, GLFW_KEY_SLASH -> if (!repeat) openMenu(Menu.HELP)
            GLFW_KEY_V -> setViewMode(if (viewMode == ViewMode.TOUCH) ViewMode.AUTO else ViewMode.TOUCH)
            GLFW_KEY_A -> { analyzer.autoLevel = !analyzer.autoLevel; if (!analyzer.autoLevel) analyzer.gainDb = analyzer.effectiveGainDb; saveLevel() }
            GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD, GLFW_KEY_UP -> setGain(currentGain() + 1f)
            GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT, GLFW_KEY_DOWN -> setGain(currentGain() - 1f)
            GLFW_KEY_L -> { levelHidden = !levelHidden; prefs.putBoolean("level_hidden", levelHidden) }
            GLFW_KEY_R, GLFW_KEY_HOME -> { renderer.resetCamera(); renderer.userInteracted() }
            GLFW_KEY_SPACE -> analyzer.paused = !analyzer.paused
            GLFW_KEY_F, GLFW_KEY_F11 -> if (!repeat) toggleFullscreen()
        }
    }

    private fun currentGain() = if (analyzer.autoLevel) analyzer.effectiveGainDb else analyzer.gainDb

    private fun toggleFullscreen() {
        if (!fullscreen) {
            MemoryStack.stackPush().use { s ->
                val x = s.mallocInt(1); val y = s.mallocInt(1); val w = s.mallocInt(1); val h = s.mallocInt(1)
                glfwGetWindowPos(window, x, y); glfwGetWindowSize(window, w, h)
                windowed[0] = x[0]; windowed[1] = y[0]; windowed[2] = w[0]; windowed[3] = h[0]
            }
            val monitor = glfwGetPrimaryMonitor()
            val mode = glfwGetVideoMode(monitor) ?: return
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate())
        } else {
            glfwSetWindowMonitor(window, 0L, windowed[0], windowed[1], windowed[2], windowed[3], GLFW_DONT_CARE)
        }
        fullscreen = !fullscreen
        glfwSwapInterval(1)
        updateSizes()
    }

    private fun setIcon() {
        try {
            val img = javaClass.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) } ?: return
            val w = img.width; val h = img.height
            val buf = MemoryUtil.memAlloc(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                buf.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
            }
            buf.flip()
            GLFWImage.malloc(1).use { images ->
                images[0].set(w, h, buf)
                glfwSetWindowIcon(window, images)
            }
            MemoryUtil.memFree(buf)
        } catch (e: Exception) {
            // Wayland doesn't support window icons; the .desktop file provides it there.
        }
    }

    // --- Drawing -------------------------------------------------------------------------

    private fun frame() {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        renderer.draw(fbWidth, fbHeight)
        overlay.ensureScale(ui)
        hits = mutableListOf()
        drawLabels()
        drawHud()
        drawToolbar()
        drawLevelPanel()
        when (menu) {
            Menu.SOURCE -> drawSourceMenu()
            Menu.COLOURS -> drawColourMenu()
            Menu.HELP -> drawHelp()
            Menu.NONE -> Unit
        }
        overlay.flush(fbWidth, fbHeight)
    }

    private fun drawLabels() {
        val pos = renderer.labelScreen
        renderer.labels.forEachIndexed { i, label ->
            if (pos[i * 3 + 2] == 0f) return@forEachIndexed
            val style = if (label.title) Style.TITLE else Style.TICK
            val color = if (label.title) 0xEBDCE6FA.toInt() else 0xC8BECDE6.toInt()
            overlay.text(style, label.text, pos[i * 3], pos[i * 3 + 1] + overlay.ascent(style) / 3f, color, 0.5f)
        }
    }

    private fun drawHud() {
        val pad = 16f * ui
        var y = pad + overlay.ascent(Style.HUD)
        val step = overlay.lineHeight(Style.HUD) * 1.15f
        val err = sourceError
        if (err != null) {
            overlay.text(Style.HUD, err, pad, y, WHITE)
        } else {
            val peak = analyzer.peakHz
            val peakText = if (peak >= 1000) "%.2f kHz".format(peak / 1000) else "%.0f Hz".format(peak)
            overlay.text(Style.HUD, "Source  ${source.label}", pad, y, WHITE); y += step
            overlay.text(Style.HUD, "Peak  $peakText", pad, y, WHITE); y += step
            overlay.text(Style.HUD, "Level  %.1f dBFS".format(analyzer.rmsDb), pad, y, WHITE); y += step
            overlay.text(
                Style.HUD,
                if (analyzer.stereo) "Channels  Left ▲  Right ▼" else "Channels  Mono (both sides alike)",
                pad, y, WHITE,
            )
            if (analyzer.paused) {
                y += step
                overlay.text(Style.HUD, "PAUSED  (Space to resume)", pad, y, WHITE)
            } else if (System.currentTimeMillis() - analyzer.lastSoundAtMs > 3000) {
                y += overlay.lineHeight(Style.HINT) * 1.6f
                overlay.text(Style.HINT, "No sound yet. Play something, or press S to pick another source.", pad, y, HINT)
            }
        }
        if (notice != null && System.currentTimeMillis() < noticeUntilMs) {
            val n = notice!!
            val w = overlay.textWidth(Style.HUD, n) + 32f * ui
            val h = overlay.lineHeight(Style.HUD) + 16f * ui
            val x = (fbWidth - w) / 2f
            val ny = fbHeight - 90f * ui
            overlay.rect(x, ny, w, h, PANEL)
            overlay.text(Style.HUD, n, fbWidth / 2f, ny + 8f * ui + overlay.ascent(Style.HUD), WHITE, 0.5f)
        }
        overlay.text(
            Style.HINT,
            "Drag to rotate · right-drag to pan · scroll to zoom · double-click to reset · H for keys",
            pad, fbHeight - pad, HINT,
        )
    }

    /** A clickable button; returns its width. */
    private fun button(text: String, x: Float, y: Float, active: Boolean = false, onPress: () -> Unit): Float {
        val padX = 12f * ui
        val h = buttonHeight()
        val w = overlay.textWidth(Style.MENU, text) + padX * 2
        overlay.rect(x, y, w, h, if (active) ACTIVE else BUTTON)
        overlay.text(Style.MENU, text, x + padX, y + (h + overlay.ascent(Style.MENU) * 0.72f) / 2f, WHITE)
        hits += Hit(x, y, w, h, { _, _ -> onPress() })
        return w
    }

    private fun buttonWidth(text: String) = overlay.textWidth(Style.MENU, text) + 24f * ui
    private fun buttonHeight() = overlay.lineHeight(Style.MENU) + 12f * ui

    private fun drawToolbar() {
        val labels = listOf("Source ▾", "View: ${viewMode.label}", "Colours ▾", "?")
        val gap = 8f * ui
        var x = fbWidth - 16f * ui - labels.sumOf { buttonWidth(it).toDouble() }.toFloat() - gap * (labels.size - 1)
        val y = 16f * ui
        x += button(labels[0], x, y, menu == Menu.SOURCE) { openMenu(Menu.SOURCE) } + gap
        x += button(labels[1], x, y, viewMode == ViewMode.AUTO) {
            setViewMode(if (viewMode == ViewMode.TOUCH) ViewMode.AUTO else ViewMode.TOUCH)
        } + gap
        x += button(labels[2], x, y, menu == Menu.COLOURS) { openMenu(Menu.COLOURS) } + gap
        button(labels[3], x, y, menu == Menu.HELP) { openMenu(Menu.HELP) }
    }

    private fun drawLevelPanel() {
        val margin = 16f * ui
        if (levelHidden) {
            val text = "Level ▴"
            val w = buttonWidth(text)
            button(text, fbWidth - margin - w, fbHeight - margin - buttonHeight()) {
                levelHidden = false; prefs.putBoolean("level_hidden", false)
            }
            return
        }
        val h = buttonHeight()
        val panelW = 440f * ui
        val panelH = h + 20f * ui
        val px = fbWidth - margin - panelW
        val py = fbHeight - margin - panelH
        overlay.rect(px, py, panelW, panelH, PANEL)
        hits += Hit(px, py, panelW, panelH, { _, _ -> }) // swallow clicks on the panel
        val by = py + 10f * ui
        var x = px + 10f * ui
        val baseline = by + (h + overlay.ascent(Style.MENU) * 0.72f) / 2f
        overlay.text(Style.MENU, "Level", x, baseline, WHITE)
        x += overlay.textWidth(Style.MENU, "Level") + 10f * ui
        x += button("Auto", x, by, analyzer.autoLevel) {
            analyzer.autoLevel = !analyzer.autoLevel
            if (!analyzer.autoLevel) analyzer.gainDb = analyzer.effectiveGainDb
            saveLevel()
        } + 14f * ui

        val closeW = buttonWidth("✕")
        val valueW = overlay.textWidth(Style.MENU, "+30.0 dB") + 16f * ui
        val trackX = x + 8f * ui
        val trackW = px + panelW - 10f * ui - closeW - valueW - trackX - 8f * ui
        val gain = currentGain()
        val range = DesktopAnalyzer.MAX_GAIN_DB - DesktopAnalyzer.MIN_GAIN_DB
        val f = (gain - DesktopAnalyzer.MIN_GAIN_DB) / range
        val cy = by + h / 2f
        overlay.rect(trackX, cy - 2f * ui, trackW, 4f * ui, 0x55FFFFFF)
        overlay.rect(trackX + trackW / 2f - 1f * ui, cy - 7f * ui, 2f * ui, 14f * ui, 0x55FFFFFF)
        val knob = 16f * ui
        overlay.rect(trackX + f * trackW - knob / 2f, cy - knob / 2f, knob, knob, if (analyzer.autoLevel) 0xFF7A8699.toInt() else ACCENT)
        val drag = { mx: Float, _: Float -> setGain(DesktopAnalyzer.MIN_GAIN_DB + ((mx - trackX) / trackW).coerceIn(0f, 1f) * range) }
        hits += Hit(trackX - knob, by, trackW + knob * 2, h, drag, drag)
        overlay.text(Style.MENU, "%+.1f dB".format(gain), trackX + trackW + 4f * ui + valueW, baseline, WHITE, 1f)
        button("✕", px + panelW - 10f * ui - closeW, by) {
            levelHidden = true; prefs.putBoolean("level_hidden", true)
        }
    }

    private fun menuPanel(title: String, rows: Int, width: Float): Triple<Float, Float, Float> {
        val rowH = overlay.lineHeight(Style.MENU) + 14f * ui
        val headH = overlay.lineHeight(Style.HEADING) + 20f * ui
        val w = width
        val h = headH + rows * rowH + 12f * ui
        val x = fbWidth - 16f * ui - w
        val y = 16f * ui + buttonHeight() + 8f * ui
        overlay.rect(x, y, w, h, MENU_BG)
        hits += Hit(x, y, w, h, { _, _ -> })
        overlay.text(Style.HEADING, title, x + 16f * ui, y + 12f * ui + overlay.ascent(Style.HEADING), WHITE)
        return Triple(x, y + headH, rowH)
    }

    private fun menuRow(x: Float, y: Float, w: Float, h: Float, selected: Boolean, onPress: () -> Unit) {
        if (selected) overlay.rect(x + 6f * ui, y, w - 12f * ui, h, ACTIVE)
        hits += Hit(x, y, w, h, { _, _ -> onPress(); menu = Menu.NONE })
    }

    private fun drawSourceMenu() {
        val choices = sourceChoices()
        val extra = if (PipeWire.recorder == null) 1 else if (apps.isEmpty()) 1 else 0
        val w = 420f * ui
        val (x, top, rowH) = menuPanel("Sound source", choices.size + extra + if (apps.isNotEmpty()) 1 else 0, w)
        var y = top
        choices.forEachIndexed { i, s ->
            if (s is AudioSource.App && i == 2) {
                overlay.text(Style.HINT, "Apps playing now", x + 16f * ui, y + rowH * 0.7f, HINT)
                y += rowH
            }
            menuRow(x, y, w, rowH, s == source) { startSource(s) }
            val name = when (s) {
                AudioSource.SystemOutput -> "Computer output (all apps)"
                is AudioSource.App -> s.appName
                else -> s.label
            }
            overlay.text(Style.MENU, "${i + 1}   $name", x + 16f * ui, y + rowH * 0.68f, WHITE)
            y += rowH
        }
        if (PipeWire.recorder == null) {
            overlay.text(Style.HINT, "Install pipewire-bin to capture what the computer plays", x + 16f * ui, y + rowH * 0.7f, HINT)
        } else if (apps.isEmpty()) {
            overlay.text(Style.HINT, "Start playing in an app to capture it on its own", x + 16f * ui, y + rowH * 0.7f, HINT)
        }
    }

    private fun drawColourMenu() {
        val w = 360f * ui
        val (x, top, rowH) = menuPanel("Colours", Palette.entries.size, w)
        var y = top
        Palette.entries.forEachIndexed { i, p ->
            menuRow(x, y, w, rowH, p == renderer.palette) { setPalette(p) }
            val sw = 120f * ui
            val sh = rowH * 0.5f
            val sx = x + 16f * ui
            val sy = y + (rowH - sh) / 2f
            val seg = sw / p.previewColors.size
            p.previewColors.forEachIndexed { k, c -> overlay.rect(sx + k * seg, sy, seg + 0.5f, sh, c) }
            overlay.text(Style.MENU, "${i + 1}   ${p.label}", sx + sw + 16f * ui, y + rowH * 0.68f, WHITE)
            y += rowH
        }
    }

    private fun drawHelp() {
        val keys = listOf(
            "Left-drag" to "Rotate the grid",
            "Right-drag" to "Pan",
            "Scroll" to "Zoom",
            "Double-click, R" to "Reset the view",
            "V" to "Mouse view / Auto view (random tour)",
            "S" to "Sound source",
            "C" to "Colours",
            "A" to "Auto level on/off",
            "+  /  −" to "Raise / lower the level",
            "L" to "Show / hide the level panel",
            "Space" to "Pause",
            "F, F11" to "Full screen",
            "Ctrl+Q" to "Quit",
        )
        val w = 206f * ui + keys.maxOf { overlay.textWidth(Style.MENU, it.second) }
        val (x, top, rowH) = menuPanel("Keys and mouse", keys.size, w)
        var y = top
        for ((k, d) in keys) {
            overlay.text(Style.MENU, k, x + 16f * ui, y + rowH * 0.68f, ACCENT)
            overlay.text(Style.MENU, d, x + 190f * ui, y + rowH * 0.68f, WHITE)
            y += rowH
        }
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val HINT = 0x8CC8D2E6.toInt()
        private const val PANEL = 0xB0141A26.toInt()
        private const val MENU_BG = 0xF0141A26.toInt()
        private const val BUTTON = 0xC0283246.toInt()
        private const val ACTIVE = 0xFF2F6BFF.toInt()
        private const val ACCENT = 0xFF19C3E6.toInt()
    }
}
