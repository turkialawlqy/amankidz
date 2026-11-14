package com.turkialawlqy.aman.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import coil3.load
import com.turkialawlqy.aman.R
import com.turkialawlqy.aman.ui.AppThemes

/**
 * بطاقة "الحلقة التالية" (مثل شاهد ونتفلكس): تطلع آخر ثواني الحلقة بصورة الحلقة الجاية
 * وعدّاد تنازلي — OK يشغّلها الآن، ورجوع يخفيها ويكمل الحلقة.
 */
class NextUpCard(private val ctx: Context) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()
    private val bold = runCatching { ResourcesCompat.getFont(ctx, R.font.tajawal_bold) }.getOrNull()
    private val extra = runCatching { ResourcesCompat.getFont(ctx, R.font.tajawal_extrabold) }.getOrNull()

    private val thumb = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(AppThemes.current.surface2.toArgb())
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(AppThemes.current.radius.value))
        }
    }
    private val playIcon = TextView(ctx).apply {
        text = "▶"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
    }
    /** شريط العدّ التنازلي تحت الصورة */
    private val countBar = View(ctx)
    private val countTrack = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = dp(2f); setColor(0x33FFFFFF) }
        addView(countBar, FrameLayout.LayoutParams(0, -1, Gravity.RIGHT))
    }
    private val label = TextView(ctx).apply {
        text = "الحلقة التالية"; setTextColor(0xB3FFFFFF.toInt()); textSize = 12f; typeface = bold; gravity = Gravity.RIGHT
    }
    private val title = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 16f; typeface = extra; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.RIGHT
    }
    private val countText = TextView(ctx).apply {
        setTextColor(0xE6FFFFFF.toInt()); textSize = 13f; typeface = bold; gravity = Gravity.RIGHT
    }
    private val playBtn = TextView(ctx).apply {
        text = "شغّل الآن"; textSize = 14f; typeface = bold; gravity = Gravity.CENTER
        setTextColor(0xFF111111.toInt())
        setPadding(dpi(16f), dpi(7f), dpi(16f), dpi(7f))
        background = GradientDrawable().apply { cornerRadius = if (AppThemes.current.pillButtons) dp(99f) else dp(4f); setColor(Color.WHITE) }
        isFocusable = true
    }

    val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL // الصورة يمين والنص يسارها
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(16f), dpi(12f), dpi(12f), dpi(12f))
        // لوح سادة بلون الثيم (بدون تدرّج ولا ظل)
        background = GradientDrawable().apply { cornerRadius = dp(AppThemes.current.radius.value + 4f); setColor(AppThemes.current.surface.toArgb()) }
        alpha = 0f
        visibility = View.GONE
        // الصورة + العدّاد تحتها
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(ctx).apply {
                addView(thumb, FrameLayout.LayoutParams(-1, -1))
                addView(playIcon, FrameLayout.LayoutParams(dpi(38f), dpi(38f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dpi(168f), dpi(94f)))
            addView(countTrack, LinearLayout.LayoutParams(-1, dpi(4f)).apply { topMargin = dpi(6f) })
        }, LinearLayout.LayoutParams(-2, -2))
        // النص + الزر
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            addView(label, LinearLayout.LayoutParams(-1, -2))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })
            addView(countText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
            addView(playBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(8f); gravity = Gravity.RIGHT })
        }, LinearLayout.LayoutParams(dpi(220f), -2).apply { marginStart = dpi(14f) })
    }

    var isShowing = false
        private set
    private var shownFor: String? = null

    fun setOnPlay(action: () -> Unit) {
        playBtn.setOnClickListener { action() }
        root.setOnClickListener { action() }
    }

    /** يطلع البطاقة (مرة لكل حلقة) */
    fun show(key: String, nextTitle: String, thumbUrl: String?) {
        if (isShowing && shownFor == key) return
        shownFor = key
        title.text = nextTitle
        thumb.setImageDrawable(null)
        if (!thumbUrl.isNullOrBlank()) runCatching { thumb.load(thumbUrl) }
        countBar.setBackgroundColor(CinemaControls.TEAL)
        isShowing = true
        root.visibility = View.VISIBLE
        root.translationY = dp(16f)
        root.animate().cancel()
        root.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** remainingMs = الباقي على نهاية الحلقة، windowMs = مدة العدّ كاملة */
    fun update(remainingMs: Long, windowMs: Long) {
        if (!isShowing) return
        val sec = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        countText.text = if (sec > 0) "تبدأ بعد $sec ${if (sec in 3..10) "ثواني" else "ثانية"}" else "تبدأ الآن…"
        val frac = (remainingMs.toFloat() / windowMs).coerceIn(0f, 1f)
        val w = countTrack.width
        if (w > 0) countBar.layoutParams = (countBar.layoutParams as FrameLayout.LayoutParams).apply { width = (w * frac).toInt() }
    }

    fun focus() { playBtn.requestFocus() }
    fun hasFocus() = root.hasFocus()

    fun hide() {
        if (!isShowing) return
        isShowing = false
        root.animate().cancel()
        root.animate().alpha(0f).translationY(dp(16f)).setDuration(160)
            .withEndAction { if (!isShowing) root.visibility = View.GONE }.start()
        root.findFocus()?.clearFocus()
    }
}
