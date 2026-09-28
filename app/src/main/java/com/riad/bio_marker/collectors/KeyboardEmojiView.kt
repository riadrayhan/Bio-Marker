package com.riad.bio_marker.collectors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.random.Random


class KeyboardEmojiView(context: Context) : View(context) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var speed: Float,
        var size: Float,
        var glyph: String,
        var alpha: Int,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particles = ArrayList<Particle>(PARTICLE_COUNT)
    private var lastFrameMs = 0L
    @Volatile private var animating = false

    init {
        isClickable = false
        isFocusable = false
    }

    fun startAnimation() {
        if (animating) return
        animating = true
        lastFrameMs = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun stopAnimation() {
        animating = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seedParticles(w, h)
    }

    private fun seedParticles(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        particles.clear()
        repeat(PARTICLE_COUNT) {
            particles.add(newParticle(w, Random.nextInt(0, h).toFloat()))
        }
    }

    private fun newParticle(w: Int, startY: Float): Particle {
        val size = dp(18f) + Random.nextFloat() * dp(14f)
        return Particle(
            x = Random.nextFloat() * w,
            y = startY,
            speed = dp(8f) + Random.nextFloat() * dp(16f),  // px per second
            size = size,
            glyph = GLYPHS[Random.nextInt(GLYPHS.size)],
            alpha = 26 + Random.nextInt(0, 34),             // ~10–23% opacity
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        if (particles.isEmpty()) seedParticles(w, h)

        val now = System.currentTimeMillis()
        val dt = ((now - lastFrameMs).coerceIn(0L, 64L)) / 1000f
        lastFrameMs = now

        for (p in particles) {
            p.y -= p.speed * dt
            if (p.y + p.size < 0f) {
                // Recycle from the bottom with fresh randomised traits.
                val np = newParticle(w, h + p.size)
                p.x = np.x; p.y = np.y; p.speed = np.speed
                p.size = np.size; p.glyph = np.glyph; p.alpha = np.alpha
            }
            paint.textSize = p.size
            paint.alpha = p.alpha
            canvas.drawText(p.glyph, p.x, p.y, paint)
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        private const val PARTICLE_COUNT = 7
        private val GLYPHS = arrayOf("❤", "💋", "🙂", "🐦", "💑", "🌸", "✨", "💕")
    }
}



