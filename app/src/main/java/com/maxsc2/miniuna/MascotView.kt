package com.maxsc2.miniuna

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var core = Color.rgb(26, 132, 252)
    private var mid = Color.rgb(72, 156, 254)
    private var rim = Color.rgb(130, 188, 255)
    private var aura = Color.rgb(74, 157, 248)
    private var emotion = "neutral"
    private var tracking = true
    private var breathing = true
    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var blinkUntil = 0L
    private var lastBlink = System.currentTimeMillis()
    private var phase = 0.0
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        postInvalidateOnAnimation()
    }

    fun setPalette(core: Int, mid: Int, rim: Int, aura: Int) {
        this.core = core
        this.mid = mid
        this.rim = rim
        this.aura = aura
        invalidate()
    }

    fun setEmotion(value: String) {
        emotion = value
        invalidate()
    }

    fun setTracking(enabled: Boolean) {
        tracking = enabled
        if (!enabled) {
            targetX = 0f
            targetY = 0f
        }
    }

    fun setBreathing(enabled: Boolean) {
        breathing = enabled
    }

    fun blink() {
        blinkUntil = System.currentTimeMillis() + 170L
        lastBlink = System.currentTimeMillis()
        invalidate()
    }

    fun randomBlinkIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBlink > 2600L + ((now / 97L) % 2200L)) blink()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.018
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.30f
        val breathingScale = if (breathing) 1f + sin(phase * 1.7).toFloat() * 0.012f else 1f

        shadowPaint.shader = RadialGradient(cx, cy + radius * 0.98f, radius * 0.72f,
            Color.argb(105, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawOval(cx - radius * 0.72f, cy + radius * 0.83f,
            cx + radius * 0.72f, cy + radius * 1.02f, shadowPaint)

        auraPaint.shader = RadialGradient(cx, cy, radius * 1.28f,
            Color.argb(125, Color.red(aura), Color.green(aura), Color.blue(aura)),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.22f, auraPaint)

        canvas.save()
        canvas.scale(breathingScale, breathingScale, cx, cy)

        spherePaint.shader = RadialGradient(
            cx - radius * 0.12f, cy - radius * 0.20f, radius * 1.25f,
            intArrayOf(core, mid, rim),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        spherePaint.setShadowLayer(radius * 0.22f, 0f, radius * 0.10f,
            Color.argb(90, Color.red(aura), Color.green(aura), Color.blue(aura)))
        setLayerType(LAYER_TYPE_SOFTWARE, spherePaint)
        canvas.drawCircle(cx, cy, radius, spherePaint)
        spherePaint.clearShadowLayer()

        val e = emotionParams()
        gazeX += (targetX - gazeX) * 0.12f
        gazeY += (targetY - gazeY) * 0.12f

        val faceX = gazeX * 30f * e.look
        val faceY = gazeY * 27f * e.look + e.droop
        val faceRotation = gazeX * gazeY * 9f + e.tilt
        canvas.save()
        canvas.rotate(faceRotation, cx, cy)
        canvas.translate(faceX, faceY)

        val eyeW = radius * 0.125f * e.sx
        val eyeH = radius * 0.24f * e.sy
        val gap = radius * 0.36f
        val perspective = min(0.52f, abs(gazeX) * 0.48f)
        val leftScale = 1f - perspective * if (gazeX > 0) 0.65f else 0.12f
        val rightScale = 1f - perspective * if (gazeX < 0) 0.65f else 0.12f
        val lid = if (System.currentTimeMillis() < blinkUntil) 0.08f else 1f

        drawEye(canvas, cx - gap / 2f, cy - radius * 0.24f, eyeW, eyeH * leftScale * lid)
        drawEye(canvas, cx + gap / 2f, cy - radius * 0.24f, eyeW, eyeH * rightScale * lid)
        canvas.restore()
        canvas.restore()

        randomBlinkIfNeeded()
        postInvalidateOnAnimation()
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        eyePaint.shader = LinearGradient(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f,
            Color.WHITE, Color.rgb(238, 242, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f,
            w, w, eyePaint)
    }

    private data class E(val sy: Float, val tilt: Float, val droop: Float, val look: Float, val sx: Float = 1f)

    private fun emotionParams(): E = when (emotion) {
        "happy" -> E(.46f, 0f, -2f, 1.25f)
        "surprised" -> E(1.30f, 0f, 0f, 1.55f, 1.12f)
        "smirk" -> E(.80f, -10f, 0f, 1.10f)
        "grin" -> E(.55f, 0f, 0f, 1.20f)
        "shy" -> E(.65f, 10f, 3f, .72f)
        "sad" -> E(.84f, 14f, 7f, .42f)
        "angry" -> E(.80f, -18f, -1f, 1.18f)
        "sleepy" -> E(.12f, 0f, 3f, .15f)
        else -> E(1f, 0f, 0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    targetX = ((event.x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                    targetY = ((event.y - height / 2f) / (height / 2f)).coerceIn(-1f, 1f)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                blink()
                targetX *= 0.35f
                targetY *= 0.35f
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
