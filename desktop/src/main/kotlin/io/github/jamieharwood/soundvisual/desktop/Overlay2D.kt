package io.github.jamieharwood.soundvisual.desktop

import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer

/**
 * Screen-space text and rectangles drawn over the 3D grid. Glyphs are rendered once with
 * Java's font rasteriser (anti-aliased, any Unicode the system font has) into a texture atlas;
 * each frame the overlay batches quads and draws them in one call. Coordinates are
 * framebuffer pixels with y down.
 */
class Overlay2D {
    enum class Style(val points: Float, val bold: Boolean) {
        TICK(11f, false), TITLE(13f, true), HUD(15f, false), HINT(12f, false), MENU(15f, false), HEADING(17f, true)
    }

    private class Glyph(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val w: Int, val h: Int, val advance: Int)
    private class FontData(val glyphs: Map<Char, Glyph>, val ascent: Int, val pad: Int, val lineHeight: Int)

    private var fonts = emptyMap<Style, FontData>()
    private var atlasScale = 0f
    private var texture = 0
    private var whiteU = 0f
    private var whiteV = 0f

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var uScreen = 0
    private var verts = FloatArray(8 * 6 * 2048)
    private var count = 0
    private var gpuBuffer: FloatBuffer = MemoryUtil.memAllocFloat(verts.size)

    fun init() {
        program = DesktopGridRenderer.buildProgram(VS, FS)
        uScreen = glGetUniformLocation(program, "uScreen")
        vao = glGenVertexArrays()
        vbo = glGenBuffers()
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 32, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 32, 8)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16)
        glBindVertexArray(0)
        texture = glGenTextures()
    }

    /** Re-render the atlas when the display scale (HiDPI factor) changes. */
    fun ensureScale(scale: Float) {
        if (scale == atlasScale) return
        atlasScale = scale
        val size = 1024
        val img = BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, 4, 4)
        whiteU = 2f / size; whiteV = 2f / size
        var x = 6
        var y = 0
        var rowH = 6
        val built = HashMap<Style, FontData>()
        for (style in Style.entries) {
            val font = Font(Font.SANS_SERIF, if (style.bold) Font.BOLD else Font.PLAIN, 1)
                .deriveFont(style.points * scale * PT_TO_PX)
            g.font = font
            val fm = g.fontMetrics
            val pad = 2
            val h = fm.ascent + fm.descent + pad * 2
            val glyphs = HashMap<Char, Glyph>()
            for (c in CHARS) {
                if (!font.canDisplay(c)) continue
                val adv = fm.charWidth(c)
                val w = adv + pad * 2
                if (x + w > size) { x = 0; y += rowH; rowH = 0 }
                g.drawString(c.toString(), x + pad, y + pad + fm.ascent)
                glyphs[c] = Glyph(x.toFloat() / size, y.toFloat() / size, (x + w).toFloat() / size, (y + h).toFloat() / size, w, h, adv)
                x += w + 1
                rowH = maxOf(rowH, h + 1)
            }
            built[style] = FontData(glyphs, fm.ascent, pad, fm.height)
        }
        g.dispose()
        val pixels = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
        val buf = MemoryUtil.memAlloc(pixels.size).put(pixels).flip()
        glBindTexture(GL_TEXTURE_2D, texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, size, size, 0, GL_RED, GL_UNSIGNED_BYTE, buf)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        MemoryUtil.memFree(buf)
        fonts = built
    }

    fun lineHeight(style: Style) = fonts.getValue(style).lineHeight.toFloat()
    fun ascent(style: Style) = fonts.getValue(style).ascent.toFloat()

    fun textWidth(style: Style, text: String): Float {
        val f = fonts.getValue(style)
        return text.sumOf { (f.glyphs[it] ?: f.glyphs['?'])?.advance ?: 0 }.toFloat()
    }

    /** Draw [text] with its baseline at [y]; [align] 0 = left, 0.5 = centre, 1 = right. */
    fun text(style: Style, text: String, x: Float, y: Float, argb: Int, align: Float = 0f) {
        val f = fonts.getValue(style)
        var pen = x - textWidth(style, text) * align
        for (c in text) {
            val gl = f.glyphs[c] ?: f.glyphs['?'] ?: continue
            if (c != ' ') {
                // Snap to whole pixels so the atlas glyphs stay crisp.
                val x0 = Math.round(pen).toFloat() - f.pad
                val y0 = Math.round(y).toFloat() - f.ascent - f.pad
                quad(x0, y0, x0 + gl.w, y0 + gl.h, gl.u0, gl.v0, gl.u1, gl.v1, argb)
            }
            pen += gl.advance
        }
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, argb: Int) =
        quad(x, y, x + w, y + h, whiteU, whiteV, whiteU, whiteV, argb)

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, argb: Int) {
        if ((count + 6) * 8 > verts.size) {
            verts = verts.copyOf(verts.size * 2)
            MemoryUtil.memFree(gpuBuffer)
            gpuBuffer = MemoryUtil.memAllocFloat(verts.size)
        }
        val a = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        fun v(x: Float, y: Float, u: Float, vv: Float) {
            val i = count * 8
            verts[i] = x; verts[i + 1] = y; verts[i + 2] = u; verts[i + 3] = vv
            verts[i + 4] = r; verts[i + 5] = g; verts[i + 6] = b; verts[i + 7] = a
            count++
        }
        v(x0, y0, u0, v0); v(x1, y0, u1, v0); v(x1, y1, u1, v1)
        v(x0, y0, u0, v0); v(x1, y1, u1, v1); v(x0, y1, u0, v1)
    }

    /** Draw everything queued since the last flush. */
    fun flush(width: Int, height: Int) {
        if (count == 0) return
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(program)
        glUniform2f(uScreen, width.toFloat(), height.toFloat())
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        gpuBuffer.clear(); gpuBuffer.put(verts, 0, count * 8).flip()
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, gpuBuffer, GL_STREAM_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, count)
        glBindVertexArray(0)
        count = 0
    }

    companion object {
        /** Points → pixels at 96 dpi. */
        private const val PT_TO_PX = 96f / 72f
        private val CHARS = ((32..126).map { it.toChar() } + "−▲▼·✕▴▾°…←→↑↓✓●○".toList()).distinct()

        private const val VS = """#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
uniform vec2 uScreen;
out vec2 vUv;
out vec4 vColor;
void main() {
    vUv = aUv; vColor = aColor;
    gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);
}
"""
        private const val FS = """#version 330 core
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uAtlas;
out vec4 fragColor;
void main() { fragColor = vec4(vColor.rgb, vColor.a * texture(uAtlas, vUv).r); }
"""
    }
}
