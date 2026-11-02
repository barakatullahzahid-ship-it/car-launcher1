package com.example.carlauncher

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.*

/** دایره‌ی وسط: ساعت، سرعت، پخش، قطب‌نما، لوگو */
class Gauge(c: Context, a: AttributeSet?) : View(c, a) {
    companion object { const val CLOCK = 0; const val SPEED = 1; const val MUSIC = 2; const val COMPASS = 3; const val LOGO = 4 }
    var mode = CLOCK
    var accent = Color.rgb(255, 140, 0)
    var speed = 0f; var heading = 0f; var progress = 0f
    var title = ""; var timeText = ""; var logo = "VW"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun txt(cv: Canvas, s: String, x: Float, y: Float, size: Float, col: Int) {
        p.style = Paint.Style.FILL; p.color = col; p.textSize = size; p.textAlign = Paint.Align.CENTER
        cv.drawText(s, x, y, p)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - 6f
        p.style = Paint.Style.FILL; p.color = Color.rgb(18, 18, 18); cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.rgb(45, 45, 45); cv.drawCircle(cx, cy, r, p)

        val n = 60
        val dim = Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent))
        p.strokeWidth = 4f
        for (i in 0 until n) {
            val ang = if (mode == SPEED) 135.0 + 270.0 * i / (n - 1) else 360.0 * i / n - 90.0
            val on = when (mode) {
                SPEED -> i <= min(speed, 240f) / 240f * (n - 1)
                MUSIC -> i < progress * n
                else -> i % 5 == 0
            }
            p.color = if (on) accent else dim
            val rad = Math.toRadians(ang)
            val r1 = r * 0.80f; val r2 = if (i % 5 == 0) r * 0.96f else r * 0.90f
            cv.drawLine(cx + cos(rad).toFloat() * r1, cy + sin(rad).toFloat() * r1,
                        cx + cos(rad).toFloat() * r2, cy + sin(rad).toFloat() * r2, p)
        }

        when (mode) {
            CLOCK -> {
                val k = Calendar.getInstance()
                val h = (k.get(Calendar.HOUR) + k.get(Calendar.MINUTE) / 60f) * 30f - 90f
                val m = (k.get(Calendar.MINUTE) + k.get(Calendar.SECOND) / 60f) * 6f - 90f
                val s = k.get(Calendar.SECOND) * 6f - 90f
                hand(cv, cx, cy, h, r * 0.45f, 8f, Color.WHITE)
                hand(cv, cx, cy, m, r * 0.68f, 5f, Color.WHITE)
                hand(cv, cx, cy, s, r * 0.72f, 3f, accent)
                p.style = Paint.Style.FILL; p.color = accent; cv.drawCircle(cx, cy, 9f, p)
            }
            SPEED -> {
                txt(cv, "${speed.roundToInt()}", cx, cy + r * 0.18f, r * 0.55f, accent)
                txt(cv, "km/h", cx, cy + r * 0.45f, r * 0.14f, Color.LTGRAY)
            }
            MUSIC -> {
                txt(cv, "♫", cx, cy - r * 0.1f, r * 0.4f, accent)
                val tp = TextPaint(p); tp.textSize = r * 0.13f
                val t = TextUtils.ellipsize(title.ifEmpty { "—" }, tp, r * 1.15f, TextUtils.TruncateAt.END).toString()
                txt(cv, t, cx, cy + r * 0.25f, r * 0.13f, Color.WHITE)
                txt(cv, timeText, cx, cy + r * 0.45f, r * 0.12f, Color.LTGRAY)
            }
            COMPASS -> {
                cv.save(); cv.rotate(-heading, cx, cy)
                val path = Path()
                path.moveTo(cx, cy - r * 0.55f); path.lineTo(cx - r * 0.12f, cy); path.lineTo(cx + r * 0.12f, cy); path.close()
                p.style = Paint.Style.FILL; p.color = accent; cv.drawPath(path, p)
                val s = Path()
                s.moveTo(cx, cy + r * 0.55f); s.lineTo(cx - r * 0.12f, cy); s.lineTo(cx + r * 0.12f, cy); s.close()
                p.color = Color.GRAY; cv.drawPath(s, p)
                txt(cv, "N", cx, cy - r * 0.6f, r * 0.15f, Color.WHITE)
                cv.restore()
                txt(cv, "${heading.roundToInt()}°", cx, cy + r * 0.72f, r * 0.12f, Color.LTGRAY)
            }
            LOGO -> txt(cv, logo, cx, cy + r * 0.15f, r * 0.5f, accent)
        }
    }

    private fun hand(cv: Canvas, cx: Float, cy: Float, deg: Float, len: Float, w: Float, col: Int) {
        val rad = Math.toRadians(deg.toDouble())
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.color = col
        cv.drawLine(cx, cy, cx + cos(rad).toFloat() * len, cy + sin(rad).toFloat() * len, p)
    }
}
