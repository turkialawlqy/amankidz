package com.turkialawlqy.aman.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * زر دائري بأيقونة مرسومة (مو حرف من الخط) — عشان تكون بالنص بالضبط على أي تلفزيون،
 * والدائرة ترسم داخل حدود الزر فما تنقص لما ينحدد.
 */
class CircleIcon(ctx: Context) : View(ctx) {
    enum class Kind { PLAY, PAUSE, REWIND, FORWARD, NEXT, PREV, HEART, DOWNLOAD, CHECK, TRACKS }

    var kind = Kind.PLAY
        set(v) { field = v; invalidate() }
    /** لون الأيقونة لما الزر مو محدد (مثلاً القلب وردي لو بالمفضلة) */
    var idleIconColor = Color.WHITE
        set(v) { field = v; invalidate() }
    /** خلفية خفيفة + إطار لما مو محدد (أزرار فوق، وزر التشغيل) */
    var idleFill = 0x40FFFFFF
    var idleRing = true
    var darkColor = 0xFF1E2235.toInt()

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, _ -> invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val focused = isFocused
        val cx = width / 2f
        val cy = height / 2f
        val ring = 1.5f * d
        // المحدد أكبر شوي، والاثنين داخل حدود الزر
        val maxR = minOf(width, height) / 2f - ring
        val r = if (focused) maxR else maxR * 0.92f

        p.style = Paint.Style.FILL
        if (focused) {
            p.color = Color.WHITE; c.drawCircle(cx, cy, r, p)
        } else {
            if (idleFill != 0) { p.color = idleFill; c.drawCircle(cx, cy, r, p) }
            if (idleRing) {
                p.style = Paint.Style.STROKE; p.strokeWidth = ring; p.color = 0xD9FFFFFF.toInt()
                c.drawCircle(cx, cy, r, p)
            }
        }

        val col = if (focused) darkColor else idleIconColor
        val s = r * 0.36f // وحدة حجم الأيقونة
        p.color = col
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        path.reset()
        fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        }
        when (kind) {
            Kind.PLAY -> tri(cx - 0.75f * s, cy - s, cx - 0.75f * s, cy + s, cx + 1.05f * s, cy)
            Kind.PAUSE -> {
                val w = 0.55f * s; val g = 0.3f * s
                c.drawRoundRect(cx - g - w, cy - s, cx - g, cy + s, w / 3, w / 3, p)
                c.drawRoundRect(cx + g, cy - s, cx + g + w, cy + s, w / 3, w / 3, p)
            }
            Kind.REWIND -> {
                val h = 0.8f * s
                tri(cx, cy - h, cx, cy + h, cx - 1.1f * s, cy)
                tri(cx + 1.1f * s, cy - h, cx + 1.1f * s, cy + h, cx, cy)
            }
            Kind.FORWARD -> {
                val h = 0.8f * s
                tri(cx, cy - h, cx, cy + h, cx + 1.1f * s, cy)
                tri(cx - 1.1f * s, cy - h, cx - 1.1f * s, cy + h, cx, cy)
            }
            Kind.NEXT -> {
                val h = 0.75f * s; val o = -0.1f * s
                tri(cx - 1.1f * s + o, cy - h, cx - 1.1f * s + o, cy + h, cx + o, cy)
                tri(cx + o, cy - h, cx + o, cy + h, cx + 1.0f * s + o, cy)
                c.drawRect(cx + 1.0f * s + o, cy - h, cx + 1.25f * s + o, cy + h, p)
            }
            Kind.PREV -> {
                val h = 0.75f * s; val o = 0.1f * s
                tri(cx + 1.1f * s + o, cy - h, cx + 1.1f * s + o, cy + h, cx + o, cy)
                tri(cx + o, cy - h, cx + o, cy + h, cx - 1.0f * s + o, cy)
                c.drawRect(cx - 1.25f * s + o, cy - h, cx - 1.0f * s + o, cy + h, p)
            }
            Kind.HEART -> {
                val w = 1.05f * s
                path.moveTo(cx, cy + 0.95f * s)
                path.cubicTo(cx - 1.6f * w, cy - 0.1f * s, cx - 0.9f * w, cy - 1.35f * s, cx, cy - 0.5f * s)
                path.cubicTo(cx + 0.9f * w, cy - 1.35f * s, cx + 1.6f * w, cy - 0.1f * s, cx, cy + 0.95f * s)
                path.close()
            }
            Kind.DOWNLOAD -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.28f * s
                c.drawLine(cx, cy - 1.0f * s, cx, cy + 0.45f * s, p)
                path.moveTo(cx - 0.6f * s, cy - 0.15f * s); path.lineTo(cx, cy + 0.45f * s); path.lineTo(cx + 0.6f * s, cy - 0.15f * s)
                c.drawLine(cx - 0.85f * s, cy + 1.0f * s, cx + 0.85f * s, cy + 1.0f * s, p)
            }
            Kind.TRACKS -> {
                // فقاعة كلام فيها سطرين (الصوت والترجمة)
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.22f * s
                c.drawRoundRect(cx - 1.05f * s, cy - 0.85f * s, cx + 1.05f * s, cy + 0.55f * s, 0.3f * s, 0.3f * s, p)
                path.moveTo(cx - 0.45f * s, cy + 0.55f * s); path.lineTo(cx - 0.7f * s, cy + 1.05f * s); path.lineTo(cx - 0.05f * s, cy + 0.55f * s)
                c.drawLine(cx - 0.6f * s, cy - 0.35f * s, cx + 0.6f * s, cy - 0.35f * s, p)
                c.drawLine(cx - 0.6f * s, cy + 0.05f * s, cx + 0.25f * s, cy + 0.05f * s, p)
            }
            Kind.CHECK -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.3f * s
                path.moveTo(cx - 0.85f * s, cy + 0.05f * s); path.lineTo(cx - 0.2f * s, cy + 0.7f * s); path.lineTo(cx + 0.9f * s, cy - 0.65f * s)
            }
        }
        if (!path.isEmpty) c.drawPath(path, p)
    }
}
