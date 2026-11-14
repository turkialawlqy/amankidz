package com.turkialawlqy.aman.yt

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
import java.util.concurrent.TimeUnit

/**
 * فيديوهات تيليجرام اللي أرسلها الأهل للبوت.
 * الرابط في المحتوى: tgmedia:<file_unique_id>  ومعرّفه: tg_<file_unique_id>
 * الدالة (telegram) تعطي رابط موقّع للبث/التحميل — أو رابط مساعد الفيديوهات الكبيرة بالبيت.
 */
object TgMedia {
    const val PREFIX = "tgmedia:"
    const val ID_PREFIX = "tg_"

    /** الفيديو كبير ولسا المساعد ما جهّزه */
    class NotReady(message: String) : Exception(message)

    @Serializable
    private data class Info(
        val url: String? = null,
        val size: Long = -1,
        val title: String? = null,
        val thumb: String? = null,
        val duration: Long? = null,
        val height: Int? = null,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isTg(url: String) = url.startsWith(PREFIX)
    fun videoIdOf(url: String): String? = if (isTg(url)) ID_PREFIX + url.removePrefix(PREFIX) else null
    fun urlFor(videoId: String) = PREFIX + videoId.removePrefix(ID_PREFIX)

    suspend fun extract(url: String): VideoExtractor.Extracted = withContext(Dispatchers.IO) {
        val id = url.removePrefix(PREFIX)
        val token = TvRepository.supabase.auth.currentAccessTokenOrNull()
            ?: throw IllegalStateException("not signed in")
        val body = buildJsonObject {
            put("action", "media")
            put("id", id)
        }.toString()
        val req = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/telegram")
            .header("Authorization", "Bearer $token")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val info = http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val i = runCatching { json.decodeFromString<Info>(text) }.getOrNull()
            if (r.code == 409) throw NotReady("الفيديو كبير ولسا يتجهز على مساعد التحميل… جرّب بعد شوي")
            if (!r.isSuccessful || i?.url == null) throw IllegalStateException("tg media ${r.code}: ${i?.error ?: text.take(120)}")
            i
        }
        val videoId = ID_PREFIX + id
        VideoExtractor.Extracted(
            videoId = videoId,
            title = info.title.orEmpty(),
            thumbnail = info.thumb,
            durationSec = info.duration ?: 0,
            media = PreparedMedia(
                videoId,
                listOf(
                    PreparedStream(
                        url = info.url!!,
                        itag = 0,
                        type = "muxed",
                        delivery = "PROGRESSIVE_HTTP",
                        contentLength = info.size,
                        height = info.height ?: 0,
                    ),
                ),
            ),
        )
    }
}
