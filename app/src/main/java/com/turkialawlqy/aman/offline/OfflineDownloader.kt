package com.turkialawlqy.aman.offline

import android.net.Uri
import android.util.Log
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.player.PreparedStream
import com.turkialawlqy.aman.player.StreamResolver
import com.turkialawlqy.aman.yt.CollectionCache
import com.turkialawlqy.aman.yt.VideoEntry
import com.turkialawlqy.aman.yt.VideoExtractor
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.ServiceList
import kotlin.math.min

/**
 * مدير التحميل المسبق:
 * - يحمّل المحتوى اللي حدده الأب (فيديوهات + آخر N من القوائم والقنوات) بالجودة المختارة.
 * - يحذف المحفوظ اللي ما عاد مسموح (الأب حذفه أو قفل الخيار).
 * - يرسل للأب حالة التخزين.
 */
object OfflineDownloader {
    private const val TAG = "OfflineDownloader"
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024
    private const val CHUNK_BYTES = 4L * 1024 * 1024
    private const val REPORT_COUNT = 60 // كم فيديو نعرضه للأب من كل قائمة/قناة
    private const val LISTING_MAX_AGE_MS = 3 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val xtLock = kotlinx.coroutines.sync.Mutex()
    private var job: Job? = null

    @Volatile
    private var pending: TvState.Ready? = null

    private val _status = MutableStateFlow<String?>(null)
    /** نص الحالة الحالي (يظهر في شاشة الإعدادات) */
    val status: StateFlow<String?> = _status.asStateFlow()

    private data class Wanted(
        val url: String,
        val title: String,
        val thumbnail: String?,
        val sources: MutableSet<String>,
    )

    /** يُستدعى كل ما تغيّر المحتوى أو الإعدادات */
    fun schedule(state: TvState.Ready) {
        pending = state
        if (job?.isActive == true) return // الحلقة الحالية بتلتقط التغيير
        job = scope.launch {
            delay(3000)
            while (true) {
                val s = pending ?: break
                pending = null
                try {
                    runOnce(s)
                } catch (e: Exception) {
                    Log.e(TAG, "run failed", e)
                } finally {
                    clearProgress()
                }
            }
        }
    }

    /** زر "حذف كل المحفوظ" */
    fun clearAll() {
        scope.launch {
            OfflineStore.clearAll()
            report(null)
        }
    }

