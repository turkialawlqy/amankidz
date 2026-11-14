package com.turkialawlqy.aman.player

import android.content.Context
import android.content.SharedPreferences
import com.turkialawlqy.aman.control.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** بيانات فيديو تكفي لعرضه وتشغيله من صف "أكمل المشاهدة" أو "مفضلتي" */
@Serializable
data class VideoMeta(
    val videoId: String,
    val title: String,
    val thumb: String? = null,
    val url: String,
    val sourceId: String? = null,
    val at: Long = System.currentTimeMillis(),
)

/**
 * يحفظ مكان التوقف لكل فيديو (لكل طفل لحاله)،
 * عشان لما يرجع له يكمل من نفس المكان، ويظهر في صف "أكمل المشاهدة".
 */
object ResumeStore {
    private const val MIN_MS = 5_000L         // أقل من 5 ثواني = ما يحتاج متابعة
    private const val END_MARGIN_MS = 15_000L // آخر 15 ثانية = الفيديو انتهى
    private const val MAX_ENTRIES = 500

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var metaPrefs: SharedPreferences

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** نسبة المشاهدة لكل فيديو (للطفل الحالي) */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _continue = MutableStateFlow<List<VideoMeta>>(emptyList())
    /** صف "أكمل المشاهدة" للطفل الحالي (الأحدث أول) */
    val continueWatching: StateFlow<List<VideoMeta>> = _continue.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)
        metaPrefs = context.getSharedPreferences("resume_meta", Context.MODE_PRIVATE)
        refresh()
    }

    /** الطفل بدون ملف يستخدم المفتاح القديم (توافق مع النسخ السابقة) */
    private fun key(videoId: String): String =
        ProfileStore.current.value?.let { "$it|$videoId" } ?: videoId

    private fun ownsKey(k: String): Boolean {
        val p = ProfileStore.current.value
        return if (p == null) '|' !in k else k.startsWith("$p|")
    }

    private fun videoOf(k: String) = k.substringAfter('|')

    /** مكان البداية (0 = من الأول) */
    fun startPosition(videoId: String): Long = read(key(videoId))?.first ?: 0L

    /** بيانات العرض (العنوان والصورة) — تُحفظ أول ما يبدأ الفيديو */
    fun setMeta(meta: VideoMeta) {
        if (!::metaPrefs.isInitialized) return
        metaPrefs.edit().putString(meta.videoId, json.encodeToString(meta)).apply()
    }

    fun meta(videoId: String): VideoMeta? = try {
        metaPrefs.getString(videoId, null)?.let { json.decodeFromString<VideoMeta>(it) }
    } catch (e: Exception) {
        null
    }

    fun save(videoId: String, positionMs: Long, durationMs: Long) {
        if (!::prefs.isInitialized) return
        val finished = durationMs > 0 && positionMs > durationMs - END_MARGIN_MS
        if (finished) { markDone(videoId); return }
        if (positionMs < MIN_MS) {
            // رجع للبداية: لو كان شاهده كامل قبل نخليه "شاهدته"
            if (prefs.getString(key(videoId), null)?.startsWith("done|") != true) clear(videoId)
            return
        }
        val editor = prefs.edit().putString(key(videoId), "$positionMs|$durationMs|${System.currentTimeMillis()}")
        // تنظيف الأقدم لو كثرت
        val all = prefs.all
        if (all.size > MAX_ENTRIES) {
            all.entries.sortedBy { (it.value as? String)?.split("|")?.getOrNull(2)?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
        com.turkialawlqy.aman.control.ProgressReporter.queue(videoId, positionMs, durationMs, false)
        if (durationMs > 0) {
            _progress.value = _progress.value + (videoId to (positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
        }
        if (_continue.value.firstOrNull()?.videoId != videoId) refresh()
    }

    /** خلص الفيديو: يتعلّم "شاهدته" (شريط كامل)، ويبدأ من الأول المرة الجاية، وما يطلع في "أكمل المشاهدة" */
    fun markDone(videoId: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(key(videoId), "done|0|${System.currentTimeMillis()}").apply()
        com.turkialawlqy.aman.control.ProgressReporter.queue(videoId, 0, 0, true)
        _progress.value = _progress.value + (videoId to 1f)
        _continue.value = _continue.value.filterNot { it.videoId == videoId }
    }

    fun clear(videoId: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(key(videoId)).apply()
        _progress.value = _progress.value - videoId
        _continue.value = _continue.value.filterNot { it.videoId == videoId }
    }

    private fun read(k: String): Pair<Long, Long>? {
        if (!::prefs.isInitialized) return null
        val parts = prefs.getString(k, null)?.split("|") ?: return null
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return pos to dur
    }

    /** يعيد الحساب للطفل الحالي (بعد تغيير الطفل مثلاً) */
    fun refresh() {
        if (!::prefs.isInitialized) return
        val mine = prefs.all.filterKeys { ownsKey(it) }.mapNotNull { (k, v) ->
            val parts = (v as? String)?.split("|") ?: return@mapNotNull null
            if (parts[0] == "done") return@mapNotNull Triple(videoOf(k), 1f, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
            val pos = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val dur = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val ts = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            Triple(videoOf(k), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, ts)
        }
        _progress.value = mine.associate { it.first to it.second }
        _continue.value = mine
            .filter { it.second in 0.02f..0.95f }
            .sortedByDescending { it.third }
            .mapNotNull { meta(it.first) }
            .take(20)
    }
}
