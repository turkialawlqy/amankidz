package com.turkialawlqy.aman.offline

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import com.turkialawlqy.aman.player.PreparedMedia
import com.turkialawlqy.aman.player.StreamResolver
import com.turkialawlqy.aman.player.YoutubeHttpDataSource
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory
import com.google.android.exoplayer2.upstream.cache.ContentMetadata
import com.google.android.exoplayer2.upstream.cache.ContentMetadataMutations
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** مكان تخزين متاح (ذاكرة الجهاز، فلاش USB، كرت SD) */
@Serializable
data class StorageOption(
    val id: String,
    val label: String,
    val free: Long,
    val total: Long,
    val removable: Boolean,
)

/** فيديو محفوظ (كامل أو جزئي) */
@Serializable
data class OfflineEntry(
    val videoId: String,
    val title: String = "",
    val thumbnail: String? = null,
    val durationSec: Long = 0,
    val media: PreparedMedia,
    val complete: Boolean = false,
    val bytes: Long = 0,
    val downloaded: Boolean = false,       // جاء من التحميل التلقائي
    val sources: Set<String> = emptySet(), // عناصر المحتوى اللي ينتمي لها
    val updatedAt: Long = 0,
)

/**
 * التخزين المحلي: ذاكرة ExoPlayer الدائمة (SimpleCache) + فهرس الفيديوهات المحفوظة.
 * نفس الذاكرة تخدم "حفظ المشاهَد" و"التحميل المسبق"، فأي فيديو محفوظ يشتغل بدون إنترنت.
 */
object OfflineStore {
    private const val TAG = "OfflineStore"
    const val DIR_NAME = "aman-media"
    const val PRIMARY = "primary"

