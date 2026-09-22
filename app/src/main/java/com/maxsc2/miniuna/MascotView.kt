package com.maxsc2.miniuna

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
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

    private var blinkStarted = 0L
    private var blinkUntil = 0L
    private var nextBlinkAt = System.currentTimeMillis() + 2600L

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cachedRadius = -1f
    private var cachedCx = -1f
    private var cachedCy = -1f
    private var cachedCore = 0
    private var cachedMid = 0
    private var cachedRim = 0
    private var cachedAura = 0

    init {
        isClickable = true
        eyePaint.color = Color.WHITE
        // Keep the mascot on Android's normal hardware renderer. The previous
        // per-frame software layer forced expensive redraws and caused jank.
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
        invalidate()
    }

    fun setBreathing(enabled: Boolean) {
        breathing = enabled
        invalidate()
    }

    fun blink() {
        val now = System.currentTimeMillis()
        blinkStarted = now
        blinkUntil = now + 150L
        scheduleNextBlink(now)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedRadius = -1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.currentTimeMillis()
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.30f

        rebuildShadersIfNeeded(cx, cy, radius)

        val time = now / 1000f
        val breathingScale = if (breathing) {
            1f + sin(time * 1.7f) * 0.010f
        } else {
            1f
        }

        val idleX = sin(time * 0.55f) * 0.035f
        val idleY = cos(time * 0.43f) * 0.025f

        if (now >= nextBlinkAt && now >= blinkUntil) {
            blinkStarted = now
            blinkUntil = now + 150L
            scheduleNextBlink(now)
        }

        val blinkFactor = blinkFactor(now)

        // Soft shadow is a cached radial gradient, not a software Paint shadow.
        canvas.drawOval(
            cx - radius * 0.72f,
            cy + radius * 0.83f,
            cx + radius * 0.72f,
            cy + radius * 1.02f,
            shadowPaint
        )

        canvas.drawCircle(cx, cy, radius * 1.22f, auraPaint)

        canvas.save()
        canvas.scale(breathingScale, breathingScale, cx, cy)
        canvas.drawCircle(cx, cy, radius, spherePaint)

        val e = emotionParams()

        val desiredX = if (tracking) targetX else 0f
        val desiredY = if (tracking) targetY else 0f
        gazeX += (desiredX - gazeX) * 0.16f
        gazeY += (desiredY - gazeY) * 0.16f

        val liveX = gazeX + idleX * (1f - abs(gazeX))
        val liveY = gazeY + idleY * (1f - abs(gazeY))

        // The face moves around the sphere instead of merely sliding a few pixels.
        val faceX = liveX * radius * 0.22f * e.look
        val faceY = liveY * radius * 0.19f * e.look + e.droop
        val faceRotation = liveX * liveY * 10f + e.tilt

        canvas.save()
        canvas.rotate(faceRotation, cx, cy)
        canvas.translate(faceX, faceY)

        val eyeW = radius * 0.17f * e.sx
        val eyeH = radius * 0.30f * e.sy
        val gap = radius * 0.39f

        // Stronger perspective: the far eye narrows as the face turns.
        val perspective = min(0.62f, abs(liveX) * 0.58f)
        val leftScale = 1f - perspective * if (liveX > 0f) 0.72f else 0.10f
        val rightScale = 1f - perspective * if (liveX < 0f) 0.72f else 0.10f

        drawEye(
            canvas,
            cx - gap / 2f,
            cy - radius * 0.20f,
            eyeW,
            eyeH * leftScale * blinkFactor
        )
        drawEye(
            canvas,
            cx + gap / 2f,
            cy - radius * 0.20f,
            eyeW,
            eyeH * rightScale * blinkFactor
        )

        canvas.restore()
        canvas.restore()

        postInvalidateOnAnimation()
    }

    private fun rebuildShadersIfNeeded(cx: Float, cy: Float, radius: Float) {
        if (
            radius == cachedRadius &&
            cx == cachedCx &&
            cy == cachedCy &&
            core == cachedCore &&
            mid == cachedMid &&
            rim == cachedRim &&
            aura == cachedAura
        ) return

        cachedRadius = radius
        cachedCx = cx
        cachedCy = cy
        cachedCore = core
        cachedMid = mid
        cachedRim = rim
        cachedAura = aura

        spherePaint.shader = RadialGradient(
            cx - radius * 0.12f,
            cy - radius * 0.20f,
            radius * 1.25f,
            intArrayOf(core, mid, rim),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        auraPaint.shader = RadialGradient(
            cx,
            cy,
            radius * 1.28f,
            Color.argb(120, Color.red(aura), Color.green(aura), Color.blue(aura)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        shadowPaint.shader = RadialGradient(
            cx,
            cy + radius * 0.93f,
            radius * 0.72f,
            Color.argb(95, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val safeH = h.coerceAtLeast(2f)
        eyePaint.color = Color.WHITE
        canvas.drawRoundRect(
            x - w / 2f,
            y - safeH / 2f,
            x + w / 2f,
            y + safeH / 2f,
            w * 0.42f,
            w * 0.42f,
            eyePaint
        )
    }

    private fun blinkFactor(now: Long): Float {
        if (now >= blinkUntil) return 1f
        val duration = (blinkUntil - blinkStarted).coerceAtLeast(1L).toFloat()
        val progress = ((now - blinkStarted).coerceIn(0L, (blinkUntil - blinkStarted)) / duration)
        return (1f - sin(progress * Math.PI).toFloat() * 0.96f).coerceAtLeast(0.04f)
    }

    private fun scheduleNextBlink(now: Long) {
        // Deterministic variation without allocations or Random objects per frame.
        val variation = 2400L + ((now / 173L) % 3200L)
        nextBlinkAt = now + variation
    }

    private data class E(
        val sy: Float,
        val tilt: Float,
        val droop: Float,
        val look: Float,
        val sx: Float = 1f
    )

    private fun emotionParams(): E = when (emotion) {
        "happy" -> E(.52f, 0f, -2f, 1.25f)
        "surprised" -> E(1.25f, 0f, 0f, 1.45f, 1.10f)
        "smirk" -> E(.82f, -10f, 0f, 1.10f)
        "grin" -> E(.62f, 0f, 0f, 1.20f)
        "shy" -> E(.68f, 10f, 3f, .78f)
        "sad" -> E(.86f, 14f, 7f, .48f)
        "angry" -> E(.80f, -18f, -1f, 1.18f)
        "sleepy" -> E(.16f, 0f, 3f, .18f)
        else -> E(1f, 0f, 0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    targetX = ((event.x - width / 2f) / (width * 0.42f)).coerceIn(-1f, 1f)
                    targetY = ((event.y - height / 2f) / (height * 0.42f)).coerceIn(-1f, 1f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                blink()
                targetX *= 0.18f
                targetY *= 0.18f
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
