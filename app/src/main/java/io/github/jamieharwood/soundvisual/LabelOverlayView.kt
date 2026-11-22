package io.github.jamieharwood.soundvisual

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Draws axis labels (projected by the renderer) and a live readout over the GL view. */
class LabelOverlayView(
    context: Context,
    private val renderer: GridRenderer,
    private val analyzer: AudioAnalyzer,
) : View(context) {

    /** Message shown instead of the readout, e.g. when mic permission is missing. */
    var message: String? = null
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 190, 205, 230)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 220, 230, 250)
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 200, 210, 230)
        textSize = 11f * density
    }
    private val positions = FloatArray(renderer.labels.size * 3)

    override fun onDraw(canvas: Canvas) {
        synchronized(renderer.labelLock) {
            System.arraycopy(renderer.labelScreen, 0, positions, 0, positions.size)
        }
        renderer.labels.forEachIndexed { i, label ->
            if (positions[i * 3 + 2] == 0f) return@forEachIndexed
            val paint = if (label.kind == GridRenderer.Label.Kind.TITLE) titlePaint else tickPaint
            canvas.drawText(label.text, positions[i * 3], positions[i * 3 + 1] + paint.textSize / 3, paint)
        }

        val pad = 16f * density
        var y = pad + hudPaint.textSize
        val msg = message
        if (msg != null) {
            canvas.drawText(msg, pad, y, hudPaint)
        } else {
            val peak = analyzer.peakHz
            val peakText = if (peak >= 1000) "%.2f kHz".format(peak / 1000) else "%.0f Hz".format(peak)
            canvas.drawText("Peak  $peakText", pad, y, hudPaint)
            y += hudPaint.textSize * 1.3f
            canvas.drawText("Level  %.1f dBFS".format(analyzer.rmsDb), pad, y, hudPaint)
            if (analyzer.paused) {
                y += hudPaint.textSize * 1.3f
                canvas.drawText("PAUSED", pad, y, hudPaint)
            }
        }
        canvas.drawText(
            "Drag to rotate · pinch to zoom · tap to pause · double-tap to reset view",
            pad, height - pad, hintPaint,
        )
        postInvalidateOnAnimation()
    }
}
