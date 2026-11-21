package com.koro.kororythm.ui.player

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PRISM light show: a video-feedback visualizer.
 *
 * Every frame the previous frame is drawn again slightly zoomed in, turned and hue-shifted,
 * at a little less than full brightness. New light is added in the middle (rays in a
 * symmetric star, liquid chrome droplets, sparks). Because old light keeps being pushed
 * outward and recoloured, it smears into rainbow streaks racing out of the centre.
 *
 * Bass drives the rays and zoom, mids the droplets, highs the sparks. Rendered at low
 * resolution on the CPU and scaled up, which also softens it into a glow.
 */
class PrismEngine(val width: Int, val height: Int) {
    private var front: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var back: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var frontCanvas = Canvas(front)
    private var backCanvas = Canvas(back)

    private val m = Matrix()
    private val hueShift = ColorMatrix()
    private val boost = ColorMatrix()
    private val feedback = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)
    private val wedge = android.graphics.Path()

    private var hue = 40f
    private var spin = 0f
    private var frame = 0L

    /** The latest frame. */
    val bitmap: Bitmap get() = front

    /**
     * Draws the next frame. [dt] in seconds; energies 0..1 (bass, mids, highs).
     * Deterministic: the same inputs give the same pictures (used by the screenshot tests).
     */
    fun step(dt: Float, bass: Float, mid: Float, high: Float) {
        frame++
        val t = frame / 60f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        hue = (hue + dt * (25f + 90f * high)) % 360f
        spin += dt * (8f + 40f * mid)

        // 1) previous frame, pushed outward, turned and recoloured
        backCanvas.drawColor(Color.BLACK)
        val zoom = 1.028f + 0.05f * bass
        m.reset()
        m.postScale(zoom, zoom * (1.006f + 0.01f * mid), cx, cy)
        m.postRotate(0.35f + 1.8f * (mid - 0.25f) * sin(t * 0.7f), cx, cy)
        // Rotating colour space around the blue axis walks red → yellow → green…: the rainbow bands.
        hueShift.setRotate(2, 1.6f + 3f * high)
        boost.setSaturation(1.07f)
        hueShift.postConcat(boost)
        feedback.colorFilter = ColorMatrixColorFilter(hueShift)
        // High persistence: old light survives long enough to streak all the way to the edges.
        feedback.alpha = (246 + 6 * bass).toInt().coerceIn(240, 252)
        backCanvas.drawBitmap(front, m, feedback)

        // 2a) broad translucent bands of colour turning slowly: the big washes of light
        val bands = 6
        for (i in 0 until bands) {
            val a = (-spin * 0.6f * PI / 180).toFloat() + i * (2 * PI / bands).toFloat()
            val spread = 0.16f + 0.1f * mid
            val far = r * (0.9f + 0.8f * bass)
            wedge.reset()
            wedge.moveTo(cx, cy)
            wedge.lineTo(cx + cos(a - spread) * far, cy + sin(a - spread) * far * 1.4f)
            wedge.lineTo(cx + cos(a + spread) * far, cy + sin(a + spread) * far * 1.4f)
            wedge.close()
            fill.color = color(hue + 60f + i * 60f, 0.95f, 1f)
            fill.alpha = (26 + 60 * bass).toInt()
            backCanvas.drawPath(wedge, fill)
        }

        // 2b) star of rays from the middle, 2 × N-fold symmetric
        val n = 12 + (4 * mid).toInt() * 2
        val len = r * (0.18f + 0.75f * bass)
        for (i in 0 until n) {
            val a = (spin * PI / 180).toFloat() + i * (2 * PI / n).toFloat() + 0.25f * sin(t * 1.3f + i)
            val ex = cx + cos(a) * len
            val ey = cy + sin(a) * len * (height.toFloat() / width).coerceAtMost(1.6f)
            val c = color(hue + i * (360f / n) * 0.5f, 0.85f + 0.15f * high, 1f)
            glow.color = c; glow.alpha = (110 + 140 * bass).toInt().coerceAtMost(240); glow.strokeWidth = r * (0.07f + 0.08f * bass)
            backCanvas.drawLine(cx, cy, ex, ey, glow)
            ray.color = c; ray.alpha = 255; ray.strokeWidth = r * (0.012f + 0.02f * bass)
            backCanvas.drawLine(cx, cy, ex, ey, ray)
        }

        // 3) liquid chrome droplets orbiting the centre
        val drops = 7
        for (k in 0 until drops) {
            val ang = t * (0.6f + 0.13f * k) + k * 2.1f
            val orbit = r * (0.08f + 0.18f * mid) * (0.5f + 0.5f * sin(t * 0.9f + k))
            val x = cx + cos(ang) * orbit
            val y = cy + sin(ang * 1.3f) * orbit
            val rad = r * (0.025f + 0.055f * mid + 0.02f * sin(t * 2f + k))
            if (rad <= 1f) continue
            fill.shader = RadialGradient(
                x - rad * 0.35f, y - rad * 0.35f, rad * 1.25f,
                intArrayOf(Color.WHITE, Color.rgb(205, 215, 230), Color.rgb(90, 110, 150), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP,
            )
            backCanvas.drawCircle(x, y, rad, fill)
        }
        fill.shader = null

        // 4) sparks on the highs
        val sparks = (4 + 40 * high).toInt()
        for (s in 0 until sparks) {
            val seed = (frame * 7919 + s * 104729) % 100_000 / 100_000f
            val a = seed * 2 * PI.toFloat()
            val d = r * (0.1f + 0.9f * ((seed * 13.7f) % 1f))
            fill.color = color(hue + 180f + seed * 120f, 0.4f, 1f)
            fill.alpha = (120 + 135 * high).toInt().coerceAtMost(255)
            backCanvas.drawCircle(cx + cos(a) * d, cy + sin(a) * d, max(1f, r * 0.006f), fill)
        }

        // swap
        val b = front; front = back; back = b
        val c = frontCanvas; frontCanvas = backCanvas; backCanvas = c
    }

    private fun color(h: Float, s: Float, v: Float): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f; hsv[1] = s.coerceIn(0f, 1f); hsv[2] = v.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    fun release() {
        front.recycle(); back.recycle()
    }
}
