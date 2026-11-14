package com.turkialawlqy.aman.yt

/**
 * أفلام وحلقات من اشتراك IPTV (Xtream) اختارها الأب من اللوحة.
 * رابطها في المحتوى: xtmedia:<المعرّف>@<رابط الملف المباشر>
 * والمعرّف (xt_…) هو نفسه youtube_id في المحتوى — عشان "تابع المشاهدة" والمفضلة تشتغل زي باقي الفيديوهات.
 * تشتغل أونلاين مباشرة من سيرفر الاشتراك (ما تتحمّل للتلفزيون).
 */
object XtMedia {
    const val PREFIX = "xtmedia:"
    const val ID_PREFIX = "xt_"

    fun isXt(url: String) = url.startsWith(PREFIX)
    fun isXtId(id: String?) = id?.startsWith(ID_PREFIX) == true
    fun videoIdOf(url: String): String? = if (isXt(url)) url.removePrefix(PREFIX).substringBefore('@').takeIf { it.isNotBlank() } else null
    /** رابط الملف الحقيقي على سيرفر الاشتراك */
    fun streamUrl(url: String): String = url.removePrefix(PREFIX).substringAfter('@')

    /** اسم وصورة كل فيديو من مكتبة الاشتراك (عشان المشغّل و"تابع المشاهدة" يعرضونها) */
    val meta = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()

    /** بعض سيرفرات الاشتراك ترفض (403) مشغّلات معيّنة → نجرب هويات مشغّلات معروفة بالترتيب */
    val USER_AGENTS = listOf("VLC/3.0.20 LibVLC/3.0.20", "Lavf/58.76.100", "IPTVSmartersPlayer", "okhttp/4.12.0")

    /** رابط ملف فيلم/حلقة على سيرفر Xtream (للتحميل: يمر على اتصال بهوية VLC) */
    fun isStreamUri(u: android.net.Uri): Boolean {
        val s = u.scheme ?: return false
        if (s != "http" && s != "https") return false
        val path = u.path ?: return false
        return path.contains("/movie/") || path.contains("/series/")
    }

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /**
     * للتحميل: نعرف حجم الملف (طلب أول بايت بس) ونجهزه كملف واحد يتحفظ بالكامل.
     * الاشتراك عادة يسمح بجهاز واحد → التحميل يستخدم الاتصال، فالتحميل واحد واحد.
     */
    fun extract(url: String): VideoExtractor.Extracted {
        val real = streamUrl(url)
        val id = videoIdOf(url) ?: throw IllegalArgumentException("xt id")
        val len = http.newCall(
            okhttp3.Request.Builder().url(real).header("User-Agent", USER_AGENTS[0]).header("Range", "bytes=0-0").build()
        ).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IllegalStateException("سيرفر الاشتراك رفض (${r.code}) — غالباً الاشتراك مستخدم على جهاز ثاني")
            if (!r.isSuccessful) throw IllegalStateException("سيرفر الاشتراك رد ${r.code}")
            r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
                ?: -1L
        }
        if (len <= 0) throw IllegalStateException("ما قدرنا نعرف حجم الملف من سيرفر الاشتراك")
        val (t, th) = meta[id] ?: ("" to null)
        return VideoExtractor.Extracted(
            videoId = id, title = t, thumbnail = th, durationSec = 0,
            media = com.turkialawlqy.aman.player.PreparedMedia(
                id, listOf(com.turkialawlqy.aman.player.PreparedStream(url = real, itag = 0, type = "muxed", delivery = "PROGRESSIVE_HTTP", contentLength = len)),
            ),
        )
    }
}
