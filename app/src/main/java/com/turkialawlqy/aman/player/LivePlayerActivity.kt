package com.turkialawlqy.aman.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import coil3.load
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.MimeTypes
import com.turkialawlqy.aman.R
import com.turkialawlqy.aman.control.ParentalRules
import com.turkialawlqy.aman.control.ProfileStore
import com.turkialawlqy.aman.control.WatchTracker
import com.turkialawlqy.aman.data.LiveChannel
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.ui.AppThemes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مشغّل القنوات المباشرة (IPTV):
 * فوق/تحت = القناة التالية/السابقة، OK = معلومات القناة (ويش يعرض الحين)، رجوع = خروج.
 * ينحسب من وقت المشاهدة، ويقفل لو خلص الوقت أو جا وقت النوم — زي باقي المحتوى.
 */
class LivePlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var banner: LinearLayout
    private lateinit var numView: TextView
    private lateinit var nameView: TextView
    private lateinit var nowView: TextView
    private lateinit var nextView: TextView
    private lateinit var logoView: ImageView
    private lateinit var msgView: TextView
    private var channels: List<LiveChannel> = emptyList()
    private var index = 0
    private var hideJob: Job? = null
    private var retries = 0
    private lateinit var http: DefaultHttpDataSource.Factory

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ready = TvRepository.state.value as? TvState.Ready
        val ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
        val all = ready?.visibleChannels(ProfileStore.current.value).orEmpty()
        // قنوات مكتبة الاشتراك تجي جاهزة (مو في قاعدة البيانات)
        val pool = all + playlist
        channels = ids.mapNotNull { id -> pool.firstOrNull { it.id == id } }.ifEmpty { all }
        if (channels.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.size - 1)

        http = DefaultHttpDataSource.Factory()
            .setUserAgent(com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS[0])
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        player = ExoPlayer.Builder(this).setLoadControl(FastStart.loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (archiveTitle != null) {
                    message("البرنامج هذا مو متاح في الأرشيف — اضغط يسار وارجع للبث المباشر")
                    return
                }
                // البث المباشر ينقطع أحياناً → نعيد المحاولة، والمحاولة الثانية بالصيغة الثانية (m3u8 ↔ ts)
                // المحاولات: الصيغة الثانية (m3u8↔ts) × هويات مشغّلات مختلفة (لو السيرفر يرفض 403)
                // المحاولات قليلة وبينها مهلة: كل محاولة فاشلة ممكن السيرفر يحسبها "جهاز متصل" لثواني،
                // فالمحاولات السريعة ورا بعض نفسها تسبب 403 (حد الأجهزة)
                val uas = com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS
                val code = (error.cause as? com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)?.responseCode
                lastHost = (error.cause as? com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException)?.dataSpec?.uri?.host ?: lastHost
                if (retries++ < 3) {
                    altFormat = retries % 2 == 1
                    http.setUserAgent(uas[(retries / 2).coerceAtMost(uas.size - 1)])
                    message("يعيد المحاولة… (${retries + 1} من 4)")
                    val wait = if (code == 401 || code == 403 || code == 458 || code == 456) 6000L else 1500L
                    lifecycleScope.launch { delay(wait); play(index, keepRetries = true) }
                } else {
                    val base = "القناة ما تشتغل حالياً\n" + PlayErrors.reason(error) + (lastHost?.let { "\nالسيرفر: $it" } ?: "")
                    message(base)
                    com.turkialawlqy.aman.control.CrashReporter.report("قناة ما اشتغلت «${channels.getOrNull(index)?.name}»: ${PlayErrors.reason(error)}",
                        android.util.Log.getStackTraceString(error).take(4000))
                    // نفحص نفس الرابط من التلفزيون ونكتب السبب (بدون اسم المستخدم وكلمة المرور)
                    val c = channels.getOrNull(index)
                    if (c != null && (code == 401 || code == 403)) lifecycleScope.launch {
                        message("$base\n\nيفحص الاتصال…")
                        message("$base\n\n" + XtDiag.run(c.streamUrl))
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; msgView.visibility = View.GONE }
                if (state == Player.STATE_BUFFERING && player.playWhenReady && retries == 0) message("جاري التحميل…")
            }
        })

        val font = runCatching { ResourcesCompat.getFont(this, R.font.tajawal_bold) }.getOrNull()
        val extra = runCatching { ResourcesCompat.getFont(this, R.font.tajawal_extrabold) }.getOrNull()
        val t = AppThemes.current
        val view = StyledPlayerView(this).apply {
            useController = false
            this.player = this@LivePlayerActivity.player
            setBackgroundColor(Color.BLACK)
        }
        numView = TextView(this).apply { setTextColor(t.accent.toArgb()); textSize = 30f; typeface = extra }
        logoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF111111.toInt())
        }
        nameView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f; typeface = extra; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        nowView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = font; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        nextView = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 14f; typeface = font; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(t.radius.value + 2f); setColor((t.background.toArgb() and 0x00FFFFFF) or (0xEB shl 24)) }
            setPadding(dpi(20f), dpi(14f), dpi(20f), dpi(14f))
            addView(numView, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(16f) })
            addView(logoView, LinearLayout.LayoutParams(dpi(96f), dpi(54f)).apply { marginEnd = dpi(16f) })
            addView(LinearLayout(this@LivePlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(nameView); addView(nowView); addView(nextView)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        msgView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = font; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(6f); setColor(0xCC000000.toInt()) }
            setPadding(dpi(28f), dpi(12f), dpi(28f), dpi(12f))
            maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
            visibility = View.GONE
        }
        guideList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guideTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; typeface = extra; setPadding(0, 0, 0, dpi(10f)) }
        guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { setColor((t.background.toArgb() and 0x00FFFFFF) or (0xF2 shl 24)) }
            setPadding(dpi(24f), dpi(28f), dpi(24f), dpi(20f))
            addView(guideTitle)
            addView(android.widget.ScrollView(this@LivePlayerActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(guideList)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            visibility = View.GONE
        }
        guideFont = font
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(msgView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                val side = (resources.displayMetrics.widthPixels * 0.06f).toInt()
                leftMargin = side; rightMargin = side; bottomMargin = (resources.displayMetrics.heightPixels * 0.07f).toInt()
            })
            addView(guide, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.42f).toInt(), -1, Gravity.LEFT))
        })
        play(index)

        // كل 5 ثواني: وقت المشاهدة + القواعد (خلص الوقت / وقت النوم) + تحديث "يعرض الحين"
        lifecycleScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(5000)
                val now = SystemClock.elapsedRealtime()
                if (player.isPlaying) WatchTracker.addPlayed(now - last, false)
                last = now
                val s = TvRepository.state.value as? TvState.Ready
                if (s != null) {
                    val st = ParentalRules.current(s)
                    if (st.blocked || s.device.locked) { finish(); return@launch }
                }
                if (banner.visibility == View.VISIBLE) fillBanner()
            }
        }
    }

    private fun message(text: String) {
        msgView.text = text
        msgView.visibility = View.VISIBLE
    }

    /** نجرب الصيغة الثانية للقناة (بعض الاشتراكات تسمح بـ ts بس أو m3u8 بس) */
    private var altFormat = false
    private var lastHost: String? = null

    private fun urlFor(c: LiveChannel): String {
        val u = c.streamUrl
        if (!altFormat) return u
        return when {
            u.endsWith(".m3u8", true) -> u.dropLast(5) + ".ts"
            u.endsWith(".ts", true) -> u.dropLast(3) + ".m3u8"
            Regex("/live/[^/]+/[^/]+/\\d+$").containsMatchIn(u) || Regex("/[^/]+/[^/]+/\\d+$").containsMatchIn(u) -> "$u.ts"
            else -> u
        }
    }

    private fun play(i: Int, keepRetries: Boolean = false) {
        archiveTitle = null
        if (!keepRetries) { retries = 0; altFormat = false; http.setUserAgent(com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS[0]) }
        index = (i + channels.size) % channels.size
        val c = channels[index]
        WatchTracker.begin("live:${c.id}", c.name, c.id)
        LiveStore.played(c)
        val url = urlFor(c)
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build()).build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        if (!keepRetries) message("جاري التحميل…")
        showBanner()
    }

    private val clock = SimpleDateFormat("h:mm", Locale.ENGLISH)

    private fun fillBanner() {
        val c = channels[index]
        numView.text = "${index + 1}"
        nameView.text = (if (LiveStore.isFavorite(c)) "★ " else "") + c.name
        if (c.logo.isNullOrBlank()) logoView.setImageDrawable(null) else runCatching { logoView.load(c.logo) }
        val now = Epg.now(c.epgId)
        val next = Epg.next(c.epgId)
        nowView.text = now?.let { "الحين: ${it.title}  (${clock.format(Date(it.start))} – ${clock.format(Date(it.end))})" } ?: (c.groupName ?: "بث مباشر")
        nextView.text = next?.let { "بعده: ${it.title}  ${clock.format(Date(it.start))}" } ?: ""
        nextView.visibility = if (next == null) View.GONE else View.VISIBLE
    }

    private fun showBanner() {
        fillBanner()
        banner.visibility = View.VISIBLE
        hideJob?.cancel()
        hideJob = lifecycleScope.launch { delay(5000); banner.visibility = View.GONE }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.turkialawlqy.aman.control.AppUpdater.lastInteraction = System.currentTimeMillis()
        if (event.action != KeyEvent.ACTION_DOWN) return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> super.dispatchKeyEvent(event)
            else -> true
        }
        // الدليل مفتوح: الأسهم تتنقل بين البرامج، ورجوع/يمين يقفله
        if (guide.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                closeGuide(); return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_GUIDE || event.keyCode == KeyEvent.KEYCODE_MENU)) {
            openGuide(); return true
        }
        // ضغطة مطوّلة على OK = إضافة/إزالة من القنوات المفضلة
        if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount > 0) {
            if (event.isLongPress && channels.isNotEmpty()) {
                val added = LiveStore.toggleFavorite(channels[index])
                showBanner()
                message(if (added) "انضافت للمفضلة" else "انشالت من المفضلة")
                lifecycleScope.launch { delay(2000); if (player.isPlaying) msgView.visibility = View.GONE }
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> play(index + 1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> play(index - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_RIGHT -> showBanner()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; showBanner() }
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> {
                // أرقام الريموت: رقم القناة مباشرة
                val d = event.keyCode - KeyEvent.KEYCODE_0
                if (d in 0..9) { typeDigit(d); return true }
                return super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    // ------------------------------------------------------------------ دليل البرامج + الأرشيف
    private lateinit var guide: LinearLayout
    private lateinit var guideList: LinearLayout
    private lateinit var guideTitle: TextView
    private var guideFont: android.graphics.Typeface? = null
    /** يشاهد برنامج من الأرشيف (مو البث المباشر) */
    private var archiveTitle: String? = null
    private val hm = SimpleDateFormat("h:mm", Locale.US)
    private val dayName = SimpleDateFormat("EEEE", Locale("ar"))
    private val dayNum = SimpleDateFormat("d/M", Locale.US)
    private fun timeLabel(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return hm.format(Date(ms)) + if (c.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) " ص" else " م"
    }
    private val dayFmt = object { fun format(d: Date) = dayName.format(d) + " " + dayNum.format(d) }

    /** القناة من اشتراك Xtream؟ → (المصدر، رقم القناة) */
    private fun xtOf(c: LiveChannel): Pair<com.turkialawlqy.aman.data.IptvSource, String>? {
        val ready = TvRepository.state.value as? TvState.Ready ?: return null
        val src = ready.iptv.firstOrNull { it.id == c.sourceId }?.takeIf { it.xtream != null } ?: return null
        val id = if (c.id.startsWith("xl:")) c.id.substringAfterLast(':')
            else Regex("/(\\d+)(\\.\\w+)?$").find(c.streamUrl)?.groupValues?.get(1)
        return id?.let { src to it }
    }

    private fun closeGuide() {
        guide.visibility = View.GONE
        guideList.removeAllViews()
    }

    private fun openGuide() {
        if (channels.isEmpty()) return
        val c = channels[index]
        guideTitle.text = "دليل البرامج — ${c.name}"
        guideList.removeAllViews()
        guideList.addView(row("يجيب الدليل…", false) {})
        guide.visibility = View.VISIBLE
        banner.visibility = View.GONE
        lifecycleScope.launch {
            val xt = xtOf(c)
            val now = System.currentTimeMillis()
            val items: List<XtreamLibrary.Program> = if (xt != null) {
                runCatching { XtreamLibrary.epgTable(xt.first, xt.second) }.getOrDefault(emptyList())
            } else {
                c.epgId?.let { Epg.guide.value[it] }.orEmpty().map { XtreamLibrary.Program(it.start, it.end, it.title, null, false, "") }
            }
            if (guide.visibility != View.VISIBLE) return@launch
            guideList.removeAllViews()
            // البث المباشر أول
            val live = guideList.addAndGet(row("▶  البث المباشر الحين", archiveTitle == null) { closeGuide(); if (archiveTitle != null) play(index) })
            var focus: View = live
            if (items.isEmpty()) {
                guideList.addView(row(if (xt == null && c.epgId.isNullOrBlank()) "ما فيه دليل برامج لهالقناة" else "الدليل فاضي حالياً", false) {})
            }
            var lastDay = ""
            val shown = items.filter { it.end > now - 7L * 24 * 3600_000 && it.start < now + 24L * 3600_000 }
            for (p in shown) {
                val day = dayFmt.format(Date(p.start))
                if (day != lastDay) {
                    lastDay = day
                    guideList.addView(TextView(this@LivePlayerActivity).apply {
                        text = day; setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; typeface = guideFont
                        setPadding(dpi(6f), dpi(12f), dpi(6f), dpi(4f))
                    })
                }
                val isNow = now in p.start until p.end
                val past = p.end <= now
                val mark = when { isNow -> "  • الحين"; past && p.archive -> "  ↺ شاهده"; else -> "" }
                val v = guideList.addAndGet(row("${timeLabel(p.start)}   ${p.title}$mark", isNow, dim = past && !p.archive) {
                    when {
                        past && p.archive && xt != null -> {
                            val url = XtreamLibrary.timeshiftUrl(xt.first, xt.second, p) ?: return@row
                            closeGuide(); playArchive(url, p.title)
                        }
                        else -> { closeGuide(); if (archiveTitle != null) play(index) }
                    }
                })
                if (isNow) focus = v
            }
            focus.requestFocus()
        }
    }

    private fun LinearLayout.addAndGet(v: View): View { addView(v); return v }

    private fun row(text: String, highlight: Boolean, dim: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = guideFont
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
            isFocusable = true
            isClickable = true
            val base = if (dim) 0x80FFFFFF.toInt() else Color.WHITE
            setTextColor(if (highlight) AppThemes.current.accent.toArgb() else base)
            val shape = GradientDrawable().apply { cornerRadius = dp(6f); setColor(Color.TRANSPARENT) }
            background = shape
            setOnFocusChangeListener { _, has ->
                shape.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                setTextColor(if (has) Color.BLACK else if (highlight) AppThemes.current.accent.toArgb() else base)
            }
            setOnClickListener { onClick() }
        }

    /** يشغّل برنامج من الأرشيف */
    private fun playArchive(url: String, title: String) {
        archiveTitle = title
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        message("من الأرشيف: $title\nيسار ← البث المباشر")
        lifecycleScope.launch { delay(3500); if (player.isPlaying) msgView.visibility = View.GONE }
    }

    private var typed = ""
    private var typeJob: Job? = null
    private fun typeDigit(d: Int) {
        typed = (typed + d).take(4)
        numView.text = typed
        banner.visibility = View.VISIBLE
        typeJob?.cancel()
        typeJob = lifecycleScope.launch {
            delay(1500)
            val n = typed.toIntOrNull()
            typed = ""
            if (n != null && n in 1..channels.size) play(n - 1) else showBanner()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
        WatchTracker.end()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && channels.isNotEmpty()) {
            player.playWhenReady = true
            WatchTracker.begin("live:${channels[index].id}", channels[index].name, channels[index].id)
        }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        /** القنوات المرسلة للمشغّل (فيها قنوات المكتبة) */
        @Volatile var playlist: List<LiveChannel> = emptyList()

        fun start(context: Context, channels: List<LiveChannel>, index: Int) {
            playlist = channels
            context.startActivity(
                Intent(context, LivePlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_IDS, ArrayList(channels.map { it.id }))
                    .putExtra(EXTRA_INDEX, index)
            )
        }
    }
}
