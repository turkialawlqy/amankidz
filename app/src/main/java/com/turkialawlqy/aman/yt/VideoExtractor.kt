package com.turkialawlqy.aman.yt

import android.util.Log
import com.turkialawlqy.aman.player.PreparedMedia
import com.turkialawlqy.aman.player.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Collections

/**
 * نقطة واحدة لجلب روابط أي فيديو:
 * 1) NewPipeExtractor (الأسرع)
 * 2) لو رفض يوتيوب (مثل "محتوى الأطفال") → InnertubeFallback (عميل التلفزيون + حل تحديات yt-dlp)
 */
object VideoExtractor {
    private const val TAG = "VideoExtractor"

    data class Extracted(
        val videoId: String,
        val title: String,
        val thumbnail: String?,
        val durationSec: Long,
        val media: PreparedMedia,
    )

    /** فيديوهات عرفنا إنها تحتاج الطريقة البديلة (نتخطى NewPipe لها) */
    private val needsFallback = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * @param preferCacheable حاول تختار مسارات قابلة للحفظ
     * @param requireCacheable لازم تكون قابلة للحفظ (للتحميل)
     */
    suspend fun extract(
        url: String,
        maxHeight: Int,
        preferCacheable: Boolean = false,
        requireCacheable: Boolean = false,
    ): Extracted = withContext(Dispatchers.IO) {
        // فيديو أرسله الأهل من تيليجرام
        if (TgMedia.isTg(url)) return@withContext TgMedia.extract(url)
        // فيديو رفعه الأهل من لوحة التحكم
        if (UpMedia.isUp(url)) return@withContext UpMedia.extract(url)
        // فيديو من فلاشة التلفزيون: ما يحتاج تحميل (المشغّل يقرأه مباشرة)
        if (com.turkialawlqy.aman.offline.LocalMedia.isLocal(url)) throw TgMedia.NotReady("فيديو من فلاشة التلفزيون — ما يحتاج تحميل")
        // أفلام/حلقات الاشتراك: ملف واحد على سيرفر الاشتراك (يتحمّل كامل)
        if (XtMedia.isXt(url)) return@withContext XtMedia.extract(url)
        val videoId = videoIdOf(url)
        var newPipeError: Exception? = null

        if (videoId == null || videoId !in needsFallback) {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                val media = when {
                    requireCacheable -> StreamResolver.prepare(info, maxHeight, cacheableOnly = true)
                    preferCacheable -> runCatching { StreamResolver.prepare(info, maxHeight, cacheableOnly = true) }
                        .getOrElse { StreamResolver.prepare(info, maxHeight) }
                    else -> StreamResolver.prepare(info, maxHeight)
                }
                return@withContext Extracted(
                    videoId = media.videoId,
                    title = info.name ?: "",
                    thumbnail = info.thumbnails.bestUrl(),
                    durationSec = info.duration,
                    media = media,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe failed for $url, trying fallback", e)
                newPipeError = e
            }
        }

        val id = videoId ?: throw (newPipeError ?: IllegalArgumentException("Unknown video url"))
        try {
            val r = InnertubeFallback.extract(id, maxHeight, cacheableOnly = requireCacheable)
            needsFallback += id
            Extracted(id, r.title, r.thumbnail, r.durationSec, r.media)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                listOfNotNull(
                    newPipeError?.let { "NewPipe: ${it.javaClass.simpleName}: ${it.message?.take(120)}" },
                    "Fallback: ${e.message?.take(300)}",
                ).joinToString("\n"),
                e,
            )
        }
    }

    /** رابط التشغيل من المعرّف (يوتيوب أو تيليجرام) */
    fun urlFor(videoId: String): String =
        when {
            videoId.startsWith(TgMedia.ID_PREFIX) -> TgMedia.urlFor(videoId)
            videoId.startsWith(UpMedia.ID_PREFIX) -> UpMedia.urlFor(videoId)
            com.turkialawlqy.aman.offline.LocalMedia.isLocalId(videoId) -> com.turkialawlqy.aman.offline.LocalMedia.urlFor(videoId)
            else -> "https://www.youtube.com/watch?v=$videoId"
        }

    /** فيديو محفوظ عندنا (تيليجرام أو مرفوع من اللوحة) — دايم يتحمّل على التلفزيون */
    fun isHosted(videoId: String?) = videoId != null &&
        (videoId.startsWith(TgMedia.ID_PREFIX) || videoId.startsWith(UpMedia.ID_PREFIX))

    fun videoIdOf(url: String): String? = if (XtMedia.isXt(url)) XtMedia.videoIdOf(url)
    else if (TgMedia.isTg(url)) TgMedia.videoIdOf(url)
    else if (UpMedia.isUp(url)) UpMedia.videoIdOf(url)
    else if (com.turkialawlqy.aman.offline.LocalMedia.isLocal(url)) com.turkialawlqy.aman.offline.LocalMedia.videoIdOf(url) else try {
        ServiceList.YouTube.streamLHFactory.getId(url)
    } catch (e: Exception) {
        Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.get(1)
    }
}