    private suspend fun runOnce(state: TvState.Ready) {
        val d = state.device
        // حلقات نزلت من الكمبيوتر على الفلاشة (بس لو فيه محتوى من الاشتراك، ومرة كل كم دقيقة)
        if (state.items.any { com.turkialawlqy.aman.yt.XtMedia.isXtId(it.youtubeId) }) runCatching { XtLocal.refresh(com.turkialawlqy.aman.App.instance) }
        OfflineStore.ensure(d.storageVolume)
        if (OfflineStore.missingVolume != null) {
            // الفلاشة المختارة للتحميل مفصولة: ما نحمّل على ذاكرة الجهاز ولا نحذف شي — نكمل لما ترجع
            setStatus("الفلاشة مفصولة — ركّبها ويكمل التحميل")
            report(_status.value)
            return
        }
        if (!state.offline) runCatching { LocalMedia.cleanup(state.items) } // الأب حذف فيديو فلاشة → نحذف نسخته
        val itemIds = state.items.map { it.id }.toSet()

        // 1) الفيديوهات المطلوب تحميلها
        val wanted = LinkedHashMap<String, Wanted>()
        val onUsb = XtLocal.savedIds(state.items) // حلقات من الكمبيوتر على الفلاشة: ما تتحمّل
        val failedSources = mutableSetOf<String>()
        fun want(videoId: String?, url: String, title: String, thumb: String?, source: String) {
            if (videoId.isNullOrBlank() || LocalMedia.isLocalId(videoId) || videoId in onUsb) return
            wanted.getOrPut(videoId) { Wanted(url, title, thumb, mutableSetOf()) }.sources += source
        }

        val byId = state.items.associateBy { it.id }
        val listings = HashMap<String, List<VideoEntry>>()
        if (!state.offline) {
            CollectionCache.retainOnly(itemIds)
            for (item in state.items) {
                if (pending != null) return
                when (item.kind) {
                    "video" -> {
                        // من فلاشة التلفزيون: الملف عندنا أصلاً
                        if (LocalMedia.isLocalId(item.youtubeId)) continue
                        // فيديوهات تيليجرام واللي انرفعت من اللوحة: دايم تتحمّل (الاستضافة تحذفها بعدين)
                        val hosted = VideoExtractor.isHosted(item.youtubeId)
                        // مسلسل "أتابعه": الحلقات الجديدة (تنضاف معلّمة للتحميل) تتحمّل حتى لو التحميل التلقائي متوقف
                        val followed = item.offline && item.parentId?.let { byId[it]?.follow } == true
                        if (!hosted && !followed && (!d.autoDownload || !item.offline)) continue
                        // فيديو داخل قائمة خاصة: يتحمّل فقط لو القائمة نفسها مسموح تحميلها
                        if (!hosted && !followed && item.parentId != null && byId[item.parentId]?.offline == false) continue
                        want(item.youtubeId, item.url, item.title, item.thumbnailUrl, item.id)
                    }
                    "playlist", "channel" -> try {
                        // نحدّث محتوى القائمة (للأب وللتشغيل بدون إنترنت) كل 3 ساعات
                        val download = d.autoDownload && item.offline
                        val count = if (download) d.collectionDownloadLimit + item.excludedVideoIds.size else REPORT_COUNT
                        val list = CollectionCache.listing(item, count.coerceAtLeast(REPORT_COUNT), LISTING_MAX_AGE_MS)
                        listings[item.id] = list
                        if (download) {
                            // المخفي واللي ينتظر موافقة الأب ما يتحمّل
                            list.filter { v -> videoIdOf(v.url)?.let { item.allows(it) } ?: false }
                                .filterNot { state.titleBlocked(it.title) } // فلتر الكلمات
                                .take(d.collectionDownloadLimit)
                                .forEach { want(videoIdOf(it.url), it.url, it.title, it.thumbnail, item.id) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "collection listing failed: ${item.url}", e)
                        failedSources += item.id // لا نحذف محفوظه بسبب خطأ مؤقت
                    }
                }
            }
        }

        // 1ب) "تنزيل الآن": من التلفزيون (الأحدث أول) ثم أولوية الأب — حتى لو التحميل التلقائي متوقف
        // مكتبة الاشتراك (xs:/xv:/xe:) ما تكون في المحتوى — مسموحة دايم (الأب يتحكم من إخفاء التصنيفات)
        fun fromLibrary(src: String?) = src != null && Regex("^x[sve]:").containsMatchIn(src)
        val manualLocal = requests().filter { r ->
            (fromLibrary(r.sourceId) || byId[r.sourceId]?.allows(r.videoId) == true) && OfflineStore.entry(r.videoId)?.complete != true
        }
        saveRequests(manualLocal)
        manualLocal.forEach { r -> want(r.videoId, r.url, r.title, r.thumb, r.sourceId!!) }
        val manualIds = manualLocal.map { it.videoId } + d.downloadPriority
        if (!state.offline && manualIds.isNotEmpty()) {
            for (pid in d.downloadPriority) {
                if (pid in wanted) continue
                val video = state.items.firstOrNull { it.kind == "video" && it.youtubeId == pid }
                if (video != null) {
                    want(pid, video.url, video.title, video.thumbnailUrl, video.id)
                    continue
                }
                for ((srcId, list) in listings) {
                    val src = byId[srcId] ?: continue
                    if (!src.allows(pid)) continue
                    val e = list.firstOrNull { videoIdOf(it.url) == pid }
                        ?: CollectionCache.get(srcId)?.items?.firstOrNull { videoIdOf(it.url) == pid }
                        ?: continue
                    want(pid, e.url, e.title, e.thumbnail, srcId)
                    break
                }
            }
            // نرتّب: الأولوية أول بنفس ترتيب الأب، وبعدها الباقي
            val ordered = LinkedHashMap<String, Wanted>()
            manualIds.forEach { id -> wanted[id]?.let { ordered[id] = it } }
            wanted.forEach { (id, w) -> if (id !in ordered) ordered[id] = w }
            wanted.clear()
            wanted.putAll(ordered)
        }

        // 2) تنظيف: نحذف ما عاد مسموح (المحذوف، والمخفي من الأب)
        if (!state.offline) {
            fun allowedIn(src: String, videoId: String) =
                src in itemIds && byId[src]?.allows(videoId) == true
            for (e in OfflineStore.entries()) {
                val stillWanted = e.videoId in wanted || e.sources.any { fromLibrary(it) } ||
                    e.sources.any { it in failedSources && allowedIn(it, e.videoId) }
                val keepAsWatched = d.saveWatched && e.sources.any { allowedIn(it, e.videoId) }
                when {
                    stillWanted -> Unit
                    keepAsWatched -> if (e.downloaded) OfflineStore.update(e.videoId) { it.copy(downloaded = false) }
                    else -> OfflineStore.remove(e.videoId)
                }
            }
        }

        // أجزاء فيديوهات بدون سجل وما أحد يبغاها (من فصل الفلاشة في النسخ القديمة) → نرجع مساحتها
        if (!state.offline && failedSources.isEmpty()) {
            val freed = OfflineStore.pruneKeys(wanted.keys) + OfflineStore.sweptBytes
            OfflineStore.sweptBytes = 0L
            if (freed > 50L * 1024 * 1024) {
                setStatus("رجّعنا ${freed / (1024 * 1024)} ميجا من ملفات مكررة على مكان التحميل")
                report(_status.value)
            }
        }

        // فيديوهات اللوحة المحفوظة: نتأكد إنها انحذفت من الاستضافة (لو فشل قبل بسبب الإنترنت)
        if (!state.offline) {
            OfflineStore.entries().filter { it.complete && it.videoId.startsWith(com.turkialawlqy.aman.yt.UpMedia.ID_PREFIX) }
                .forEach { com.turkialawlqy.aman.yt.UpMedia.markDone(it.videoId) }
        }

        // 3) التحميل — أكثر من فيديو بنفس اللحظة
        // المكتمل: نحدّث مصادره بس
        for ((videoId, w) in wanted) {
            val existing = OfflineStore.entry(videoId) ?: continue
            if (existing.complete && (!existing.downloaded || !existing.sources.containsAll(w.sources))) {
                OfflineStore.update(videoId) { it.copy(downloaded = true, sources = it.sources + w.sources) }
            }
        }
        val queue = wanted.filterKeys { OfflineStore.entry(it)?.complete != true }.toList()
        val done = java.util.concurrent.atomic.AtomicInteger(wanted.size - queue.size)
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)
        val stopReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        currentQueue = queue.map { it.first to it.second.title }
        queueCursor = nextIndex
        overallTotal = wanted.size
        overallDone = done
        _queued.value = queue.map { it.first }.toSet()

        kotlinx.coroutines.coroutineScope {
            repeat(d.downloadParallel.coerceIn(1, MAX_PARALLEL_DOWNLOADS)) {
                launch {
                    while (true) {
                        if (pending != null || stopReason.get() != null) return@launch
                        val i = nextIndex.getAndIncrement()
                        if (i >= queue.size) return@launch
                        val (videoId, w) = queue[i]
                        if (videoId !in manualIds) {
                            // جدولة التحميل: وقت محدد و/أو واي فاي فقط (اللي طلبته "تنزيل الآن" ما ينتظر)
                            val wait = waitReason(d)
                            if (wait != null) { stopReason.compareAndSet(null, wait); return@launch }
                        }
                        if (OfflineStore.freeBytes() < MIN_FREE_BYTES) {
                            stopReason.compareAndSet(null, "المساحة غير كافية لإكمال التحميل")
                            return@launch
                        }
                        // الاشتراك يسمح بجهاز واحد غالباً → تحميلات الاشتراك واحد واحد (حتى لو التحميل المتوازي مفعّل)
                        val ok = if (com.turkialawlqy.aman.yt.XtMedia.isXtId(videoId)) xtLock.withLock { downloadOne(videoId, w, d, i, queue.size) }
                        else downloadOne(videoId, w, d, i, queue.size)
                        if (ok) done.incrementAndGet()
                        _queued.value = _queued.value - videoId
                    }
                }
            }
        }
        _queued.value = emptySet()

        val reason = stopReason.get()
        if (reason != null) {
            setStatus(reason)
            report(reason)
            if (reason.startsWith("التحميل ينتظر")) recheckLater()
            return
        }
        if (pending != null) return
        setStatus(if (wanted.isEmpty()) null else "تم تحميل ${done.get()} من ${wanted.size}")
        report(_status.value)
    }

