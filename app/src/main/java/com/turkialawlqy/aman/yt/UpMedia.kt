package com.turkialawlqy.aman.yt

import android.content.Context
import android.util.Log
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.player.PreparedMedia
import com.turkialawlqy.aman.player.PreparedStream
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * فيديوهات رفعها الأهل من لوحة التحكم (حلقات مسلسل مثلاً).
 * محفوظة في الاستضافة على شكل أجزاء (≤ 40 ميجا) — التلفزيون يقرأها كملف واحد
 * (MultiPartDataSource)، ولما يكمل حفظها عنده نبلغ الخادم يحذفها من الاستضافة.
 * رابطها في المحتوى: upmedia:<id>  ومعرّفها: up_<id>  ومسارها للمشغّل: upmedia://<id>
 */
object UpMedia {
    const val PREFIX = "upmedia:"
    const val ID_PREFIX = "up_"
    const val SCHEME = "upmedia"
    private const val TAG = "UpMedia"

    @Serializable
    data class Part(val url: String, val size: Long)

    @Serializable
    private data class Info(
        val title: String? = null,
        val size: Long = 0,
        val duration: Long? = null,
        val thumb: String? = null,
        val parts: List<Part> = emptyList(),
        val error: String? = null,
    )

    /** أجزاء كل فيديو (روابط موقّعة صالحة 12 ساعة) */
    private val registry = ConcurrentHashMap<String, List<Part>>()
    fun partsFor(id: String): List<Part>? = registry[id]

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isUp(url: String) = url.startsWith(PREFIX)
    private fun idOf(url: String) = url.removePrefix(PREFIX).removePrefix("//")
    fun videoIdOf(url: String): String? = if (isUp(url)) ID_PREFIX + idOf(url) else null
    fun urlFor(videoId: String) = PREFIX + videoId.removePrefix(ID_PREFIX)

    private suspend fun call(action: String, id: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = TvRepository.supabase.auth.currentAccessTokenOrNull()
            ?: throw IllegalStateException("not signed in")
        val body = buildJsonObject {
            put("action", action)
            put("id", id)
        }.toString()
        val req = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/telegram")
            .header("Authorization", "Bearer $token")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
    }

    suspend fun extract(url: String): VideoExtractor.Extracted {
        val id = idOf(url)
        val (code, text) = call("upload_urls", id)
        val info = runCatching { json.decodeFromString<Info>(text) }.getOrNull()
        when {
            code == 410 -> throw TgMedia.NotReady("هذا الفيديو انحذف من الاستضافة بعد ما تحمّل — ارفعه من جديد من لوحة التحكم")
            code == 409 -> throw TgMedia.NotReady("الفيديو لسا ينرفع من لوحة التحكم… جرّب بعد شوي")
            code !in 200..299 || info == null || info.parts.isEmpty() ->
                throw IllegalStateException("upload $code: ${info?.error ?: text.take(120)}")
        }
        registry[id] = info!!.parts
        val videoId = ID_PREFIX + id
        return VideoExtractor.Extracted(
            videoId = videoId,
            title = info.title.orEmpty(),
            thumbnail = info.thumb,
            durationSec = info.duration ?: 0,
            media = PreparedMedia(
                videoId,
                listOf(
                    PreparedStream(
                        url = "$SCHEME://$id",
                        itag = 0,
                        type = "muxed",
                        delivery = "PROGRESSIVE_HTTP",
                        contentLength = info.parts.sumOf { it.size },
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ الحذف من الاستضافة بعد الحفظ
    private fun prefs() = App.instance.getSharedPreferences("upmedia", Context.MODE_PRIVATE)

    /** نبلغ الخادم إن الفيديو انحفظ كامل (ونعيد المحاولة لاحقاً لو ما فيه إنترنت) */
    suspend fun markDone(videoId: String) {
        if (!videoId.startsWith(ID_PREFIX)) return
        val done = prefs().getStringSet("done", emptySet()).orEmpty()
        if (videoId in done) return
        try {
            val (code, _) = call("upload_done", videoId.removePrefix(ID_PREFIX))
            if (code in 200..299 || code == 403) {
                prefs().edit().putStringSet("done", (done + videoId).toSet()).apply()
                Log.i(TAG, "removed from hosting: $videoId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "markDone failed (retry later): $videoId", e)
        }
    }
}
