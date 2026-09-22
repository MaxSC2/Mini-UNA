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
    private var auraEnabled = true
    private var lightAnimation = true
    private var gloss = true
    private var lookTravel = 0.22f
    private var eyeScale = 1f
    private var roll = 10f
    private var frameIntervalMs = 33L

    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var blinkStarted = 0L
    private var blinkUntil = 0L
    private var nextBlinkAt = System.currentTimeMillis() + 2600L
    private var hopStarted = 0L
    private var hopUntil = 0L

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 110, 140)
    }
    private val zzzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 200, 255)
        textAlign = Paint.Align.CENTER
    }

    // Emotion face parameters, ported from the kimi-mascot reference:
    // eye height scale, eye tilt, blush opacity, blink-rate multiplier.
    private data class EmoFace(
        val eyeSY: Float,
        val eyeTilt: Float,
        val blush: Float,
        val blinkGap: Float,
        val sleepy: Boolean = false,
        val dizzy: Boolean = false
    )

    private val emoFaces = mapOf(
        "neutral" to EmoFace(1f, 0f, 0f, 1f),
        "happy" to EmoFace(0.45f, 0f, 0.35f, 0.7f),
        "surprised" to EmoFace(1.32f, 0f, 0f, 1.5f),
        "smirk" to EmoFace(0.8f, -10f, 0f, 1f),
        "grin" to EmoFace(0.55f, 0f, 0.25f, 0.7f),
        "shy" to EmoFace(0.65f, 10f, 1f, 0.8f),
        "dizzy" to EmoFace(1f, 0f, 0.15f, 0.6f, dizzy = true),
        "sad" to EmoFace(0.85f, 14f, 0f, 1.7f),
        "angry" to EmoFace(0.8f, -18f, 0f, 0.9f),
        "sleepy" to EmoFace(0.12f, 0f, 0f, 0f, sleepy = true)
    )

    // Smoothed live values: emotions transition without jumps.
    private var curSY = 1f
    private var curTilt = 0f
    private var curBlush = 0f

    private var cachedRadius = -1f
    private var cachedCx = -1f
    private var cachedCy = -1f
    private var cachedCore = 0
    private var cachedMid = 0
    private var cachedRim = 0
    private var cachedAura = 0
    private var lastFrame = 0L

    init {
        isClickable = true
        eyePaint.color = Color.WHITE
        glossPaint.color = Color.argb(82, 255, 255, 255)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

    fun setAuraEnabled(enabled: Boolean) {
        auraEnabled = enabled
        invalidate()
    }

    fun setLightAnimation(enabled: Boolean) {
        lightAnimation = enabled
        invalidate()
    }

    fun setGloss(enabled: Boolean) {
        gloss = enabled
        invalidate()
    }

    fun setLookTravel(value: Float) {
        lookTravel = value.coerceIn(0.08f, 0.34f)
        invalidate()
    }

    fun setEyeScale(value: Float) {
        eyeScale = value.coerceIn(0.8f, 1.35f)
        invalidate()
    }

    fun setRoll(value: Float) {
        roll = value.coerceIn(0f, 20f)
        invalidate()
    }

    fun blink() {
        val now = System.currentTimeMillis()
        blinkStarted = now
        blinkUntil = now + 155L
        scheduleNextBlink(now)
        invalidate()
    }

    private fun hop() {
        val now = System.currentTimeMillis()
        hopStarted = now
        hopUntil = now + 340L
        blink()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedRadius = -1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Optimization: pause the frame loop while hidden (page GONE, activity
        // in background). No postInvalidate here, so the loop fully stops.
        if (visibility != VISIBLE || !isShown) {
            lastFrame = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (lastFrame != 0L && now - lastFrame < frameIntervalMs) {
            postInvalidateDelayed(frameIntervalMs - (now - lastFrame))
            return
        }
        if (lastFrame == 0L) {
            // Fresh (re)start after being hidden: don't blink instantly.
            nextBlinkAt = max(nextBlinkAt, now + 2000L)
        }
        lastFrame = now

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.34f

        rebuildShadersIfNeeded(cx, cy, radius)

        val time = now / 1000f
        val breathingScale = if (breathing) 1f + sin(time * 1.7f) * 0.010f else 1f
        val idleX = sin(time * 0.55f) * 0.035f
        val idleY = cos(time * 0.43f) * 0.025f

        val emo = emoFaces[emotion] ?: emoFaces.getValue("neutral")
        // Smooth emotion transitions, like the reference (lerp per frame).
        curSY += (emo.eyeSY - curSY) * 0.15f
        curTilt += (emo.eyeTilt - curTilt) * 0.15f
        curBlush += (emo.blush - curBlush) * 0.12f

        // Sleepy never auto-blinks: the eyes are already closed.
        if (!emo.sleepy && now >= nextBlinkAt && now >= blinkUntil) {
            blinkStarted = now
            blinkUntil = now + 155L
            scheduleNextBlink(now, emo.blinkGap)
        }

        val blinkFactor = blinkFactor(now)
        val hopOffset = if (now < hopUntil) {
            val p = ((now - hopStarted).toFloat() / (hopUntil - hopStarted).coerceAtLeast(1L))
            -sin(p * Math.PI).toFloat() * radius * 0.10f
        } else 0f

        canvas.drawOval(
            cx - radius * 0.72f,
            cy + radius * 0.83f,
            cx + radius * 0.72f,
            cy + radius * 1.02f,
            shadowPaint
        )

        if (auraEnabled) {
            val pulse = 0.94f + sin(time * 1.5f) * 0.06f
            canvas.drawCircle(cx, cy + hopOffset, radius * 1.22f * pulse, auraPaint)
        }

        canvas.save()
        canvas.translate(0f, hopOffset)
        canvas.scale(breathingScale, breathingScale, cx, cy)
        canvas.drawCircle(cx, cy, radius, spherePaint)

        val desiredX = if (tracking) targetX else 0f
        val desiredY = if (tracking) targetY else 0f
        gazeX += (desiredX - gazeX) * 0.20f
        gazeY += (desiredY - gazeY) * 0.20f

        val liveX = gazeX + idleX * (1f - abs(gazeX))
        val liveY = gazeY + idleY * (1f - abs(gazeY))

        if (gloss) {
            // Reference behavior: the gloss travels opposite the gaze,
            // with a small idle drift while light animation is on.
            val drift = if (lightAnimation) sin(time * 0.42f) * radius * 0.04f else 0f
            val lx = cx - liveX * radius * 0.30f + drift
            val ly = cy - radius * 0.40f - liveY * radius * 0.18f
            canvas.drawOval(
                lx - radius * 0.23f,
                ly - radius * 0.13f,
                lx + radius * 0.23f,
                ly + radius * 0.13f,
                glossPaint
            )
        }

        // The sphere stays round. Only the face travels across its surface.
        val faceX = liveX * radius * lookTravel
        val faceY = liveY * radius * (lookTravel * 0.86f)
        val faceRotation = liveX * liveY * roll

        canvas.save()
        canvas.rotate(faceRotation, cx, cy)
        canvas.translate(faceX, faceY)

        val eyeW = radius * 0.21f * eyeScale
        val eyeH = radius * 0.48f * eyeScale * curSY
        val gap = radius * 0.64f
        val eyeY = cy - radius * 0.28f

        // No perspective deformation: both eyes keep their shape on turn.
        val eyeTilt = liveX * 6f + curTilt

        if (curBlush > 0.02f) {
            blushPaint.alpha = (235f * curBlush.coerceIn(0f, 1f)).toInt()
            val bw = radius * 0.17f
            val bh = radius * 0.09f
            val bxOff = gap * 0.5f + radius * 0.27f
            val by = cy + radius * 0.21f
            canvas.drawOval(cx - bxOff - bw, by - bh, cx - bxOff + bw, by + bh, blushPaint)
            canvas.drawOval(cx + bxOff - bw, by - bh, cx + bxOff + bw, by + bh, blushPaint)
        }

        if (emo.dizzy) {
            val s = eyeW * 0.45f
            xPaint.strokeWidth = (s * 0.32f).coerceAtLeast(2f)
            drawXEye(canvas, cx - gap / 2f, eyeY, s, eyeTilt)
            drawXEye(canvas, cx + gap / 2f, eyeY, s, eyeTilt)
        } else {
            drawEye(canvas, cx - gap / 2f, eyeY, eyeW, eyeH * blinkFactor, eyeTilt)
            drawEye(canvas, cx + gap / 2f, eyeY, eyeW, eyeH * blinkFactor, eyeTilt)
        }

        if (emo.sleepy) {
            zzzPaint.textSize = radius * 0.20f
            zzzPaint.alpha = (140f + 90f * sin(time * 2.8f)).toInt().coerceIn(0, 255)
            canvas.drawText("z z", cx + radius * 0.55f, cy - radius * 0.75f, zzzPaint)
        }

        canvas.restore()
        canvas.restore()

        // 30 FPS is enough for a tiny mascot and avoids burning the phone for no reason.
        postInvalidateDelayed(33L)
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

    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, rotation: Float = 0f) {
        val safeW = w.coerceAtLeast(3f)
        val safeH = h.coerceAtLeast(2f)
        canvas.save()
        canvas.rotate(rotation, x, y)
        canvas.drawRoundRect(
            x - safeW / 2f,
            y - safeH / 2f,
            x + safeW / 2f,
            y + safeH / 2f,
            safeW * 0.44f,
            safeW * 0.44f,
            eyePaint
        )
        canvas.restore()
    }

    private fun blinkFactor(now: Long): Float {
        if (now >= blinkUntil) return 1f
        val duration = (blinkUntil - blinkStarted).coerceAtLeast(1L).toFloat()
        val progress = ((now - blinkStarted).coerceIn(0L, (blinkUntil - blinkStarted)) / duration)
        return (1f - sin(progress * Math.PI).toFloat() * 0.96f).coerceAtLeast(0.04f)
    }

    private fun scheduleNextBlink(now: Long, gap: Float = 1f) {
        val variation = 3800L + ((now / 173L) % 2600L)
        nextBlinkAt = now + (variation * gap).toLong().coerceAtLeast(1200L)
    }

    private fun drawXEye(canvas: Canvas, x: Float, y: Float, s: Float, rotation: Float = 0f) {
        canvas.save()
        canvas.rotate(rotation, x, y)
        canvas.drawLine(x - s, y - s, x + s, y + s, xPaint)
        canvas.drawLine(x - s, y + s, x + s, y - s, xPaint)
        canvas.restore()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Restart the frame loop after returning from background.
        if (visibility == VISIBLE) invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Restart the frame loop after tab switches (page GONE -> VISIBLE).
        if (visibility == VISIBLE) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    val sphereRadius = min(width, height) * 0.34f
                    val sphereCx = width / 2f
                    val sphereCy = height / 2f
                    targetX = ((event.x - sphereCx) / sphereRadius).coerceIn(-1f, 1f)
                    targetY = ((event.y - sphereCy) / sphereRadius).coerceIn(-1f, 1f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                hop()
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
