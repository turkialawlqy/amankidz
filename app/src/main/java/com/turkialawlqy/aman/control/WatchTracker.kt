package com.turkialawlqy.aman.control

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.turkialawlqy.aman.data.TvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * يحسب وقت المشاهدة اليومي (يشتغل حتى بدون إنترنت)،
 * ويسجّل كل فيديو تمت مشاهدته ويرسله للأب لما يتوفر الإنترنت.
 */
object WatchTracker {
    private const val TAG = "WatchTracker"
    private const val MIN_LOG_SEC = 10
    private const val MAX_PENDING = 300
    private const val FLUSH_MS = 2 * 60 * 1000L

    @Serializable
    data class Entry(
        val id: String,
        val title: String,
        val item: String? = null,
        val at: String,
        val sec: Int,
        val profile: String? = null,
    )

    private data class Session(
        val videoId: String,
        var title: String,
        val itemId: String?,
        val profileId: String?,
        val startedAt: Long,
        var ms: Long = 0,
    )

    private const val NO_PROFILE = "_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private var day = ""
    /** وقت اليوم لكل طفل (بالملي ثانية) */
    private val usedMs = HashMap<String, Long>()
    /** وقت المحتوى التعليمي اليوم لكل طفل — ما ينحسب من الحد */
    private val eduMs = HashMap<String, Long>()
    private val _eduSec = MutableStateFlow(0L)
    val eduSec: StateFlow<Long> = _eduSec.asStateFlow()
    private var current: Session? = null
    private var lastReported = ""

    private val _usedSec = MutableStateFlow(0L)
    /** ثواني مشاهدة الطفل الحالي اليوم */
    val usedSec: StateFlow<Long> = _usedSec.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
        day = prefs.getString("day", "") ?: ""
        try {
            prefs.getString("used_map", null)?.let { usedMs.putAll(json.decodeFromString<Map<String, Long>>(it)) }
            prefs.getString("edu_map", null)?.let { eduMs.putAll(json.decodeFromString<Map<String, Long>>(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "bad used map", e)
        }
        // ترقية من النسخة السابقة (وقت واحد للجهاز)
        if (usedMs.isEmpty()) prefs.getLong("used_ms", 0).takeIf { it > 0 }?.let { usedMs[NO_PROFILE] = it }
        rollDay()
        refresh()
        scope.launch {
            while (true) {
                delay(FLUSH_MS)
                flush()
            }
        }
    }

    private fun key() = ProfileStore.current.value ?: NO_PROFILE

    @Synchronized
    private fun rollDay() {
        val today = ParentalRules.today()
        if (day != today) {
            day = today
            usedMs.clear()
            eduMs.clear()
            save()
        }
    }

    private fun save() {
        prefs.edit().putString("day", day)
            .putString("used_map", json.encodeToString(usedMs.toMap()))
            .putString("edu_map", json.encodeToString(eduMs.toMap()))
            .apply()
    }

    /** يحدّث الرقم المعروض بعد تغيير الطفل */
    @Synchronized
    fun refresh() {
        rollDay()
        _usedSec.value = (usedMs[key()] ?: 0) / 1000
        _eduSec.value = (eduMs[key()] ?: 0) / 1000
    }

    /** دقائق الترفيه اللي كسبها الطفل الحالي من التعليمي اليوم */
    @Synchronized
    fun earnedMinutes(d: com.turkialawlqy.aman.data.Device): Int {
        val every = d.eduEveryMin ?: return 0
        if (every <= 0) return 0
        rollDay()
        val eduMin = ((eduMs[key()] ?: 0) / 60_000).toInt()
        return minOf(d.eduMaxMin, (eduMin / every) * d.eduRewardMin)
    }

    /** وقت الطفل الحالي اليوم */
    @Synchronized
    fun usedTodaySec(): Long {
        rollDay()
        return (usedMs[key()] ?: 0) / 1000
    }

    @Synchronized
    fun totalTodaySec(): Long {
        rollDay()
        return usedMs.values.sum() / 1000
    }

    /** يُستدعى من المشغّل أثناء التشغيل الفعلي فقط */
    @Synchronized
    fun addPlayed(ms: Long, educational: Boolean = false) {
        if (ms <= 0 || ms > 60_000) return // حماية من القفزات (نوم الجهاز مثلاً)
        rollDay()
        val k = current?.profileId ?: key()
        if (educational) eduMs[k] = (eduMs[k] ?: 0) + ms // التعليمي ما ينحسب من الحد
        else usedMs[k] = (usedMs[k] ?: 0) + ms
        current?.let { it.ms += ms }
        save()
        _usedSec.value = (usedMs[key()] ?: 0) / 1000
        _eduSec.value = (eduMs[key()] ?: 0) / 1000
    }

    @Synchronized
    fun begin(videoId: String, title: String, itemId: String?) {
        end()
        val profile = ProfileStore.current.value
        current = Session(videoId, title, itemId, profile, System.currentTimeMillis())
        Notifier.started(profile)
    }

    @Synchronized
    fun setTitle(title: String) {
        if (title.isNotBlank()) current?.title = title
    }

    @Synchronized
    fun end() {
        val s = current ?: return
        current = null
        val sec = (s.ms / 1000).toInt()
        if (sec < MIN_LOG_SEC) return
        val list = pending().toMutableList()
        list += Entry(s.videoId, s.title, s.itemId, iso(s.startedAt), sec, s.profileId)
        savePending(list.takeLast(MAX_PENDING))
        scope.launch { flush() }
    }

    /** يرسل السجل ووقت اليوم (الإجمالي ولكل طفل) للأب */
    suspend fun flush() {
        val list: List<Entry>
        val byProfile: Map<String, Long>
        val eduBy: Map<String, Long>
        val today: String
        synchronized(this) {
            rollDay()
            list = pending()
            byProfile = usedMs.filterKeys { it != NO_PROFILE }.mapValues { it.value / 1000 }
            eduBy = eduMs.mapValues { it.value / 1000 }
            today = day
        }
        val total = totalTodaySec()
        val signature = "$today|$total|$byProfile|$eduBy"
        if (list.isEmpty() && signature == lastReported) return
        val ok = TvRepository.logWatch(list, today, total, byProfile, eduBy)
        if (ok) {
            synchronized(this) {
                // نحذف المرسَل فقط (ممكن انضاف جديد أثناء الإرسال)
                savePending(pending().drop(list.size))
            }
            lastReported = signature
        }
    }

    private fun pending(): List<Entry> = try {
        prefs.getString("pending", null)?.let { json.decodeFromString<List<Entry>>(it) } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "bad pending list", e)
        emptyList()
    }

    private fun savePending(list: List<Entry>) {
        prefs.edit().putString("pending", json.encodeToString(list)).apply()
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}
