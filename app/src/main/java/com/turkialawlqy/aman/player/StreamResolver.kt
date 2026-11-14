package com.turkialawlqy.aman.player

import android.net.Uri
import android.util.Log
import com.turkialawlqy.aman.offline.OfflineStore
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifest
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/** مسار واحد (فيديو أو صوت) جاهز للتشغيل — قابل للحفظ في فهرس المحتوى المحفوظ. */
@Serializable
data class PreparedStream(
    val url: String,
    val itag: Int,
    val type: String,            // video | audio | muxed
    val delivery: String,        // PROGRESSIVE_HTTP | DASH | HLS
    val manifest: String? = null, // DASH manifest مولّد (null = تشغيل مباشر)
    val manifestUrl: String? = null,
    val contentLength: Long = -1,
    val height: Int = 0,
) {
    /** يمكن حفظه بالكامل؟ (ملف واحد بحجم معروف) */
    val cacheable: Boolean get() = delivery == "PROGRESSIVE_HTTP" && contentLength > 0
}

@Serializable
data class PreparedMedia(
    val videoId: String,
    val streams: List<PreparedStream>,
    val hlsUrl: String? = null, // للبث المباشر
)

/**
 * اختيار المسارات من StreamInfo وبناء MediaSource لـ ExoPlayer.
 * المنطق مأخوذ من PlaybackResolver / PlayerDataSource في NewPipe (GPLv3).
 */
object StreamResolver {
    private const val TAG = "StreamResolver"

    fun cacheKey(videoId: String, itag: Int) = "yt:$videoId:$itag"

    /**
     * @param maxHeight أقصى دقة
     * @param cacheableOnly اختر فقط المسارات اللي تنحفظ (للتحميل)
     */
    fun prepare(info: StreamInfo, maxHeight: Int, cacheableOnly: Boolean = false): PreparedMedia {
        val videoId = info.id
        val type = info.streamType
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
            require(!cacheableOnly) { "Live streams cannot be downloaded" }
            val hls = info.hlsUrl
            require(!hls.isNullOrEmpty()) { "Live stream without HLS url" }
            return PreparedMedia(videoId, emptyList(), hlsUrl = hls)
        }

        val allowed = if (cacheableOnly) setOf(DeliveryMethod.PROGRESSIVE_HTTP)
        else setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.DASH, DeliveryMethod.HLS)

        val video = info.videoOnlyStreams
            .filter { it.deliveryMethod in allowed && it.height in 1..maxHeight }
            .filter { it.format == MediaFormat.MPEG_4 || it.format == MediaFormat.WEBM }
            .maxByOrNull { v ->
                // الأعلى دقة، ثم MP4 (أفضل دعم في أجهزة التلفزيون)، ثم 30fps، ثم التشغيل المباشر
                v.height * 1000 +
                    (if (v.format == MediaFormat.MPEG_4) 100 else 0) +
                    (if (v.fps in 1..30) 10 else 0) +
                    (if (v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 1 else 0)
            }

        val audios = info.audioStreams.filter { it.deliveryMethod in allowed }
        val audio = audios
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { audios }
            .maxByOrNull { a ->
                (if (a.format == MediaFormat.M4A) 1_000_000 else 0) +
                    // للتوفير: لا داعي لأعلى جودة صوت إذا الجودة منخفضة
                    (if (maxHeight <= 480) -a.averageBitrate.coerceAtMost(200) else a.averageBitrate)
            }

        val streams = if (video != null && audio != null) {
            listOf(toPrepared(video, "video", info), toPrepared(audio, "audio", info))
        } else {
            val muxed = info.videoStreams
                .filter { it.deliveryMethod in allowed && it.height <= maxHeight }
                .maxByOrNull { it.height }
                ?: info.videoStreams.firstOrNull { it.deliveryMethod in allowed }
                ?: throw IllegalStateException("No playable stream")
            listOf(toPrepared(muxed, "muxed", info))
        }

        if (cacheableOnly && streams.any { !it.cacheable }) {
            throw IllegalStateException("Streams are not downloadable")
        }
        return PreparedMedia(videoId, streams)
    }

    private fun toPrepared(stream: Stream, type: String, info: StreamInfo): PreparedStream {
        val itag = stream.itagItem
        val length = itag?.contentLength?.takeIf { it > 0 }
            ?: Uri.parse(stream.content).getQueryParameter("clen")?.toLongOrNull()
            ?: -1L
        val height = (stream as? VideoStream)?.height ?: 0

        val manifest: String? = when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP ->
                if (itag != null && (stream is AudioStream || (stream is VideoStream && stream.isVideoOnly))) {
                    try {
                        YoutubeProgressiveDashManifestCreator
                            .fromProgressiveStreamingUrl(stream.content, itag, info.duration)
                    } catch (e: Exception) {
                        Log.w(TAG, "DASH manifest failed, falling back to progressive", e)
                        null
                    }
                } else null
            DeliveryMethod.DASH -> YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
                stream.content, requireNotNull(itag) { "OTF stream without itag" }, info.duration
            )
            else -> null
        }

        return PreparedStream(
            url = stream.content,
            itag = itag?.id ?: stream.formatId,
            type = type,
            delivery = stream.deliveryMethod.name,
            manifest = manifest,
            manifestUrl = stream.manifestUrl,
            contentLength = length,
            height = height,
        )
    }

    /** يبني MediaSource. writeCache = حفظ ما يتم تشغيله في الذاكرة المختارة.
     *  offline = الفيديو محفوظ كامل → يقرأ من الذاكرة بس (يشتغل بدون إنترنت) */
    fun buildSource(media: PreparedMedia, writeCache: Boolean, offline: Boolean = false): MediaSource {
        media.hlsUrl?.let { hls ->
            return HlsMediaSource.Factory(OfflineStore.hlsFactory())
                .createMediaSource(MediaItem.fromUri(hls))
        }
        val sources = media.streams.map { sourceFor(media.videoId, it, writeCache, offline) }
        return if (sources.size == 1) sources[0]
        else MergingMediaSource(true, *sources.toTypedArray())
    }

    private fun sourceFor(videoId: String, s: PreparedStream, writeCache: Boolean, offline: Boolean = false): MediaSource {
        val key = cacheKey(videoId, s.itag)
        OfflineStore.registerUrl(s.url, key)
        val item = MediaItem.Builder()
            .setUri(Uri.parse(s.url))
            .setCustomCacheKey(key)
            .build()

        val local = if (offline) OfflineStore.offlineFactory() else null
        if (s.manifest != null) {
            val ds = local ?: OfflineStore.dashFactory(writeCache)
            return DashMediaSource.Factory(DefaultDashChunkSource.Factory(ds), ds)
                .createMediaSource(parseManifest(s.manifest, s.manifestUrl), item)
        }
        return when (s.delivery) {
            "HLS" -> HlsMediaSource.Factory(OfflineStore.hlsFactory()).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(local ?: OfflineStore.progressiveFactory(writeCache))
                .createMediaSource(item)
        }
    }

    private fun parseManifest(content: String, manifestUrl: String?): DashManifest =
        DashManifestParser().parse(
            Uri.parse(manifestUrl ?: ""),
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        )
}
