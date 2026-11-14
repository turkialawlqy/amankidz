package com.turkialawlqy.aman.yt

import android.net.Uri
import android.util.Log
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.net.NewPipeDownloader
import com.turkialawlqy.aman.player.PreparedMedia
import com.turkialawlqy.aman.player.PreparedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * طريقة بديلة لجلب روابط الفيديو لما ترفضها مكتبة NewPipe.
 *
 * السبب: NewPipe صارت تستخدم عميل visionOS فقط، ويوتيوب يرفض فيه فيديوهات "محتوى الأطفال"
 * (موثّق في yt-dlp و NewPipeExtractor commit 9ed62db). نفس حل yt-dlp:
 * نطلب الفيديو بعميل التلفزيون (TVHTML5) أو المشغّل المضمّن، ونحل تحديات sig/n بسكربت yt-dlp.
 * قيم العملاء مأخوذة من yt-dlp (yt_dlp/extractor/youtube/_base.py) — حدّثها لو توقفت.
 */
object InnertubeFallback {
    private const val TAG = "InnertubeFallback"
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    private data class Client(
        val key: String,
        val name: String,
        val version: String,
        val id: Int,
        val userAgent: String,
        val embedded: Boolean = false,
        val usesPoToken: Boolean = false,
    )

    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

    // بالترتيب: نفس ما يسويه yt-dlp مع فيديوهات "محتوى الأطفال"
    private val CLIENTS = listOf(
        Client("tv_downgraded", "TVHTML5", "5.20260707", 7, "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"),
        Client("web_embedded", "WEB_EMBEDDED_PLAYER", "2.20260708.00.00", 56, SAFARI_UA, embedded = true),
        Client("web", "WEB", "2.20260708.00.00", 1, CHROME_UA, usesPoToken = true),
        Client(
            "tv", "TVHTML5", "7.20260707.07.00", 7,
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        ),
    )

