package com.turkialawlqy.aman.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.google.android.exoplayer2.ExoPlayer
import com.turkialawlqy.aman.R

/**
 * أزرار المشغّل بأسلوب منصات البث (توزيع شاهد):
 * - طبقة غامقة مائلة للأزرق فوق الفيديو
 * - فوق يمين: اسم المسلسل كبير + الحلقة
 * - فوق يسار: أزرار دائرية صغيرة بإطار (التالية، السابقة، مفضلتي، تنزيل) + شارة "3 من 12"
 * - تحت: خط زمني رفيع فيروزي بنقطة، وعلامات المقدمة صفراء، والوقت يسار والمدة يمين
 * - تحت بالنص: ⏪ ⏸ ⏩ صغيرة، والمحدد تحته اسمه ("إيقاف مؤقت")
 * Views عادية خفيفة — سريعة على أي تلفزيون.
 */
class CinemaControls(
    private val ctx: Context,
    private val player: ExoPlayer,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun hasPrev(): Boolean
        fun hasNext(): Boolean
        fun onPrev()
        fun onNext()
        fun onDownload()
        fun onFavorite()
        fun isSaved(): Boolean
        fun isFavorite(): Boolean
        /** المقدمة (بالملي ثانية) للفيديو الحالي */
        fun intro(): Pair<Long, Long>?
        /** يقبل التنزيل؟ (أفلام الاشتراك تشتغل أونلاين بس) */
        fun canDownload(): Boolean = true
        /** فيه أكثر من صوت أو فيه ترجمة؟ */
        fun hasTracks(): Boolean = false
        fun onTracks() {}
    }

    companion object {
        /** لون الخط الزمني = لون الثيم (فيروزي في شاهد، أحمر في نتفلكس) */
        val TEAL: Int get() = com.turkialawlqy.aman.ui.AppThemes.current.accent.toArgb()
        const val MARK = 0xFFF5D547.toInt()
        /** لون النص فوق الزر الأبيض = لون خلفية الثيم */
        private val DARK: Int get() = com.turkialawlqy.aman.ui.AppThemes.current.background.toArgb()
        /** طبقة سادة فوق الفيديو وقت ظهور الأزرار */
        private val SCRIM: Int get() = (com.turkialawlqy.aman.ui.AppThemes.current.background.toArgb() and 0x00FFFFFF) or (0x8C shl 24)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()
    private val bold: Typeface? = runCatching { ResourcesCompat.getFont(ctx, R.font.tajawal_bold) }.getOrNull()
    private val extra: Typeface? = runCatching { ResourcesCompat.getFont(ctx, R.font.tajawal_extrabold) }.getOrNull()
    private val sideGap = (ctx.resources.displayMetrics.widthPixels * 0.06f).toInt()
    private val bottomGap = (ctx.resources.displayMetrics.heightPixels * 0.07f).toInt()

    val root = FrameLayout(ctx).apply { alpha = 0f; visibility = View.GONE }

    // ---- فوق يمين: الاسم
    private val title = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 26f; typeface = extra; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.RIGHT; setShadowLayer(10f, 0f, 2f, 0x80000000.toInt())
    }
    private val subtitle = TextView(ctx).apply {
        setTextColor(0xE6FFFFFF.toInt()); textSize = 15f; typeface = bold; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.RIGHT
    }

    // ---- فوق يسار: أزرار دائرية + شارة
    private val nextBtn = iconButton(CircleIcon.Kind.NEXT, "الحلقة التالية") { cb.onNext() }
    private val prevBtn = iconButton(CircleIcon.Kind.PREV, "الحلقة السابقة") { cb.onPrev() }
    private val favBtn = iconButton(CircleIcon.Kind.HEART, "مفضلتي") { cb.onFavorite(); refresh() }
    private val dlBtn = iconButton(CircleIcon.Kind.DOWNLOAD, "تنزيل") { cb.onDownload(); refresh() }
    private val tracksBtn = iconButton(CircleIcon.Kind.TRACKS, "الصوت والترجمة") { cb.onTracks() }
    private val chip = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 13f; typeface = bold; gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(6f), dpi(16f), dpi(6f))
        background = GradientDrawable().apply { cornerRadius = dp(99f); setColor(0x33FFFFFF); setStroke(dpi(1.5f), 0x99FFFFFF.toInt()) }
    }
    /** اسم الزر المحدد فوق (تحت الأزرار) */
    private val topHint = TextView(ctx).apply {
        setTextColor(0xE6FFFFFF.toInt()); textSize = 12f; typeface = bold
    }

    // ---- تحت
    private val progress = ProgressLine(ctx)
    private val timeNow = timeText()
    private val timeTotal = timeText()
    private val rewBtn = transportButton(CircleIcon.Kind.REWIND, "رجوع 10 ثواني") { seekBy(-10_000) }
    private val playBtn = transportButton(CircleIcon.Kind.PAUSE, "إيقاف مؤقت", primary = true) { togglePlay() }
    private val ffBtn = transportButton(CircleIcon.Kind.FORWARD, "تقديم 10 ثواني") { seekBy(10_000) }
    private val hint = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 14f; typeface = bold; gravity = Gravity.CENTER
    }

    private var lastInput = 0L
    var isShowing = false
        private set

    /** ارتفاع الأزرار من تحت الشاشة (عشان "تخطى المقدمة" والبطاقة تكون فوقها) */
    val coveredHeight: Int get() = bottomGap + dpi(118f)

    init {
        // شريط التمرير: يتحدد بالريموت، ويمين/يسار يقدّم ويرجّع (الضغط المستمر يسرّع)
        progress.isFocusable = true
        progress.contentDescription = "يمين ويسار للتقديم والترجيع"
        progress.setOnFocusChangeListener { v, has -> v.invalidate(); if (has) showHint(v) }
        progress.setOnKeyListener { _, keyCode, e ->
            if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = when {
                e.repeatCount >= 12 -> 60_000L
                e.repeatCount >= 4 -> 30_000L
                else -> 10_000L
            }
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-step); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(step); true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { onInput(); togglePlay(); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { playBtn.requestFocus(); true }
                else -> false
            }
        }

        // طبقة غامقة على كامل الفيديو (أغمق فوق وتحت)
        // طبقة سادة واحدة (بدون تدرّج) عشان الكلام والأزرار تنقرى
        root.addView(View(ctx).apply { setBackgroundColor(SCRIM) }, FrameLayout.LayoutParams(-1, -1))

        // فوق يمين: الاسم
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })
        }, FrameLayout.LayoutParams((ctx.resources.displayMetrics.widthPixels * 0.42f).toInt(), -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = (ctx.resources.displayMetrics.heightPixels * 0.07f).toInt(); rightMargin = sideGap
        })

        // فوق يسار: الأزرار (من اليسار: التالية، السابقة، مفضلتي، تنزيل، الشارة)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                listOf(nextBtn, prevBtn, favBtn, dlBtn, tracksBtn).forEach { b ->
                    addView(b, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { rightMargin = dpi(10f) })
                }
                addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(4f) })
            }, LinearLayout.LayoutParams(-2, -2)) // wrap — بدونها ياخذ عرض النص اللي تحته وتنقص الأزرار
            addView(topHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f); leftMargin = dpi(2f) })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            topMargin = (ctx.resources.displayMetrics.heightPixels * 0.07f).toInt(); leftMargin = sideGap
        })

        // تحت: الخط الزمني + الوقت + ⏪ ⏸ ⏩
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(progress, LinearLayout.LayoutParams(-1, dpi(20f)))
            addView(FrameLayout(ctx).apply {
                addView(timeNow, FrameLayout.LayoutParams(-2, -2, Gravity.LEFT or Gravity.TOP).apply { topMargin = dpi(10f) })
                addView(timeTotal, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.TOP).apply { topMargin = dpi(4f) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        gravity = Gravity.CENTER_VERTICAL
                        addView(rewBtn, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
                        addView(playBtn, LinearLayout.LayoutParams(dpi(50f), dpi(50f)).apply { leftMargin = dpi(16f); rightMargin = dpi(16f) })
                        addView(ffBtn, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
                    }, LinearLayout.LayoutParams(-2, -2)) // wrap — بدونها تنقص الأزرار على عرض "إيقاف مؤقت"
                    addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f) })
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = sideGap; rightMargin = sideGap; bottomMargin = bottomGap
        })
    }

    // ------------------------------------------------------------------ واجهة للمشغّل
    fun setInfo(t: String, sub: String?, chipText: String?) {
        title.text = t
        subtitle.text = sub.orEmpty()
        subtitle.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
        chip.text = chipText.orEmpty()
        chip.visibility = if (chipText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** focusBar = التركيز على شريط التمرير (لما يبدأ التقديم/الترجيع بالأسهم) */
    fun show(focusPlay: Boolean = true, focusBar: Boolean = false) {
        lastInput = SystemClock.elapsedRealtime()
        refresh()
        tick()
        if (!isShowing) {
            isShowing = true
            root.visibility = View.VISIBLE
            root.animate().cancel()
            root.animate().alpha(1f).setDuration(150).start()
            if (focusBar) progress.requestFocus() else if (focusPlay) playBtn.requestFocus()
        } else if (focusBar && !progress.isFocused) progress.requestFocus()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(180).withEndAction { if (!isShowing) root.visibility = View.GONE }.start()
        root.findFocus()?.clearFocus()
    }

    /** المستخدم ضغط زر — نأجل الإخفاء */
    fun onInput() { lastInput = SystemClock.elapsedRealtime() }

    fun hasFocus() = root.hasFocus()

    /** حالة الأزرار (التالي/السابق، محفوظ، مفضلة) */
    fun refresh() {
        fun enable(b: View, on: Boolean) {
            b.visibility = if (on) View.VISIBLE else View.GONE
            b.isFocusable = on
        }
        enable(nextBtn, cb.hasNext())
        enable(dlBtn, cb.canDownload())
        enable(prevBtn, cb.hasPrev())
        enable(tracksBtn, cb.hasTracks())
        dlBtn.kind = if (cb.isSaved()) CircleIcon.Kind.CHECK else CircleIcon.Kind.DOWNLOAD
        dlBtn.contentDescription = if (cb.isSaved()) "محفوظ بدون إنترنت" else "تنزيل"
        favBtn.idleIconColor = if (cb.isFavorite()) 0xFFFF5E8A.toInt() else Color.WHITE
        favBtn.contentDescription = if (cb.isFavorite()) "إزالة من مفضلتي" else "مفضلتي"
        updatePlay()
        // اسم الزر المحدد
        root.findFocus()?.let { f -> showHint(f) }
    }

    private fun updatePlay() {
        val playing = player.playWhenReady
        val k = if (playing) CircleIcon.Kind.PAUSE else CircleIcon.Kind.PLAY
        if (playBtn.kind != k) playBtn.kind = k
        playBtn.contentDescription = if (playing) "إيقاف مؤقت" else "تشغيل"
        if (playBtn.isFocused) hint.text = playBtn.contentDescription
    }

    /** كل ربع ثانية من المشغّل: الشريط والوقت + الإخفاء التلقائي */
    fun tick() {
        if (!isShowing) return
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        val pos = player.currentPosition.coerceAtLeast(0)
        progress.set(
            if (dur > 0) pos.toFloat() / dur else 0f,
            if (dur > 0) player.bufferedPosition.toFloat() / dur else 0f,
            cb.intro()?.takeIf { dur > 0 }?.let { (a, b) -> a.toFloat() / dur to b.toFloat() / dur },
        )
        timeNow.text = fmt(pos)
        timeTotal.text = if (dur > 0) fmt(dur) else ""
        updatePlay()
        if (player.isPlaying && SystemClock.elapsedRealtime() - lastInput > 5000) hide()
    }

    fun togglePlay() {
        player.playWhenReady = !player.playWhenReady
        updatePlay()
    }

    fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        onInput()
        tick()
    }

    // ------------------------------------------------------------------ عناصر
    private fun showHint(v: View) {
        val text = v.contentDescription?.toString().orEmpty()
        if (v === progress) { hint.text = text; topHint.text = "" }
        else if (v === rewBtn || v === playBtn || v === ffBtn) { hint.text = text; topHint.text = "" }
        else { topHint.text = text; hint.text = "" }
    }

    private fun timeText() = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 15f; typeface = bold
        setShadowLayer(6f, 0f, 1f, 0x80000000.toInt())
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val l = java.util.Locale.US // أرقام إنجليزية دايم
        return if (s >= 3600) String.format(l, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(l, "%02d:%02d", s / 60, s % 60)
    }

    /** زر دائري صغير بإطار (فوق): المحدد أبيض والأيقونة غامقة */
    private fun iconButton(kind: CircleIcon.Kind, name: String, onClick: () -> Unit) = CircleIcon(ctx).apply {
        this.kind = kind
        contentDescription = name
        darkColor = DARK
        setOnFocusChangeListener { v, has ->
            v.invalidate()
            if (has) showHint(v) else if (topHint.text == contentDescription) topHint.text = ""
        }
        setOnClickListener { onInput(); onClick() }
    }

    /** أزرار التشغيل تحت: ⏪ ⏩ بدون خلفية، والتشغيل دائرة بإطار — المحدد دائرة بيضاء */
    private fun transportButton(kind: CircleIcon.Kind, name: String, primary: Boolean = false, onClick: () -> Unit) = CircleIcon(ctx).apply {
        this.kind = kind
        contentDescription = name
        darkColor = DARK
        idleFill = 0
        idleRing = primary
        setOnFocusChangeListener { v, has -> v.invalidate(); if (has) showHint(v) }
        setOnClickListener { onInput(); onClick() }
    }

    /** الخط الزمني: مسار فاتح + المحمّل + المشاهَد فيروزي + نقطة + علامات المقدمة صفراء */
    private class ProgressLine(ctx: Context) : View(ctx) {
        private var played = 0f
        private var buffered = 0f
        private var intro: Pair<Float, Float>? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val d = ctx.resources.displayMetrics.density

        fun set(played: Float, buffered: Float, intro: Pair<Float, Float>?) {
            this.played = played.coerceIn(0f, 1f)
            this.buffered = buffered.coerceIn(0f, 1f)
            this.intro = intro
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val h = (if (isFocused) 5 else 3) * d
            val cy = height / 2f
            val left = 9 * d
            val w = width - 18 * d
            fun bar(from: Float, to: Float, col: Int) {
                if (to <= from) return
                p.color = col
                r.set(left + w * from, cy - h / 2, left + w * to, cy + h / 2)
                c.drawRoundRect(r, h, h, p)
            }
            bar(0f, 1f, 0x8CFFFFFF.toInt())
            bar(0f, buffered, 0xCCFFFFFF.toInt())
            bar(0f, played, TEAL)
            // علامات المقدمة (مربعات صفراء صغيرة)
            intro?.let { (a, b) ->
                p.color = MARK
                for (f in listOf(a, b)) if (f > 0.001f && f < 0.999f) {
                    val x = left + w * f
                    c.drawRect(x - 3 * d, cy - 3 * d, x + 3 * d, cy + 3 * d, p)
                }
            }
            val x = left + w * played
            if (isFocused) {
                // محدد: نقطة أكبر بإطار أبيض
                p.color = Color.WHITE
                c.drawCircle(x, cy, 10 * d, p)
                p.color = TEAL
                c.drawCircle(x, cy, 7 * d, p)
            } else {
                p.color = (TEAL and 0x00FFFFFF) or 0x55000000
                c.drawCircle(x, cy, 10 * d, p)
                p.color = TEAL
                c.drawCircle(x, cy, 7 * d, p)
            }
        }
    }
}
