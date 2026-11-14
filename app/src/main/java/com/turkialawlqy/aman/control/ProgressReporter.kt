package com.turkialawlqy.aman.control

import android.util.Log
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.player.ResumeStore
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * "وين وصل" للوحة: مكان التوقف وإذا خلص الحلقة، لكل ملف شخصي.
 * نجمّع ونرسل كل دقيقتين بالكثير (مو مع كل حفظ).
 */
object ProgressReporter {
    private data class P(val profile: String, val videoId: String, val pos: Long, val dur: Long, val done: Boolean, val at: Long)

    private val pending = ConcurrentHashMap<String, P>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    fun queue(videoId: String, posMs: Long, durMs: Long, done: Boolean) {
        val profile = ProfileStore.current.value.orEmpty()
        pending["$profile|$videoId"] = P(profile, videoId, posMs / 1000, durMs / 1000, done, System.currentTimeMillis())
        if (job?.isActive == true) return
        job = scope.launch { delay(120_000); flush() }
    }

    private suspend fun flush() {
        val batch = pending.values.toList()
        if (batch.isEmpty()) return
        val ready = TvRepository.state.value as? TvState.Ready
        val items = ready?.items.orEmpty()
        val arr = JsonArray(batch.map { p ->
            val meta = ResumeStore.meta(p.videoId)
            val src = meta?.sourceId
            // المسلسل: قائمة من المحتوى، أو مسلسل من مكتبة الاشتراك (xs:…)
            val item = items.firstOrNull { it.id == src } ?: items.firstOrNull { it.youtubeId == p.videoId }
            val mix = when {
                item?.kind == "mix" -> item
                item?.parentId != null -> items.firstOrNull { it.id == item.parentId }
                else -> null
            }
            val seriesKey = mix?.id ?: src?.takeIf { it.startsWith("xs:") }
            val title = meta?.title.orEmpty()
            val seriesTitle = mix?.title ?: if (seriesKey != null && " · " in title) title.substringBefore(" · ") else null
            buildJsonObject {
                put("profile", p.profile)
                put("id", p.videoId)
                put("title", if (seriesTitle != null && title.startsWith("$seriesTitle · ")) title.removePrefix("$seriesTitle · ") else title)
                put("series", JsonPrimitive(seriesKey))
                put("series_title", JsonPrimitive(seriesTitle))
                put("pos", p.pos)
                put("dur", p.dur)
                put("done", p.done)
                put("at", iso(p.at))
            }
        })
        try {
            TvRepository.supabase.postgrest.rpc("device_report_progress", buildJsonObject { put("p_items", arr) })
            batch.forEach { b -> pending.remove("${b.profile}|${b.videoId}", b) }
        } catch (e: Exception) {
            Log.w("ProgressReporter", "send failed", e) // نحاول مع الدفعة الجاية
        }
    }
}
