package com.turkialawlqy.aman.player

import com.turkialawlqy.aman.data.episodeLabel
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.control.ParentalRules
import com.turkialawlqy.aman.control.WatchTracker
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineEntry
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.yt.VideoExtractor
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * مشغّل الفيديو: يشغّل قائمة روابط بالترتيب (للقوائم والقنوات) بدون أي فيديوهات مقترحة.
 * يغلق نفسه فوراً إذا قفل الأب التلفزيون أو حذف المحتوى من لوحة التحكم.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var messageView: TextView
    private lateinit var noticeView: TextView
    private var noticeJob: Job? = null
    /** انتهى الوقت المسموح → نكمّل الفيديو الحالي ثم نقفل */
    private var stopAfterCurrent = false
    private var blockedSince = 0L
    private var warned = false
    private var currentTitle = ""
    private var currentThumb: String? = null
    /** أزرار المشغّل (تصميم سينمائي) */
    private lateinit var controls: CinemaControls
    /** زر عائم: "تخطي المقدمة" أو "الحلقة التالية" قرب النهاية */
    private lateinit var smartBtn: android.widget.Button
    private var smartAction: (() -> Unit)? = null
    /** بطاقة "الحلقة التالية" آخر ثواني الحلقة */
    private lateinit var nextCard: NextUpCard
    private var introSkippedFor: String? = null
    private var nextOfferedFor: String? = null
    private lateinit var tvMessageView: TextView
    private var lastNowPlaying = 0L
    private var longPressHandled = false
    /** الفيديو الحالي لو كان خيار "حفظ المشاهَد" مفعّل */
    private var watching: OfflineEntry? = null
    private var currentVideoId: String? = null

    private var urls: List<String> = emptyList()
    private var index = 0
    private var sourceItemId: String? = null
    private var itemIds: List<String>? = null
    private var loadJob: Job? = null
    /** "شغّل الآن" من الأب: يتجاوز تخصيص المحتوى للطفل */
    private var parentRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        sourceItemId = intent.getStringExtra(EXTRA_SOURCE_ID)
        parentRequest = intent.getBooleanExtra(EXTRA_PARENT, false)
        itemIds = intent.getStringArrayListExtra(EXTRA_ITEM_IDS)?.takeIf { it.size == urls.size }
        if (urls.isEmpty()) {
            finish(); return
        }

        player = ExoPlayer.Builder(this).setLoadControl(FastStart.loadControl()).build()
        // الصوت والترجمة: العربي أول، وآخر لغة اختارها تنحفظ
        TrackPrefs.apply(this, player)
        playerView = StyledPlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowBuffering(StyledPlayerView.SHOW_BUFFERING_ALWAYS)
            useController = false // أزرارنا الخاصة (CinemaControls)
            setBackgroundColor(Color.BLACK)
        }
        val font = runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, com.turkialawlqy.aman.R.font.tajawal_bold) }.getOrNull()
        fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f; setColor(color) }
        messageView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = font
            gravity = Gravity.CENTER
            background = pill(0xCC0B1020.toInt())
            setPadding(56, 22, 56, 22)
        }
        noticeView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = font
            gravity = Gravity.CENTER
            background = pill(0xE61E1B3A.toInt())
            setPadding(48, 18, 48, 18)
            visibility = View.GONE
        }
        controls = CinemaControls(this, player, object : CinemaControls.Callbacks {
            override fun hasPrev() = index > 0
            override fun hasNext() = index + 1 < urls.size
            override fun onPrev() = playAt(index - 1)
            override fun onNext() = playAt(index + 1)
            override fun onDownload() = downloadNow()
            override fun onFavorite() = toggleFavorite()
            override fun isSaved() = currentVideoId?.let { OfflineStore.entry(it)?.complete == true } == true
            override fun isFavorite() = currentVideoId?.let { FavoritesStore.contains(it) } == true
            override fun intro() = introFor(index)
            override fun hasTracks() = TrackPrefs.hasChoices(player)
            override fun onTracks() = TrackPrefs.showDialog(this@PlayerActivity, player) { controls.refresh() }
            override fun canDownload() = !com.turkialawlqy.aman.yt.XtMedia.isXtId(currentVideoId) ||
                // من مكتبة الاشتراك: يتحمّل ويبقى محفوظ لين تحذفه
                Regex("^x[sve]:").containsMatchIn(itemIds?.getOrNull(index) ?: sourceItemId ?: "") ||
                // من الاشتراك: يتحمّل لو انضاف للمحتوى من اللوحة (مو من تصفح مكتبة الاشتراك)
                (TvRepository.state.value as? TvState.Ready)?.items?.any { it.youtubeId == currentVideoId } == true
        })
        // زر عائم أبيض: "تخطي المقدمة" / "الحلقة التالية"
        // "تخطى المقدمة": كبسولة فاتحة بنص غامق (مثل منصات البث)
        smartBtn = android.widget.Button(this).apply {
            isAllCaps = false
            textSize = 15f
            typeface = font
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setTextColor(0xFF111111.toInt())
            val pill = com.turkialawlqy.aman.ui.AppThemes.current.pillButtons
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = if (pill) 999f else 4f * resources.displayMetrics.density; setColor(android.graphics.Color.WHITE)
            }
            val dpx = resources.displayMetrics.density
            setPadding((24 * dpx).toInt(), (9 * dpx).toInt(), (24 * dpx).toInt(), (9 * dpx).toInt())
            elevation = 6f
            visibility = View.GONE
            isFocusable = true
            setOnClickListener { smartAction?.invoke(); hideSmart() }
            setOnFocusChangeListener { v, has ->
                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (has) Color.WHITE else 0xFFE9EBF2.toInt())
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(110).start()
            }
        }
        // رسالة الأب (تغطي الشاشة لين يضغط الطفل OK)
        tvMessageView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            setBackgroundColor(0xF00E1324.toInt())
            setPadding(80, 60, 80, 60)
            visibility = View.GONE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(messageView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(noticeView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 40
            })
            addView(controls.root, FrameLayout.LayoutParams(-1, -1))
            nextCard = NextUpCard(this@PlayerActivity).also { it.setOnPlay { playNextNow() } }
            // بطاقة الحلقة التالية: تحت يمين
            addView(nextCard.root, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply {
                bottomMargin = (resources.displayMetrics.heightPixels * 0.07f).toInt()
                rightMargin = (resources.displayMetrics.widthPixels * 0.06f).toInt()
            })
            addView(smartBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
                bottomMargin = (resources.displayMetrics.heightPixels * 0.07f).toInt()
                leftMargin = (resources.displayMetrics.widthPixels * 0.06f).toInt()
            })
            addView(tvMessageView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: com.google.android.exoplayer2.Tracks) {
                controls.refresh() // زر الصوت والترجمة يظهر لو فيه خيارات
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    recordWatch()
                    WatchTracker.end()
                    currentVideoId?.let { ResumeStore.markDone(it) } // انتهى → "شاهدته"، والمرة الجاية من البداية
                    currentVideoId = null
                    checkRules()
                    playAt(index + 1) // لو خلص الوقت يقفل (إلا لو التالي تعليمي)
                }
                if (state == Player.STATE_READY) messageView.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error", error)
                // فيلم/حلقة من الاشتراك: نعيد المحاولة بهوية مشغّل ثانية قبل ما نستسلم
                if (urls.getOrNull(index)?.let { com.turkialawlqy.aman.yt.XtMedia.isXt(it) } == true &&
                    xtTry < com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS.size - 1) {
                    xtTry++
                    showMessage("يعيد المحاولة…")
                    val code = (error.cause as? com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    // 403: نعطي السيرفر وقت يفك الاتصال الفاشل قبل المحاولة الثانية
                    lifecycleScope.launch { delay(if (code == 403 || code == 401) 6000 else 800); playAt(index) }
                    return
                }
                val msg = "تعذر تشغيل هذا الفيديو\n" + PlayErrors.reason(error) + details(error)
                showMessage(msg)
                com.turkialawlqy.aman.control.CrashReporter.report("تعذر تشغيل «$currentTitle»: ${PlayErrors.reason(error)}",
                    android.util.Log.getStackTraceString(error).take(4000))
                val xtUrl = urls.getOrNull(index)?.takeIf { com.turkialawlqy.aman.yt.XtMedia.isXt(it) }
                val httpCode = (error.cause as? com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (xtUrl != null && (httpCode == 401 || httpCode == 403)) {
                    // الاشتراك رفض: ما ننتقل للحلقة اللي بعدها (كل محاولة تاخذ اتصال وتزيد الرفض) — نفحص ونكتب السبب
                    lifecycleScope.launch {
                        showMessage("$msg\n\nيفحص الاتصال…")
                        val r = XtDiag.run(com.turkialawlqy.aman.yt.XtMedia.streamUrl(xtUrl))
                        showMessage("$msg\n\n$r")
                    }
                    return
                }
                lifecycleScope.launch { delay(errorDelayMs()); playAt(index + 1) }
            }
        })

        watchParentalChanges()
        // الأب عدّل اسم الحلقة من اللوحة → يتغير فوق على طول
        lifecycleScope.launch { TvRepository.state.collect { updateInfo() } }
        // رسالة من الأب
        lifecycleScope.launch {
            com.turkialawlqy.aman.control.TvMessages.current.collect { m ->
                if (m != null) {
                    player.pause()
                    tvMessageView.text = "${m.text}\n\n" + "اضغط OK"
                    tvMessageView.visibility = View.VISIBLE
                } else if (tvMessageView.visibility == View.VISIBLE) {
                    tvMessageView.visibility = View.GONE
                    player.play()
                }
            }
        }
        // ريموت من لوحة الأهل
        lifecycleScope.launch {
            com.turkialawlqy.aman.control.RemoteControl.commands.collect { c ->
                when (c) {
                    "pause" -> player.pause()
                    "play" -> player.play()
                    "next" -> playAt(index + 1)
                    "prev" -> playAt(index - 1)
                    "stop" -> finish()
                }
                reportNowPlaying(force = true)
            }
        }
        // كل 5 ثواني: نحفظ مكان التوقف، ونحسب وقت المشاهدة، ونتحقق من الوقت المسموح
        lifecycleScope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            while (true) {
                delay(5000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (player.isPlaying) {
                    savePosition()
                    WatchTracker.addPlayed(now - last, isEdu(index))
                }
                last = now
                checkRules()
                reportNowPlaying()
            }
        }
        // كل ربع ثانية: شريط التقدّم + زر "تخطي المقدمة" / "الحلقة التالية"
        lifecycleScope.launch {
            var n = 0
            while (true) {
                delay(250)
                controls.tick()
                updateSmartButton()
                // الزر العائم والبطاقة فوق لوح الأزرار لما يكون ظاهر
                val want = if (controls.isShowing) controls.coveredHeight + 16 else (root.height * 0.09f).toInt()
                listOf(smartBtn, nextCard.root).forEach { v ->
                    (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        if (lp.bottomMargin != want) { lp.bottomMargin = want; v.layoutParams = lp }
                    }
                }
            }
        }
        playAt(index)
    }

    /** العنوان فوق: اسم الحلقة + اسم القائمة ورقم الحلقة */
    private fun updateInfo() {
        if (!::controls.isInitialized) return
        val ready = TvRepository.state.value as? TvState.Ready
        // الاسم من لوحة التحكم (لو الأب عدّله) — وإلا اسم الملف/يوتيوب
        val item = (itemIds?.getOrNull(index) ?: sourceItemId)
            ?.let { id -> ready?.items?.firstOrNull { it.id == id } }?.takeIf { it.kind == "video" }
            ?: currentVideoId?.let { v -> ready?.items?.firstOrNull { it.youtubeId == v } }
        item?.title?.takeIf { it.isNotBlank() }?.let { currentTitle = it }
        // صورة الحلقة من لوحة التحكم (فيديوهات الفلاشة والمرفوعة ما لها صورة من المصدر)
        if (currentThumb.isNullOrBlank()) {
            currentThumb = item?.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: (item?.parentId ?: sourceItemId)?.let { p -> ready?.items?.firstOrNull { it.id == p }?.thumbnailUrl } // صورة المسلسل
        }
        val listTitle = sourceItemId?.let { s -> ready?.items?.firstOrNull { it.id == s } }
            ?.takeIf { it.kind != "video" }?.title
        val multi = urls.size > 1
        // "م2 · الحلقة 3" لو المسلسل فيه مواسم
        val eps = itemIds?.mapNotNull { id -> ready?.items?.firstOrNull { it.id == id } }?.takeIf { it.size == urls.size }
        val epLabel = eps?.episodeLabel(index) ?: "الحلقة ${index + 1}"
        val sub = listOfNotNull(listTitle, if (multi) epLabel else null).joinToString(" · ")
        controls.setInfo(currentTitle.ifBlank { listTitle ?: "" }, sub.ifBlank { null }, if (multi) "${index + 1} من ${urls.size}" else null)
        controls.refresh()
    }

    // ------------------------------------------------------------------ تخطي المقدمة + الحلقة التالية
    /** وقت المقدمة (بالملي ثانية) للفيديو رقم i — من الفيديو نفسه أو قائمته (يحدده الأب من اللوحة) */
    private fun introFor(i: Int): Pair<Long, Long>? {
        val ready = TvRepository.state.value as? TvState.Ready ?: return null
        val item = (itemIds?.getOrNull(i) ?: sourceItemId)?.let { id -> ready.items.firstOrNull { it.id == id } }
        val parent = item?.parentId?.let { p -> ready.items.firstOrNull { it.id == p } }
        val src = sourceItemId?.let { s -> ready.items.firstOrNull { it.id == s } }
        val withIntro = listOfNotNull(item, parent, src).firstOrNull { (it.introEnd ?: 0) > 0 } ?: return null
        val start = (withIntro.introStart ?: 0).coerceAtLeast(0) * 1000L
        val end = withIntro.introEnd!! * 1000L
        return if (end > start) start to end else null
    }

    /**
     * متى تبدأ نهاية الحلقة (بالملي ثانية) — يحددها الأب من اللوحة:
     * وقت لهذي الحلقة بالذات، وإلا مدة شارة النهاية للمسلسل كله (من آخر الحلقة).
     */
    private fun outroFor(i: Int, durMs: Long): Long? {
        val ready = TvRepository.state.value as? TvState.Ready ?: return null
        val item = itemIds?.getOrNull(i)?.let { id -> ready.items.firstOrNull { it.id == id } }
        item?.outroStart?.takeIf { it > 0 }?.let { s -> return (s * 1000L).takeIf { durMs <= 0 || it < durMs } }
        if (durMs <= 0) return null
        val parent = item?.parentId?.let { p -> ready.items.firstOrNull { it.id == p } }
        val src = sourceItemId?.let { s -> ready.items.firstOrNull { it.id == s } }
        val len = listOfNotNull(parent, src).firstNotNullOfOrNull { it.outroLen?.takeIf { l -> l > 0 } } ?: return null
        return (durMs - len * 1000L).takeIf { it > 30_000 }
    }

    /** لحظة ظهور بطاقة النهاية (عشان العدّاد يكمل 10 ثواني حتى لو قدّم) */
    private var outroShownFor: String? = null
    private var outroShownAtPos = 0L

    private fun showSmart(text: String, action: () -> Unit) {
        smartAction = action
        if (smartBtn.visibility != View.VISIBLE || smartBtn.text != text) {
            smartBtn.text = text
            smartBtn.visibility = View.VISIBLE
            // ياخذ التركيز (OK يضغطه) لو الطفل مو على أزرار التحكم
        }
        // "تخطي المقدمة" ياخذ التركيز مباشرة (OK يضغطه) — إلا لو الطفل يتنقل بين الأزرار الحين
        if (!smartBtn.hasFocus() && (!controls.isShowing || !controls.hasFocus())) smartBtn.requestFocus()
    }

    private fun hideSmart() {
        if (smartBtn.visibility == View.VISIBLE) {
            val hadFocus = smartBtn.hasFocus()
            smartBtn.visibility = View.GONE
            smartAction = null
            if (hadFocus && !controls.isShowing) playerView.requestFocus()
        }
    }

    private fun updateSmartButton() {
        if (!::player.isInitialized) return
        val vid = currentVideoId
        if (vid == null || player.playbackState != Player.STATE_READY || tvMessageView.visibility == View.VISIBLE) {
            hideSmart(); nextCard.hide(); return
        }
        val pos = player.currentPosition
        val dur = player.duration
        val intro = introFor(index)
        when {
            intro != null && introSkippedFor != vid && pos >= intro.first && pos < intro.second - 2000 ->
                showSmart("تخطي المقدمة") {
                    introSkippedFor = vid
                    player.seekTo(intro.second)
                }
            else -> hideSmart()
        }
        // بطاقة الحلقة التالية (مثل شاهد):
        // - لو الأب حدد نهاية الحلقة: تطلع عندها وبعد 10 ثواني تنتقل (تتخطى شارة النهاية)
        // - وإلا: آخر 15 ثانية من الحلقة
        val hasNext = index + 1 < urls.size && nextOfferedFor != vid && !stopAfterCurrent
        val outroAt = outroFor(index, dur)
        if (hasNext && outroAt != null && pos >= outroAt) {
            if (outroShownFor != vid) { outroShownFor = vid; outroShownAtPos = pos }
            val left = OUTRO_COUNT_MS - (pos - outroShownAtPos).coerceAtLeast(0)
            val (t, th) = nextInfo()
            nextCard.show(vid, t, th)
            nextCard.update(left.coerceAtLeast(0), OUTRO_COUNT_MS)
            if (!controls.isShowing && !nextCard.hasFocus()) nextCard.focus()
            if (left <= 0) playNextNow()
            return
        }
        if (outroAt == null || pos < outroAt) outroShownFor = null
        val remaining = dur - pos
        if (hasNext && dur > 60_000 && remaining in 0..NEXT_WINDOW_MS) {
            val (t, th) = nextInfo()
            nextCard.show(vid, t, th)
            nextCard.update(remaining, NEXT_WINDOW_MS)
            if (!controls.isShowing && !nextCard.hasFocus()) nextCard.focus()
        } else nextCard.hide()
    }

    /** اسم وصورة الحلقة الجاية (من لوحة التحكم) */
    private fun nextInfo(): Pair<String, String?> {
        val ready = TvRepository.state.value as? TvState.Ready
        val item = itemIds?.getOrNull(index + 1)?.let { id -> ready?.items?.firstOrNull { it.id == id && it.kind == "video" } }
        return (item?.title?.takeIf { it.isNotBlank() } ?: "الحلقة ${index + 2}") to item?.thumbnailUrl
    }

    private fun playNextNow() {
        val done = currentVideoId
        nextOfferedFor = done
        nextCard.hide()
        playAt(index + 1)
        // وصل للنهاية = الحلقة خلصت (ما تطلع في "تابع المشاهدة")
        done?.let { ResumeStore.markDone(it) }
    }

    /** هل الفيديو رقم i من محتوى تعليمي؟ (ما ينحسب من الحد) */
    private fun isEdu(i: Int): Boolean {
        val ready = TvRepository.state.value as? TvState.Ready ?: return false
        return ready.isEducational(itemIds?.getOrNull(i) ?: sourceItemId)
    }

    /** null = مسموح نشغّل الفيديو رقم i الآن */
    private fun blockedFor(i: Int): ParentalRules.Status? {
        val ready = TvRepository.state.value as? TvState.Ready ?: return null
        val st = ParentalRules.current(ready)
        if (!st.blocked) return null
        if (st.reason == "limit" && isEdu(i)) return null // خلص الترفيه بس التعليمي مسموح
        return st
    }

    /** الحد اليومي، الساعات المسموحة، مؤقت النوم */
    private fun checkRules() {
        val ready = TvRepository.state.value as? TvState.Ready ?: return
        val st = blockedFor(index) ?: ParentalRules.Status(false, remainingSec = ParentalRules.current(ready).remainingSec)
        if (st.blocked) {
            if (st.reason == "limit") {
                com.turkialawlqy.aman.control.Notifier.limitReached(
                    com.turkialawlqy.aman.control.ProfileStore.current.value, WatchTracker.usedTodaySec(),
                )
            }
            if (!stopAfterCurrent) {
                stopAfterCurrent = true
                blockedSince = System.currentTimeMillis()
                showNotice("${st.title} — هذا آخر فيديو", 8000)
            }
            // لو الفيديو طويل جداً ما ننتظر أكثر من 15 دقيقة
            if (System.currentTimeMillis() - blockedSince > 15 * 60 * 1000L) finish()
        } else {
            if (stopAfterCurrent) showNotice("ولي الأمر أضاف وقت", 4000)
            stopAfterCurrent = false
            val rem = st.remainingSec
            if (rem != null && rem <= 5 * 60) {
                if (!warned) {
                    warned = true
                    showNotice("باقي ${(rem + 59) / 60} دقائق على نهاية وقت المشاهدة", 8000)
                }
            } else warned = false
        }
    }

    private fun showNotice(text: String, ms: Long) {
        noticeView.text = text
        noticeView.visibility = View.VISIBLE
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch {
            delay(ms)
            noticeView.visibility = View.GONE
        }
    }

    /** يحفظ مكان التوقف للفيديو الحالي */
    private fun savePosition() {
        val id = currentVideoId ?: return
        if (!::player.isInitialized || player.playbackState == Player.STATE_IDLE) return
        ResumeStore.save(id, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** كم مرة جربنا هويات المشغّل لفيديو الاشتراك الحالي */
    private var xtTry = 0
    private var xtTryIndex = -1

    private fun playAt(i: Int) {
        if (i != xtTryIndex) { xtTry = 0; xtTryIndex = i }
        if (i >= urls.size) {
            finish(); return
        }
        if (i < 0) return
        if (i != index && blockedFor(i) != null) {
            finish(); return // خلص الوقت: ما نبدأ فيديو جديد (الرئيسية تعرض السبب)
        }
        if (!isAllowed(TvRepository.state.value, i)) {
            playAt(i + 1); return
        }
        savePosition() // لو انتقل لفيديو ثاني قبل ما يخلص
        WatchTracker.end()
        hideSmart()
        if (::nextCard.isInitialized) nextCard.hide()
        index = i
        currentVideoId = OfflineDownloader.videoIdOf(urls[i])
        loadJob?.cancel()
        showMessage("جاري التحميل…")
        loadJob = lifecycleScope.launch {
            try {
                val source = prepareSource(i)
                updateInfo()
                // يكمل من حيث وقف آخر مرة
                val start = currentVideoId?.let { ResumeStore.startPosition(it) } ?: 0L
                if (start > 0) player.setMediaSource(source, start) else player.setMediaSource(source)
                currentVideoId?.let { vid ->
                    // حلقة من مكتبة الاشتراك: نحفظ المسلسل نفسه (xs:…) عشان "أكمل المشاهدة" يكمل بالحلقات اللي بعدها
                    val src = sourceItemId?.takeIf { it.startsWith("xs:") } ?: itemIds?.getOrNull(i) ?: sourceItemId
                    WatchTracker.begin(vid, currentTitle, src)
                    ResumeStore.setMeta(VideoMeta(vid, currentTitle, currentThumb, urls[i], src))
                    showFavoriteHint()
                }
                player.prepare()
                player.playWhenReady = true
                controls.show() // اسم الحلقة يبان أول ثواني وبعدها يختفي
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "extract failed for ${urls[i]}", e)
                if (!isOnline()) {
                    // بدون إنترنت: نتخطى الفيديو غير المحفوظ بسرعة
                    showMessage("هذا الفيديو غير محفوظ ويحتاج إنترنت")
                    delay(1500)
                } else {
                    showMessage(if (e is com.turkialawlqy.aman.yt.TgMedia.NotReady) e.message.orEmpty() else "هذا الفيديو غير متاح" + details(e))
                    delay(errorDelayMs())
                }
                playAt(i + 1)
            }
        }
    }

    /**
     * 1) لو الفيديو محفوظ بالكامل → يشتغل من الذاكرة بدون إنترنت.
     * 2) غير كذا → نجيب الروابط من يوتيوب، ونحفظ أثناء المشاهدة لو الخيار مفعّل.
     */
    private suspend fun prepareSource(i: Int): MediaSource {
        val url = urls[i]
        val device = (TvRepository.state.value as? TvState.Ready)?.device
        val videoId = OfflineDownloader.videoIdOf(url)
        watching = null
        currentTitle = ""
        currentThumb = null

        // فيلم/حلقة من الاشتراك نزّلتها من الكمبيوتر على الفلاشة → من الفلاشة
        if (com.turkialawlqy.aman.yt.XtMedia.isXt(url)) {
            val onUsb = withContext(Dispatchers.IO) { com.turkialawlqy.aman.offline.XtLocal.find(this@PlayerActivity, videoId, url) }
            if (onUsb != null) {
                com.turkialawlqy.aman.yt.XtMedia.meta[videoId ?: ""]?.let { (t, th) -> currentTitle = t; currentThumb = th }
                return com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                    com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(this),
                ).createMediaSource(com.google.android.exoplayer2.MediaItem.fromUri(onUsb))
            }
        }
        // فيلم/حلقة من الاشتراك محفوظة على التلفزيون → من الذاكرة (بدون إنترنت وبدون ما تاخذ اتصال من الاشتراك)
        if (com.turkialawlqy.aman.yt.XtMedia.isXt(url)) {
            videoId?.let { OfflineStore.entry(it) }?.takeIf { it.complete }?.let { sv ->
                currentTitle = sv.title; currentThumb = sv.thumbnail
                return withContext(Dispatchers.Default) { StreamResolver.buildSource(sv.media, writeCache = false, offline = true) }
            }
        }
        // فيلم/حلقة من اشتراك IPTV: من سيرفر الاشتراك مباشرة (mp4 / mkv / m3u8)
        if (com.turkialawlqy.aman.yt.XtMedia.isXt(url)) {
            val real = com.turkialawlqy.aman.yt.XtMedia.streamUrl(url)
            com.turkialawlqy.aman.yt.XtMedia.meta[videoId ?: ""]?.let { (t, th) -> currentTitle = t; currentThumb = th }
            val http = com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory()
                .setUserAgent(com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS[xtTry.coerceIn(0, com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS.size - 1)])
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
            val item = com.google.android.exoplayer2.MediaItem.Builder().setUri(real).apply {
                if (real.contains(".m3u8", true)) setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8)
            }.build()
            return com.google.android.exoplayer2.source.DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(item)
        }

        // فيديو من فلاشة/هارديسك (منسوخ للتلفزيون أو يشتغل من الفلاشة مباشرة)
        if (com.turkialawlqy.aman.offline.LocalMedia.isLocal(url)) {
            val vid = com.turkialawlqy.aman.offline.LocalMedia.videoIdOf(url)!!
            val uri = com.turkialawlqy.aman.offline.LocalMedia.sourceFor(vid)
                ?: throw com.turkialawlqy.aman.yt.TgMedia.NotReady("هذا الفيديو مو موجود على التلفزيون — وصّل الفلاشة/الهارديسك اللي فيه أو استورده من جديد")
            currentTitle = com.turkialawlqy.aman.offline.LocalMedia.titleFor(vid)
            return com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(this),
            ).createMediaSource(com.google.android.exoplayer2.MediaItem.fromUri(uri))
        }

        val saved = videoId?.let { OfflineStore.entry(it) }?.takeIf { it.complete }
        if (saved != null) {
            currentTitle = saved.title
            currentThumb = saved.thumbnail
            // محفوظ كامل → من الذاكرة بس (حتى لو فيه إنترنت: أسرع، وما يحتاج روابط الاستضافة)
            return withContext(Dispatchers.Default) { StreamResolver.buildSource(saved.media, writeCache = false, offline = true) }
        }

        val save = device?.saveWatched == true
        val maxHeight = device?.playbackMaxHeight ?: 1080
        // NewPipe أولاً، ولو رفض يوتيوب (محتوى الأطفال مثلاً) → الطريقة البديلة
        val extracted = VideoExtractor.extract(url, maxHeight, preferCacheable = save)
        val media = extracted.media
        currentTitle = extracted.title
        currentThumb = extracted.thumbnail
        val canSave = save && media.streams.isNotEmpty() && media.streams.all { it.cacheable }
        if (canSave) {
            val source = itemIds?.getOrNull(i) ?: sourceItemId
            watching = OfflineEntry(
                videoId = media.videoId,
                title = extracted.title,
                thumbnail = extracted.thumbnail,
                durationSec = extracted.durationSec,
                media = media,
                sources = setOfNotNull(source),
            )
            recordWatch()
        }
        return withContext(Dispatchers.Default) { StreamResolver.buildSource(media, writeCache = canSave) }
    }

    /** يحدّث فهرس المحفوظ (هل اكتمل الفيديو؟ كم حجمه؟) */
    private fun recordWatch() {
        val w = watching ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val wasComplete = OfflineStore.entry(w.videoId)?.complete == true
                OfflineStore.upsert(w)
                if (!wasComplete && OfflineStore.entry(w.videoId)?.complete == true) {
                    OfflineDownloader.reportSoon()
                }
            } catch (e: Exception) {
                Log.w(TAG, "recordWatch failed", e)
            }
        }
    }

    /** في نسخة Debug نعرض سبب الخطأ على الشاشة عشان نقدر نشخّصه. */
    private fun details(e: Throwable, extra: String? = null): String {
        if (!BuildConfig.DEBUG) return ""
        val chain = generateSequence(e) { it.cause }.take(4).joinToString("\n") {
            "${it.javaClass.simpleName}: ${it.message?.take(160) ?: ""}"
        }
        return "\n\n" + listOfNotNull("v${BuildConfig.VERSION_NAME}", extra, chain).joinToString("\n")
    }

    private fun errorDelayMs() = if (BuildConfig.DEBUG) 20_000L else 3_000L

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun watchParentalChanges() {
        lifecycleScope.launch {
            TvRepository.state.collect { state ->
                if (!isAllowed(state, index)) {
                    // المحتوى الحالي حُذف أو الجهاز قُفل → نخرج فوراً
                    finish()
                }
            }
        }
    }

    /** هل ما زال ولي الأمر يسمح بتشغيل العنصر رقم i؟ */
    private fun isAllowed(state: TvState, i: Int): Boolean = when (state) {
        is TvState.Ready -> {
            val id = itemIds?.getOrNull(i) ?: sourceItemId
            // المحتوى المخصص للطفل الحالي (ملفات الأطفال)
            val items = if (parentRequest) state.items
                else state.visibleItems(com.turkialawlqy.aman.control.ProfileStore.current.value)
            val source = items.firstOrNull { it.id == sourceItemId }
            val videoId = urls.getOrNull(i)?.let { OfflineDownloader.videoIdOf(it) }
            // الأب أخفى هذا الفيديو من القائمة/القناة؟
            val hidden = source != null && !source.allows(videoId)
            // عناصر مكتبة الاشتراك (xv:/xe:) ما تكون في المحتوى — الأب يتحكم فيها من إخفاء التصنيفات
            val fromLibrary = id != null && (id.startsWith("xv:") || id.startsWith("xe:"))
            !state.device.locked && !hidden && (id == null || fromLibrary || items.any { it.id == id })
        }
        is TvState.Loading -> true
        else -> false
    }

    /** ضغطة مطوّلة على OK = إضافة/إزالة من "مفضلتي" */
    private fun toggleFavorite() {
        val vid = currentVideoId ?: return
        val src = itemIds?.getOrNull(index) ?: sourceItemId
        val added = FavoritesStore.toggle(VideoMeta(vid, currentTitle, currentThumb, urls[index], src))
        showNotice(if (added) "انضاف لمفضلتي" else "انشال من مفضلتي", 2500)
        controls.refresh()
    }

    /** نعلّم الطفل الميزة أول 3 مرات */
    private fun showFavoriteHint() {
        val prefs = getSharedPreferences("aman", MODE_PRIVATE)
        val n = prefs.getInt("fav_hint", 0)
        if (n >= 3) return
        prefs.edit().putInt("fav_hint", n + 1).apply()
        lifecycleScope.launch {
            delay(4000)
            showNotice("اضغط OK عشان تطلع الأزرار — وضغطة طويلة تضيفه لمفضلتي", 6000)
        }
    }

    /** "تنزيل": يبدأ تحميل الفيديو الحالي فوراً */
    private fun downloadNow() {
        val vid = currentVideoId ?: return
        val src = itemIds?.getOrNull(index) ?: sourceItemId
        val ok = OfflineDownloader.requestNow(VideoMeta(vid, currentTitle, currentThumb, urls[index], src))
        showNotice(if (ok) "بدأ التنزيل — تتابع النسبة من الإعدادات" else "الفيديو محفوظ أصلاً", 4000)
        controls.refresh()
    }

    /** ما يُعرض الآن → ريموت لوحة الأهل */
    private fun reportNowPlaying(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNowPlaying < 10_000) return
        lastNowPlaying = now
        val vid = currentVideoId ?: return
        val json = kotlinx.serialization.json.buildJsonObject {
            put("video_id", kotlinx.serialization.json.JsonPrimitive(vid))
            put("title", kotlinx.serialization.json.JsonPrimitive(currentTitle))
            put("thumb", kotlinx.serialization.json.JsonPrimitive(currentThumb))
            put("pos", kotlinx.serialization.json.JsonPrimitive(player.currentPosition / 1000))
            put("dur", kotlinx.serialization.json.JsonPrimitive((player.duration.coerceAtLeast(0)) / 1000))
            put("playing", kotlinx.serialization.json.JsonPrimitive(player.isPlaying))
            put("index", kotlinx.serialization.json.JsonPrimitive(index + 1))
            put("total", kotlinx.serialization.json.JsonPrimitive(urls.size))
            put("at", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
        }
        bg.launch { TvRepository.reportPlaying(json) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.turkialawlqy.aman.control.AppUpdater.lastInteraction = System.currentTimeMillis()
        // رسالة الأب مفتوحة: OK أو رجوع = حسناً
        if (tvMessageView.visibility == View.VISIBLE) {
            val k = event.keyCode
            if (event.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK)
            ) com.turkialawlqy.aman.control.TvMessages.dismiss()
            return true
        }
        val k = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val isOk = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        // ضغطة طويلة على OK = مفضلتي
        if (isOk) {
            if (down && event.repeatCount == 1) {
                longPressHandled = true
                toggleFavorite()
                return true
            }
            if (longPressHandled) {
                if (!down) longPressHandled = false
                return true
            }
        }
        // أزرار الوسائط في الريموت (تشتغل دايم)
        if (down) when (k) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playAt(index + 1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playAt(index - 1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (k == KeyEvent.KEYCODE_MEDIA_PLAY) player.play()
                else if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause()
                else controls.togglePlay()
                controls.show(focusPlay = !controls.isShowing); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controls.seekBy(10_000); controls.show(focusPlay = !controls.isShowing); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { controls.seekBy(-10_000); controls.show(focusPlay = !controls.isShowing); return true }
        }
        if (k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND
        ) return true

        if (controls.isShowing) {
            controls.onInput()
            // رجوع: يخفي الأزرار بس (ما يطلع من الفيديو)
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) controls.hide()
                return true
            }
            return super.dispatchKeyEvent(event) // التنقل بين الأزرار والضغط عليها
        }

        // الأزرار مخفية:
        // بطاقة الحلقة التالية: OK = شغّلها الآن، رجوع = إخفاء وكمّل الحلقة
        if (nextCard.isShowing && (isOk || k == KeyEvent.KEYCODE_BACK)) {
            if (!down) {
                if (isOk) playNextNow() else { nextOfferedFor = currentVideoId; nextCard.hide() }
            }
            return true
        }
        // زر "تخطي المقدمة" ظاهر → OK يضغطه (حتى لو ضاع التركيز)
        if (isOk && smartBtn.visibility == View.VISIBLE) {
            if (smartBtn.hasFocus()) return super.dispatchKeyEvent(event)
            if (!down) { smartAction?.invoke(); hideSmart() }
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (k) {
            // يسار/يمين: رجوع/تقديم 10 ثواني
            // يطلع الشريط وعليه التركيز → الضغطات اللي بعدها تكمّل تقديم/ترجيع
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { controls.seekBy(-10_000); controls.show(focusPlay = false, focusBar = true) }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) { controls.seekBy(10_000); controls.show(focusPlay = false, focusBar = true) }; return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) controls.show(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK: إيقاف/تشغيل + تطلع الأزرار
                if (!down) { controls.togglePlay(); controls.show() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        isActive = true
        // رجع للمشغّل بعد ما طلع منه
        val id = currentVideoId
        if (id != null && ::player.isInitialized && player.playbackState != Player.STATE_IDLE) {
            WatchTracker.begin(id, currentTitle, itemIds?.getOrNull(index) ?: sourceItemId)
        }
    }

    override fun onStop() {
        super.onStop()
        isActive = false
        bg.launch { TvRepository.reportPlaying(null) } // التلفزيون ما يعرض شي الآن
        savePosition()
        recordWatch()
        WatchTracker.end()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val NEXT_WINDOW_MS = 15_000L
        /** العدّ بعد ما توصل نهاية الحلقة اللي حددها الأب */
        private const val OUTRO_COUNT_MS = 10_000L
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_ITEM_IDS = "item_ids"
        const val EXTRA_PARENT = "parent_request"

        /** المشغّل ظاهر الآن؟ (ما نثبّت تحديث والطفل يشاهد) */
        @Volatile
        var isActive = false
        private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /**
         * @param sourceItemId عنصر القائمة/القناة اللي جات منه الفيديوهات
         * @param itemIds معرّفات عناصر الفيديو (موازية لـ urls) عند التشغيل من صف الفيديوهات
         */
        fun start(
            context: Context,
            urls: List<String>,
            index: Int,
            sourceItemId: String? = null,
            itemIds: List<String>? = null,
        ) {
            context.startActivity(buildIntent(context, urls, index, sourceItemId, itemIds))
        }

        fun buildIntent(
            context: Context,
            urls: List<String>,
            index: Int,
            sourceItemId: String? = null,
            itemIds: List<String>? = null,
        ): Intent {
            val intent = Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_SOURCE_ID, sourceItemId)
            if (itemIds != null) intent.putStringArrayListExtra(EXTRA_ITEM_IDS, ArrayList(itemIds))
            return intent
        }
    }
}