    /** يحمّل فيديو واحد (كل مساراته) — true لو اكتمل */
    private suspend fun downloadOne(
        videoId: String,
        w: Wanted,
        d: com.turkialawlqy.aman.data.Device,
        index: Int,
        total: Int,
    ): Boolean {
        val p = Progress(videoId, w.title, w.thumbnail, index + 1, total)
        active[videoId] = p
        reportProgress(force = true)
        try {
            // NewPipe أولاً، ولو رفض يوتيوب → الطريقة البديلة (محتوى الأطفال يتحمّل كذلك)
            val x = VideoExtractor.extract(w.url, d.downloadQuality, requireCacheable = true)
            val media = x.media
            val entry = OfflineEntry(
                videoId = videoId,
                title = x.title.ifBlank { w.title },
                thumbnail = x.thumbnail ?: w.thumbnail,
                durationSec = x.durationSec,
                media = media,
                downloaded = true,
                sources = w.sources,
            )
            OfflineStore.upsert(entry) // نسجله قبل التحميل عشان الجزئي ما يضيع
            p.title = entry.title
            p.thumb = entry.thumbnail
            p.totalBytes = media.streams.sumOf { s -> s.contentLength.coerceAtLeast(0) }
            reportProgress(force = true)
            for (s in media.streams) {
                if (!downloadStream(videoId, s, p)) break
            }
            OfflineStore.upsert(entry)
            val complete = OfflineStore.entry(videoId)?.complete == true
            // انحفظ كامل → نحذفه من الاستضافة
            if (complete) com.turkialawlqy.aman.yt.UpMedia.markDone(videoId)
            return complete
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "download failed: ${w.url}", e)
            return false
        } finally {
            active.remove(videoId)
            reportProgress(force = true)
        }
    }

    // ------------------------------------------------------------------ "تنزيل الآن" من التلفزيون
    private val reqJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun requests(): List<com.turkialawlqy.aman.player.VideoMeta> = try {
        val prefs = com.turkialawlqy.aman.App.instance.getSharedPreferences("aman", android.content.Context.MODE_PRIVATE)
        prefs.getString("download_now", null)
            ?.let { reqJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.turkialawlqy.aman.player.VideoMeta.serializer()), it) }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveRequests(list: List<com.turkialawlqy.aman.player.VideoMeta>) {
        val prefs = com.turkialawlqy.aman.App.instance.getSharedPreferences("aman", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(
            "download_now",
            reqJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.turkialawlqy.aman.player.VideoMeta.serializer()), list),
        ).apply()
    }

    /**
     * زر "⬇ تنزيل" في المشغّل: يبدأ فوراً (قبل الباقي وبدون انتظار وقت التحميل).
     * @return false لو الفيديو محفوظ أصلاً
     */
    fun requestNow(meta: com.turkialawlqy.aman.player.VideoMeta): Boolean {
        if (OfflineStore.entry(meta.videoId)?.complete == true || meta.sourceId == null) return false
        saveRequests(listOf(meta) + requests().filterNot { it.videoId == meta.videoId })
        (TvRepository.state.value as? TvState.Ready)?.let { schedule(it) }
        return true
    }

    // ------------------------------------------------------------------ متابعة التحميل لحظياً (التلفزيون + لوحة الأهل)
    private const val MAX_PARALLEL_DOWNLOADS = 8 // العدد نفسه يحدده الأب من اللوحة (download_parallel)

    private class Progress(
        val videoId: String,
        @Volatile var title: String,
        @Volatile var thumb: String?,
        val index: Int,
        val total: Int,
    ) {
        @Volatile var totalBytes = 0L
        @Volatile var doneBytes = 0L
        val startedAt = android.os.SystemClock.elapsedRealtime()
        fun percent(): Int = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
    }

    private val active = java.util.concurrent.ConcurrentHashMap<String, Progress>()
    @Volatile private var currentQueue: List<Pair<String, String>> = emptyList()
    @Volatile private var queueCursor: java.util.concurrent.atomic.AtomicInteger? = null
    @Volatile private var overallTotal = 0
    @Volatile private var overallDone: java.util.concurrent.atomic.AtomicInteger? = null
    @Volatile private var lastReport = 0L
    @Volatile private var lastLocal = 0L
    private var progressSent = false

    private val _downloading = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** الفيديوهات اللي تتحمّل الآن ونسبة كل واحد (لدائرة التحميل على البطاقات) */
    val downloading: StateFlow<Map<String, Int>> = _downloading.asStateFlow()

    private val _queued = MutableStateFlow<Set<String>>(emptySet())
    /** بالطابور (ينتظر دوره) */
    val queued: StateFlow<Set<String>> = _queued.asStateFlow()

    @Synchronized
    private fun reportProgress(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        // البطاقات على التلفزيون تتحدّث كل ثانية، ولوحة الأهل كل 30 ثانية (كل تحديث = طلب على Supabase)
        _downloading.value = active.values.associate { it.videoId to it.percent() }
        if (!force && now - lastReport < 30_000) return
        lastReport = now
        val list = active.values.sortedBy { it.index }
        if (list.isEmpty()) {
            if (progressSent) {
                progressSent = false
                scope.launch { TvRepository.reportDownload(null) }
            }
            return
        }
        val doneN = overallDone?.get() ?: 0
        setStatus(
            if (list.size == 1) "جاري التحميل: ${list[0].title} — ${list[0].percent()}% (تم $doneN من $overallTotal)"
            else "جاري تحميل ${list.size} فيديوهات (تم $doneN من $overallTotal)"
        )
        fun item(p: Progress) = kotlinx.serialization.json.buildJsonObject {
            val secs = ((now - p.startedAt) / 1000.0).coerceAtLeast(0.5)
            put("video_id", kotlinx.serialization.json.JsonPrimitive(p.videoId))
            put("title", kotlinx.serialization.json.JsonPrimitive(p.title))
            put("thumb", kotlinx.serialization.json.JsonPrimitive(p.thumb))
            put("index", kotlinx.serialization.json.JsonPrimitive(p.index))
            put("total", kotlinx.serialization.json.JsonPrimitive(p.total))
            put("done_bytes", kotlinx.serialization.json.JsonPrimitive(p.doneBytes))
            put("total_bytes", kotlinx.serialization.json.JsonPrimitive(p.totalBytes))
            put("percent", kotlinx.serialization.json.JsonPrimitive(p.percent()))
            put("speed_bps", kotlinx.serialization.json.JsonPrimitive((p.doneBytes / secs).toLong()))
        }
        val first = item(list[0])
        val cursor = queueCursor?.get() ?: 0
        val next = currentQueue.drop(cursor).take(5)
        val json = kotlinx.serialization.json.buildJsonObject {
            first.forEach { (k, v) -> put(k, v) } // توافق مع النسخ القديمة من لوحة الأهل
            put("items", kotlinx.serialization.json.JsonArray(list.map { item(it) }))
            put("done", kotlinx.serialization.json.JsonPrimitive(doneN))
            put("overall_total", kotlinx.serialization.json.JsonPrimitive(overallTotal))
            put("next", kotlinx.serialization.json.JsonArray(next.map { (id, title) ->
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("title", kotlinx.serialization.json.JsonPrimitive(title))
                }
            }))
            put("at", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
        }
        progressSent = true
        scope.launch { TvRepository.reportDownload(json) }
    }

    private fun clearProgress() {
        active.clear()
        _queued.value = emptySet()
        reportProgress(force = true)
    }

    /** null = مسموح نحمّل الآن، أو سبب الانتظار */
    private fun waitReason(d: com.turkialawlqy.aman.data.Device): String? {
        if (!com.turkialawlqy.aman.control.ParentalRules.inWindow(d.downloadFrom, d.downloadTo)) {
            val p = com.turkialawlqy.aman.control.ParentalRules::pretty
            return "التحميل ينتظر وقته (من ${p(d.downloadFrom!!)} إلى ${p(d.downloadTo!!)})"
        }
        if (d.downloadWifiOnly && !onUnmeteredNetwork()) return "التحميل ينتظر شبكة واي فاي"
        return null
    }

    private fun onUnmeteredNetwork(): Boolean = try {
        val cm = com.turkialawlqy.aman.App.instance.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (e: Exception) {
        true
    }

    private var recheckJob: Job? = null

    /** نعيد المحاولة كل 10 دقائق لين يجي وقت التحميل */
    private fun recheckLater() {
        if (recheckJob?.isActive == true) return
        recheckJob = scope.launch {
            delay(10 * 60 * 1000L)
            (TvRepository.state.value as? TvState.Ready)?.let { schedule(it) }
        }
    }

    /** يحمّل مساراً كاملاً على دفعات (4 ميجا) — يكمل من حيث توقف */
    private fun downloadStream(videoId: String, s: PreparedStream, p: Progress? = null): Boolean {
        val ds = OfflineStore.downloadDataSource() ?: return false
        val key = StreamResolver.cacheKey(videoId, s.itag)
        OfflineStore.registerUrl(s.url, key)
        var pos = 0L
        if (com.turkialawlqy.aman.yt.XtMedia.isXtId(videoId)) {
            // الاشتراك: اتصال واحد للملف كامل (كل اتصال جديد يحسبه السيرفر "جهاز")، والمحفوظ قبل يتخطاه
            val spec = DataSpec.Builder().setUri(Uri.parse(s.url)).setKey(key).build()
            val base = p?.doneBytes ?: 0L
            CacheWriter(ds, spec, null) { _, cached, _ ->
                p?.let { it.doneBytes = base + cached }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastLocal > 1000) { lastLocal = now; reportProgress() }
                if (pending != null) throw java.io.InterruptedIOException("rescheduled")
            }.cache()
            return true
        }
        while (pos < s.contentLength) {
            if (pending != null) return false
            val len = min(CHUNK_BYTES, s.contentLength - pos)
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(s.url))
                .setPosition(pos)
                .setLength(len)
                .setKey(key)
                .build()
            CacheWriter(ds, spec, null, null).cache()
            pos += len
            p?.let { it.doneBytes += len }
            // تحديث الدائرة على التلفزيون (كل ثانية تقريباً)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastLocal > 1000) {
                lastLocal = now
                reportProgress()
            }
        }
        return true
    }

    private fun setStatus(s: String?) {
        _status.value = s
    }

    private suspend fun report(status: String?) {
        val items = (TvRepository.state.value as? TvState.Ready)?.items.orEmpty()
        val complete = (OfflineStore.entries().filter { it.complete }.map { it.videoId } + XtLocal.savedIds(items)).distinct()
        TvRepository.reportStorage(
            OfflineStore.volumes(), OfflineStore.usedBytes(), complete.size, status, complete,
        )
    }

    /** إرسال الحالة للأب (مثلاً بعد ما يكمل فيديو محفوظ أثناء المشاهدة) */
    fun reportSoon() {
        scope.launch { report(_status.value) }
    }

    fun videoIdOf(url: String): String? = VideoExtractor.videoIdOf(url)
}