    // جلسة بكوكيز يوتيوب (مثل المتصفح) — بدونها يرد يوتيوب "يجب إعادة تحميل الصفحة"
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Cookie>()
    private val cookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            cookies.forEach { cookieStore["${it.domain}|${it.name}"] = it }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            return cookieStore.values.filter { it.expiresAt > now && it.matches(url) }
        }
    }
    private val http by lazy {
        // موافقة الكوكيز (نفس yt-dlp) عشان ما تطلع صفحة الموافقة
        val consent = okhttp3.Cookie.Builder().domain("youtube.com").name("SOCS").value("CAI").path("/").secure().build()
        cookieStore["youtube.com|SOCS"] = consent
        NewPipeDownloader.client.newBuilder().cookieJar(cookieJar).build()
    }

    private data class Session(
        val visitorData: String?,
        val playerId: String?,
        val sts: Int?,
        val embedFlags: String?,
    )

    data class Result(
        val title: String,
        val durationSec: Long,
        val thumbnail: String?,
        val media: PreparedMedia,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var playerId: String? = null
    private var playerJs: String? = null
    private var sts: Int? = null
    private var playerFetchedAt = 0L
    private var visitorData: String? = null
    private var visitorFetchedAt = 0L

    suspend fun extract(videoId: String, maxHeight: Int, cacheableOnly: Boolean): Result =
        withContext(Dispatchers.IO) {
            val session = loadSession(videoId)
            val (pid, js, playerSts) = loadPlayer(session.playerId)
            val signatureTs = session.sts ?: playerSts
            val errors = mutableListOf<String>()

            for (client in CLIENTS) {
                try {
                    var visitor = session.visitorData ?: loadVisitorData()
                    var pot: org.schabi.newpipe.extractor.services.youtube.PoTokenResult? = null
                    var flags: String? = null
                    if (client.usesPoToken) {
                        pot = com.turkialawlqy.aman.potoken.PoTokenProviderImpl.getWebClientPoToken(videoId)
                            ?: throw IllegalStateException("no poToken")
                        visitor = pot.visitorData
                    }
                    if (client.embedded) flags = session.embedFlags ?: loadEmbedFlags(videoId)

                    val response = requestPlayer(client, videoId, signatureTs, visitor, pot?.playerRequestPoToken, flags)
                    val status = response.obj("playabilityStatus")?.str("status")
                    if (status != "OK") {
                        errors += "${client.key}: $status ${response.obj("playabilityStatus")?.str("reason") ?: ""}"
                        continue
                    }
                    val result = buildResult(videoId, response, pid, js, maxHeight, cacheableOnly, pot?.streamingDataPoToken)
                    Log.i(TAG, "$videoId played via ${client.key}")
                    return@withContext result
                } catch (e: Exception) {
                    errors += "${client.key}: ${e.message}"
                    Log.w(TAG, "client ${client.key} failed for $videoId", e)
                }
            }
            throw IllegalStateException("Fallback failed: ${errors.joinToString(" | ")}")
        }

    // ------------------------------------------------------------------ الجلسة (صفحة الفيديو)

    /** نفتح صفحة الفيديو مثل المتصفح: نأخذ الكوكيز و visitorData ورقم المشغّل */
    private fun loadSession(videoId: String): Session = try {
        val html = get("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1", CHROME_UA)
        val cfg = extractYtcfg(html)
        val visitor = (cfg.obj("INNERTUBE_CONTEXT")?.obj("client")?.str("visitorData")) ?: cfg.str("VISITOR_DATA")
        val jsUrl = cfg.str("PLAYER_JS_URL")
            ?: Regex("""/s/player/[0-9a-fA-F]{8}/[^"']+?base\.js""").find(html)?.value
        val pid = jsUrl?.let { Regex("""/s/player/([0-9a-fA-F]{8})/""").find(it)?.groupValues?.get(1) }
        Session(visitor, pid, cfg.int("STS"), null)
    } catch (e: Exception) {
        Log.w(TAG, "watch page failed", e)
        Session(null, null, null, null)
    }

    /** المشغّل المضمّن يحتاج encryptedHostFlags من صفحة embed */
    private fun loadEmbedFlags(videoId: String): String? = try {
        val req = Request.Builder()
            .url("https://www.youtube.com/embed/$videoId?html5=1")
            .header("User-Agent", SAFARI_UA)
            .header("Referer", "https://www.reddit.com/")
            .build()
        val html = http.newCall(req).execute().use { it.body.string() }
        extractYtcfg(html).obj("WEB_PLAYER_CONTEXT_CONFIGS")
            ?.obj("WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER")
            ?.str("encryptedHostFlags")
    } catch (e: Exception) {
        Log.w(TAG, "embed page failed", e)
        null
    }

    /** يجمع كل ytcfg.set({...}) في الصفحة */
    private fun extractYtcfg(html: String): JsonObject {
        val merged = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        var idx = html.indexOf("ytcfg.set(")
        while (idx >= 0) {
            val start = html.indexOf('{', idx)
            if (start < 0) break
            val end = matchBrace(html, start)
            if (end > start) {
                try {
                    merged.putAll(json.parseToJsonElement(html.substring(start, end + 1)).jsonObject)
                } catch (_: Exception) {
                }
            }
            idx = html.indexOf("ytcfg.set(", maxOf(end, start + 1))
        }
        return JsonObject(merged)
    }

    /** يرجع موضع القوس المغلق المقابل (مع تجاهل ما داخل النصوص) */
    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    // ------------------------------------------------------------------ المشغّل (base.js)

    private suspend fun loadPlayer(preferredId: String?): Triple<String, String, Int?> = lock.withLock {
        val fresh = System.currentTimeMillis() - playerFetchedAt < 6 * 60 * 60 * 1000L
        if (playerId != null && playerJs != null && (preferredId == null || preferredId == playerId) && fresh) {
            return@withLock Triple(playerId!!, playerJs!!, sts)
        }

        val id = preferredId ?: run {
            val iframe = get("https://www.youtube.com/iframe_api", NewPipeDownloader.USER_AGENT)
            Regex("""player\\?/([0-9a-fA-F]{8})\\?/""").find(iframe)?.groupValues?.get(1)
                ?: throw IllegalStateException("player id not found")
        }

        val cacheFile = File(App.instance.cacheDir, "yt-player-$id.js")
        val js = if (cacheFile.exists() && cacheFile.length() > 100_000) {
            cacheFile.readText()
        } else {
            get("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js", NewPipeDownloader.USER_AGENT)
                .also {
                    App.instance.cacheDir.listFiles { f -> f.name.startsWith("yt-player-") }?.forEach { it.delete() }
                    cacheFile.writeText(it)
                }
        }
        val signatureTs = Regex("""(?:signatureTimestamp|sts)\s*:\s*([0-9]{5})""").find(js)?.groupValues?.get(1)?.toIntOrNull()

        playerId = id; playerJs = js; sts = signatureTs
        playerFetchedAt = System.currentTimeMillis()
        Triple(id, js, signatureTs)
    }

    private fun loadVisitorData(): String? {
        if (visitorData != null && System.currentTimeMillis() - visitorFetchedAt < 60 * 60 * 1000L) return visitorData
        return try {
            val info = InnertubeClientRequestInfo.ofWebClient()
            info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                info,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            ).also {
                visitorData = it
                visitorFetchedAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "visitorData failed", e)
            null
        }
    }

    // ------------------------------------------------------------------ طلب المشغّل

    private fun requestPlayer(
        client: Client,
        videoId: String,
        signatureTs: Int?,
        visitor: String?,
        poToken: String?,
        embedFlags: String?,
    ): JsonObject {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "ar")
                    put("gl", "SA")
                    put("userAgent", client.userAgent)
                    if (visitor != null) put("visitorData", visitor)
                }
                if (client.embedded) putJsonObject("thirdParty") { put("embedUrl", "https://www.reddit.com/") }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (signatureTs != null) put("signatureTimestamp", signatureTs)
                    if (embedFlags != null) put("encryptedHostFlags", embedFlags)
                }
            }
            if (poToken != null) putJsonObject("serviceIntegrityDimensions") { put("poToken", poToken) }
        }

        val req = Request.Builder()
            .url(PLAYER_ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.id.toString())
            .header("X-YouTube-Client-Version", client.version)
            .header("Origin", "https://www.youtube.com")
            .apply {
                if (visitor != null) header("X-Goog-Visitor-Id", visitor)
                if (client.embedded) header("Referer", "https://www.reddit.com/")
            }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    // ------------------------------------------------------------------ تجهيز الروابط

    private class Fmt(
        val data: JsonObject,
        val itag: Int,
        val mime: String,
        var url: String,
        val sig: String?,
        val sp: String,
        val isAudio: Boolean,
        val muxed: Boolean,
    )

    private suspend fun buildResult(
        videoId: String,
        response: JsonObject,
        pid: String,
        js: String,
        maxHeight: Int,
        cacheableOnly: Boolean,
        streamingPot: String?,
    ): Result {
        val details = response.obj("videoDetails")
        val durationSec = details?.str("lengthSeconds")?.toLongOrNull() ?: 0L
        val title = details?.str("title") ?: videoId
        val thumb = (details?.obj("thumbnail")?.get("thumbnails") as? JsonArray)
            ?.lastOrNull()?.jsonObject?.str("url")

        val sd = response.obj("streamingData") ?: throw IllegalStateException("no streamingData")
        val formats = mutableListOf<Fmt>()
        fun collect(arr: JsonArray?, muxed: Boolean) {
            arr?.forEach { el ->
                val o = el.jsonObject
                if (o["drmFamilies"] != null) return@forEach
                if (o.str("type") == "FORMAT_STREAM_TYPE_OTF") return@forEach
                val itag = o.int("itag") ?: return@forEach
                val mime = o.str("mimeType") ?: return@forEach
                val direct = o.str("url")
                val cipher = o.str("signatureCipher") ?: o.str("cipher")
                val (url, sig, sp) = when {
                    direct != null -> Triple(direct, null, "signature")
                    cipher != null -> {
                        val p = parseQuery(cipher)
                        Triple(p["url"] ?: return@forEach, p["s"], p["sp"] ?: "signature")
                    }
                    else -> return@forEach
                }
                formats += Fmt(o, itag, mime, url, sig, sp, mime.startsWith("audio/"), muxed)
            }
        }
        collect(sd["adaptiveFormats"] as? JsonArray, muxed = false)
        collect(sd["formats"] as? JsonArray, muxed = true)
        if (formats.isEmpty()) throw IllegalStateException("no formats")

        // حل التحديات
        val nChallenges = formats.mapNotNull { Uri.parse(it.url).getQueryParameter("n") }.distinct()
        val sigChallenges = formats.mapNotNull { it.sig }.distinct()
        val solved = JsChallengeSolver.solve(pid, js, nChallenges, sigChallenges)
        val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val usable = formats.mapNotNull { f ->
            var url = f.url
            if (f.sig != null) {
                val s = solved.sig[f.sig] ?: return@mapNotNull null
                url += "&${f.sp}=" + URLEncoder.encode(s, "UTF-8")
            }
            val n = Uri.parse(url).getQueryParameter("n")
            if (n != null) {
                val solvedN = solved.n[n] ?: return@mapNotNull null
                url = url.replace(Regex("([?&])n=[^&]*")) { "${it.groupValues[1]}n=" + URLEncoder.encode(solvedN, "UTF-8") }
            }
            if (streamingPot != null) url += "&pot=" + URLEncoder.encode(streamingPot, "UTF-8")
            f.url = "$url&cpn=$cpn"
            f
        }
        if (usable.isEmpty()) throw IllegalStateException("could not solve stream URLs")

        // اختيار المسارات (نفس منطق StreamResolver)
        val video = usable
            .filter { !it.muxed && !it.isAudio && (it.data.int("height") ?: 0) in 1..maxHeight }
            .filter { it.mime.startsWith("video/mp4") || it.mime.startsWith("video/webm") }
            .maxByOrNull { f ->
                (f.data.int("height") ?: 0) * 1000 +
                    (if (f.mime.startsWith("video/mp4")) 100 else 0) +
                    (if ((f.data.int("fps") ?: 30) <= 30) 10 else 0)
            }
        val audios = usable.filter { !it.muxed && it.isAudio && it.data.bool("isDrc") != true }
        val audio = audios
            .filter { it.data.obj("audioTrack")?.bool("audioIsDefault") != false }
            .ifEmpty { audios }
            .maxByOrNull { f ->
                val br = (f.data.int("averageBitrate") ?: f.data.int("bitrate") ?: 0) / 1000
                (if (f.mime.startsWith("audio/mp4")) 1_000_000 else 0) +
                    (if (maxHeight <= 480) -br.coerceAtMost(200) else br)
            }

        val streams = if (video != null && audio != null) {
            listOf(prepared(video, "video", durationSec), prepared(audio, "audio", durationSec))
        } else {
            val muxed = usable.filter { it.muxed }.maxByOrNull { it.data.int("height") ?: 0 }
                ?: throw IllegalStateException("no playable format")
            listOf(prepared(muxed, "muxed", durationSec))
        }
        if (cacheableOnly && streams.any { !it.cacheable }) throw IllegalStateException("not downloadable")
        return Result(title, durationSec, thumb, PreparedMedia(videoId, streams))
    }

    private fun prepared(f: Fmt, type: String, durationSec: Long): PreparedStream {
        val length = f.data.str("contentLength")?.toLongOrNull()
            ?: Uri.parse(f.url).getQueryParameter("clen")?.toLongOrNull()
            ?: -1L
        val manifest = if (type != "muxed") buildDashManifest(f, length, durationSec) else null
        return PreparedStream(
            url = f.url,
            itag = f.itag,
            type = type,
            delivery = "PROGRESSIVE_HTTP",
            manifest = manifest,
            contentLength = length,
            height = f.data.int("height") ?: 0,
        )
    }

    /** نفس تجهيز NewPipe لـ ItagItem عشان نولّد DASH manifest (أفضل للتقديم والتأخير) */
    private fun buildDashManifest(f: Fmt, length: Long, durationSec: Long): String? = try {
        if (!ItagItem.isSupported(f.itag)) null else {
            val d = f.data
            val item = ItagItem.getItag(f.itag)
            val codec = if (f.mime.contains("codecs")) f.mime.split("\"").getOrElse(1) { "" } else ""
            item.setBitrate(d.int("bitrate") ?: 0)
            item.setWidth(d.int("width") ?: 0)
            item.setHeight(d.int("height") ?: 0)
            item.setInitStart(d.obj("initRange")?.str("start")?.toIntOrNull() ?: -1)
            item.setInitEnd(d.obj("initRange")?.str("end")?.toIntOrNull() ?: -1)
            item.setIndexStart(d.obj("indexRange")?.str("start")?.toIntOrNull() ?: -1)
            item.setIndexEnd(d.obj("indexRange")?.str("end")?.toIntOrNull() ?: -1)
            item.setQuality(d.str("quality"))
            item.setCodec(codec)
            item.setContentLength(length)
            item.setApproxDurationMs(d.str("approxDurationMs")?.toLongOrNull() ?: (durationSec * 1000))
            if (f.isAudio) {
                item.setSampleRate(d.str("audioSampleRate")?.toIntOrNull() ?: 44100)
                item.setAudioChannels(d.int("audioChannels") ?: 2)
            } else {
                item.setFps(d.int("fps") ?: 30)
            }
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(f.url, item, durationSec)
        }
    } catch (e: Exception) {
        Log.w(TAG, "manifest for itag ${f.itag} failed", e)
        null
    }

    // ------------------------------------------------------------------ أدوات

    private fun get(url: String, ua: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Language", "ar,en;q=0.8").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return resp.body.string()
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split("&").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    @Suppress("unused")
    private fun JsonObject.long(k: String): Long? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
}