    private lateinit var appContext: Context
    private val dbProvider by lazy { StandaloneDatabaseProvider(appContext) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var cache: SimpleCache? = null
    private var baseDir: File? = null

    private val index = ConcurrentHashMap<String, OfflineEntry>()
    private val _completeIds = MutableStateFlow<Set<String>>(emptySet())
    /** معرّفات الفيديوهات المحفوظة بالكامل (لعرض علامة "محفوظ") */
    val completeIds: StateFlow<Set<String>> = _completeIds.asStateFlow()

    // ربط روابط googlevideo (اللي تتغير) بمفتاح ثابت لكل فيديو/جودة
    private val urlKeys = ConcurrentHashMap<String, String>()
    private val idKeys = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------ أماكن التخزين

    private fun volumeIdOf(dir: File): Pair<String, android.os.storage.StorageVolume?> {
        val sm = appContext.getSystemService(StorageManager::class.java)
        val v = try { sm?.getStorageVolume(dir) } catch (e: Exception) { null }
        val primary = v?.isPrimary ?: (dir == appContext.getExternalFilesDir(null))
        return (if (primary) PRIMARY else (v?.uuid ?: dir.absolutePath)) to v
    }

    private fun mountedDirs(): List<File> =
        appContext.getExternalFilesDirs(null).filterNotNull().filter {
            try {
                Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            } catch (e: Exception) {
                false
            }
        }

    fun volumes(): List<StorageOption> = mountedDirs().mapNotNull { dir ->
        try {
            val (id, v) = volumeIdOf(dir)
            val label = if (id == PRIMARY) "ذاكرة الجهاز"
            else v?.getDescription(appContext) ?: "ذاكرة خارجية"
            StorageOption(id, label, dir.usableSpace, dir.totalSpace, id != PRIMARY)
        } catch (e: Exception) {
            null
        }
    }

    /** المجلد الأساسي على المكان المختار (أو ذاكرة الجهاز لو المكان غير موجود) */
    private fun baseDirFor(volumeId: String?): File {
        val dirs = mountedDirs()
        val match = dirs.firstOrNull { volumeIdOf(it).first == (volumeId ?: PRIMARY) }
            ?: dirs.firstOrNull { volumeIdOf(it).first == PRIMARY }
            ?: appContext.filesDir
        return File(match, DIR_NAME)
    }

    /** المكان المستخدم فعلياً (قد يختلف عن المختار لو الفلاش مفصول) */
    fun activeVolumeId(): String? = baseDir?.parentFile?.let { volumeIdOf(it).first }

    /** مكان التحميل المختار (فلاشة) مفصول الحين؟ → التحميل يوقف لين ترجع، وما ينحذف شي */
    @Volatile var missingVolume: String? = null
        private set

    /**
     * يفتح الذاكرة على المكان المختار.
     * - لو الأب غيّر المكان: يحذف المحفوظ في المكان القديم.
     * - لو الفلاشة المختارة مفصولة: يقفل الذاكرة بدون ما يحذف أو يحمّل شي على ذاكرة الجهاز،
     *   ولما ترجع الفلاشة يفتح نفس المحفوظ اللي عليها.
     */
    @Synchronized
    fun ensure(volumeId: String?) {
        val wanted = volumeId ?: PRIMARY
        val dirs = mountedDirs()
        val match = dirs.firstOrNull { volumeIdOf(it).first == wanted }
        if (match == null && wanted != PRIMARY) {
            if (missingVolume != wanted) Log.i(TAG, "storage $wanted not mounted — offline paused")
            releaseCache()
            missingVolume = wanted
            return
        }
        missingVolume = null
        val target = File(match ?: dirs.firstOrNull { volumeIdOf(it).first == PRIMARY } ?: appContext.filesDir, DIR_NAME)
        if (cache != null && target == baseDir) return
        val old = baseDir ?: lastBase
        releaseCache()
        // تغيير مكان التحميل من الأب (والمكان القديم لسا موجود): نحذف القديم
        if (old != null && old != target && old.exists() && old.canWrite()) {
            try {
                SimpleCache.delete(File(old, "cache"), dbProvider)
                File(old, "index.json").delete()
            } catch (e: Exception) {
                Log.w(TAG, "could not delete old cache", e)
            }
        }
        try {
            val cacheDir = File(target, "cache").apply { mkdirs() }
            val c = SimpleCache(cacheDir, NoOpCacheEvictor(), dbProvider)
            cache = c
            baseDir = target
            lastBase = target
            loadIndex()
            // التنظيف بالخلفية (ممكن يكون آلاف الملفات على الفلاشة) — مرة باليوم بالكثير
            val mark = File(target, ".swept")
            if (System.currentTimeMillis() - mark.lastModified() > 24 * 3600_000L) {
                Thread {
                    sweepOrphans(c, cacheDir)
                    runCatching { mark.writeText("") ; mark.setLastModified(System.currentTimeMillis()) }
                }.apply { priority = Thread.MIN_PRIORITY }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot open cache at $target", e)
            cache = null
            baseDir = null
        }
    }

    private var lastBase: File? = null

    /** كم بايت انحذف من ملفات يتيمة (آخر مرة) — للرسالة */
    @Volatile var sweptBytes = 0L

    /**
     * ملفات أجزاء فيديو على الفلاشة ما عاد يعرفها الفهرس (من فصل الفلاشة وقت التشغيل في النسخ القديمة:
     * الفيديو انحسب ناقص وتحمّل مرة ثانية، والنسخة القديمة ظلت تاخذ مساحة). نحذفها عشان ترجع المساحة.
     * يمشي لما تنفتح الذاكرة بس (قبل أي تحميل)، فما فيه ملف قيد الكتابة.
     */
    private fun sweepOrphans(c: SimpleCache, dir: File) {
        try {
            if (cache !== c) return // الذاكرة انقفلت/تغيّرت
            val live = HashSet<String>()
            for (k in c.keys) for (sp in c.getCachedSpans(k)) sp.file?.let { live += it.absolutePath }
            var freed = 0L
            var n = 0
            dir.walkTopDown().filter { it.name.endsWith(".exo") && it.absolutePath !in live }.forEach { f ->
                if (cache !== c) return
                val len = f.length()
                if (f.delete()) { freed += len; n++ }
            }
            if (n > 0) Log.i(TAG, "swept $n orphan files, ${freed / 1048576} MB")
            sweptBytes = freed
        } catch (e: Exception) {
            Log.w(TAG, "sweep failed", e)
        }
    }

    /**
     * فيديوهات أجزاؤها في الذاكرة بس ما لها سجل ولا أحد يبغاها → نحذفها.
     * (اللي مطلوب تحميله نخليه: التحميل يكمل عليه بدل ما يبدأ من الصفر)
     */
    @Synchronized
    fun pruneKeys(keepVideoIds: Set<String>): Long {
        val c = cache ?: return 0L
        val keep = keepVideoIds + index.keys
        var freed = 0L
        try {
            for (k in c.keys.toList()) {
                if (!k.startsWith("yt:")) continue
                val vid = k.removePrefix("yt:").substringBeforeLast(':')
                if (vid in keep) continue
                freed += c.getCachedSpans(k).sumOf { it.length }
                c.removeResource(k)
            }
        } catch (e: Exception) {
            Log.w(TAG, "prune failed", e)
        }
        return freed
    }

    /**
     * يقفل الذاكرة (الفلاشة انفصلت). مهم نقفلها بسرعة: لو المشغّل حاول يقرا ملف مو موجود
     * ExoPlayer يشيله من الفهرس، ولما ترجع الفلاشة يحسب الفيديو ناقص ويعيد تحميله.
     */
    @Synchronized
    fun releaseCache() {
        try { cache?.release() } catch (_: Exception) {}
        cache = null
        if (baseDir != null) lastBase = baseDir
        baseDir = null
        index.clear()
        urlKeys.clear(); idKeys.clear()
        publish()
    }

    /** الفلاشة اللي عليها الذاكرة انفصلت؟ (من إشعار النظام) */
    fun onMediaGone(path: String?) {
        val b = baseDir ?: return
        if (path == null || b.absolutePath.startsWith(path)) releaseCache()
    }

    fun freeBytes(): Long = baseDir?.usableSpace ?: 0L

    /** مجلد التطبيق على مكان التحميل المختار (ذاكرة الجهاز أو الفلاشة) */
    fun storageDir(): File? = baseDir

    /** اسم مكان التحميل الحالي (للواجهة) */
    fun activeLabel(): String = volumes().firstOrNull { it.id == (activeVolumeId() ?: PRIMARY) }?.label ?: "ذاكرة الجهاز"

    fun usedBytes(): Long = try { cache?.cacheSpace ?: 0L } catch (e: Exception) { 0L }

    // ------------------------------------------------------------------ مفاتيح الذاكرة

    fun registerUrl(url: String, key: String) {
        urlKeys[url] = key
        val u = Uri.parse(url)
        val id = u.getQueryParameter("id")
        val itag = u.getQueryParameter("itag")
        if (id != null && itag != null) idKeys["$id:$itag"] = key
    }

    private val keyFactory = CacheKeyFactory { spec: DataSpec ->
        val u = spec.uri
        urlKeys[u.toString()]
            ?: run {
                val id = u.getQueryParameter("id")
                val itag = u.getQueryParameter("itag")
                if (id != null && itag != null) idKeys["$id:$itag"] else null
            }
            ?: spec.key
            ?: u.toString()
    }

    // ------------------------------------------------------------------ مصادر البيانات

    private fun ytDash() = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(true).setRnParameterEnabled(true)

    private fun ytProgressive() = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(false).setRnParameterEnabled(true)

    fun hlsFactory(): DataSource.Factory = YoutubeHttpDataSource.Factory()

    fun dashFactory(write: Boolean): DataSource.Factory = cached(ytDash(), write)

    fun progressiveFactory(write: Boolean): DataSource.Factory = cached(ytProgressive(), write)

    private fun cached(upstreamRaw: DataSource.Factory, write: Boolean): DataSource.Factory {
        // فيديوهات اللوحة المقسومة أجزاء (upmedia://) تمر على MultiPartDataSource
        val upstream = RoutingDataSource.Factory(upstreamRaw)
        val c = cache ?: return upstream
        val f = CacheDataSource.Factory()
            .setCache(c)
            .setCacheKeyFactory(keyFactory)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (!write) f.setCacheWriteDataSinkFactory(null)
        return f
    }

    /** للتحميل المسبق (مع روابط range المقسّمة) */
    fun downloadDataSource(): CacheDataSource? {
        val c = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setCacheKeyFactory(keyFactory)
            .setUpstreamDataSourceFactory(RoutingDataSource.Factory(ytDash()))
            .createDataSourceForDownloading()
    }

    /** للنقل من تلفزيون ثاني (رابط محلي، والمفتاح من الطلب نفسه) */
    fun lanWriteSource(): CacheDataSource? {
        val c = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setCacheKeyFactory(keyFactory)
            .setUpstreamDataSourceFactory(
                com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(10_000).setReadTimeoutMs(60_000)
            )
            .createDataSourceForDownloading()
    }

    fun keyFor(url: String): String? = urlKeys[url]

    // ------------------------------------------------------------------ الفهرس

    private fun indexFile() = baseDir?.let { File(it, "index.json") }

    private fun loadIndex() {
        index.clear()
        try {
            val f = indexFile() ?: return
            if (f.exists()) {
                json.decodeFromString<List<OfflineEntry>>(f.readText()).forEach {
                    index[it.videoId] = it
                    it.media.streams.forEach { s -> registerUrl(s.url, StreamResolver.cacheKey(it.videoId, s.itag)) }
                    if (it.complete) markLengths(it.media)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "index unreadable", e)
        }
        publish()
    }

    @Synchronized
    private fun saveIndex() {
        try {
            val f = indexFile() ?: return
            val tmp = File(f.parentFile, "index.tmp")
            tmp.writeText(json.encodeToString(index.values.toList()))
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "index save failed", e)
        }
        publish()
    }

    private fun publish() {
        _completeIds.value = index.values.filter { it.complete }.map { it.videoId }.toSet()
    }

    fun entry(videoId: String): OfflineEntry? = index[videoId]
    fun entries(): List<OfflineEntry> = index.values.toList()

    /**
     * نكتب حجم الملف الكامل في بيانات الذاكرة. بدونه المشغّل لما يوصل لآخر جزء محفوظ يحاول يسأل
     * الإنترنت "خلص الملف؟" → بدون إنترنت يطلع "تعذر تشغيل الفيديو" مع إن الفيديو محفوظ كامل.
     */
    private fun markLengths(media: PreparedMedia) {
        val c = cache ?: return
        media.streams.forEach { st ->
            if (st.contentLength <= 0) return@forEach
            val key = StreamResolver.cacheKey(media.videoId, st.itag)
            try {
                if (ContentMetadata.getContentLength(c.getContentMetadata(key)) != st.contentLength) {
                    val m = ContentMetadataMutations()
                    ContentMetadataMutations.setContentLength(m, st.contentLength)
                    c.applyContentMetadataMutations(key, m)
                }
            } catch (e: Exception) {
                Log.w(TAG, "markLengths failed: $key", e)
            }
        }
    }

    /** للمحفوظ كامل: قراءة من الذاكرة بس (ما يلمس الإنترنت أبداً) */
    fun offlineFactory(): DataSource.Factory? {
        val c = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setCacheKeyFactory(keyFactory)
            .setUpstreamDataSourceFactory(null) // بدون مصدر خارجي
            .setCacheWriteDataSinkFactory(null)
    }

    /** هل كل أجزاء الفيديو موجودة في الذاكرة؟ */
    fun isFullyCached(media: PreparedMedia): Boolean {
        val c = cache ?: return false
        if (media.streams.isEmpty()) return false
        return media.streams.all {
            it.contentLength > 0 &&
                c.isCached(StreamResolver.cacheKey(media.videoId, it.itag), 0, it.contentLength)
        }
    }

    fun cachedBytes(media: PreparedMedia): Long {
        val c = cache ?: return 0L
        return media.streams.sumOf {
            val len = if (it.contentLength > 0) it.contentLength else Long.MAX_VALUE
            c.getCachedBytes(StreamResolver.cacheKey(media.videoId, it.itag), 0, len).coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun upsert(entry: OfflineEntry) {
        if (cache == null) return
        val prev = index[entry.videoId]
        // لو تغيّرت الجودة/المسارات نحذف القديم
        if (prev != null && prev.media.streams.map { it.itag } != entry.media.streams.map { it.itag }) {
            removeMedia(prev.media)
        }
        val merged = entry.copy(
            sources = (prev?.sources ?: emptySet()) + entry.sources,
            downloaded = entry.downloaded || (prev?.downloaded ?: false),
            complete = isFullyCached(entry.media),
            bytes = cachedBytes(entry.media),
            updatedAt = System.currentTimeMillis(),
        )
        if (merged.complete) markLengths(merged.media)
        index[entry.videoId] = merged
        saveIndex()
    }

    @Synchronized
    fun update(videoId: String, block: (OfflineEntry) -> OfflineEntry) {
        val e = index[videoId] ?: return
        index[videoId] = block(e)
        saveIndex()
    }

    @Synchronized
    fun remove(videoId: String) {
        val e = index.remove(videoId) ?: return
        removeMedia(e.media)
        saveIndex()
    }

    private fun removeMedia(media: PreparedMedia) {
        val c = cache ?: return
        media.streams.forEach {
            try {
                c.removeResource(StreamResolver.cacheKey(media.videoId, it.itag))
            } catch (_: Exception) {
            }
        }
    }

    /** حذف كل المحفوظ */
    @Synchronized
    fun clearAll() {
        val c = cache ?: return
        try {
            c.keys.toList().forEach { c.removeResource(it) }
        } catch (_: Exception) {
        }
        index.clear()
        saveIndex()
    }
}
