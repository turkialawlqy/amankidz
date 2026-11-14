package com.turkialawlqy.aman.player

import android.util.Log
import com.turkialawlqy.aman.data.CastMember
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.IptvSource
import com.turkialawlqy.aman.data.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.jan.supabase.auth.auth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "مكتبة الاشتراك": التلفزيون يجيب القنوات والأفلام والمسلسلات من حساب Xtream مباشرة
 * (ما تمر على Supabase)، ويخفي اللي أخفاه الأب + تصنيفات الكبار دايماً.
 */
object XtreamLibrary {
    private const val TAG = "XtreamLibrary"
    private const val TTL_MS = 60 * 60 * 1000L

    /** تصنيفات الكبار — مخفية دايماً (حتى لو ما أخفاها الأب) */
    private val ADULT = Regex("(xxx|adult|\\+ ?18|18 ?\\+|porn|sexy|erotic|للكبار|كبار فقط|\\bvod 18)", RegexOption.IGNORE_CASE)
    fun isAdult(name: String) = ADULT.containsMatchIn(name)

    data class Cat(val sourceId: String, val type: String, val id: String, val name: String)
    data class Entry(
        val sourceId: String, val type: String, val id: String, val name: String,
        val icon: String?, val catId: String, val ext: String?, val epgId: String? = null,
        val year: Int? = null, val rating: String? = null, val plot: String? = null,
        /** رابط القناة مباشرة (قوائم M3U العادية — مو Xtream) */
        val url: String? = null, val added: Long = 0, val backdrop: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    }
    private data class Cached(val at: Long, val data: JsonElement)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** كم تعيش كل نوع بيانات قبل ما نطلبها من السيرفر مرة ثانية */
    private fun ttlFor(action: String): Long = when (action) {
        "get_live_categories", "get_vod_categories", "get_series_categories" -> 12 * HOUR
        "get_live_streams", "get_vod_streams", "get_series", "m3u" -> 6 * HOUR
        "get_series_info" -> 3 * HOUR
        "get_vod_info" -> 24 * HOUR
        "get_short_epg" -> 30 * 60_000L
        "get_simple_data_table" -> HOUR
        else -> HOUR
    }
    private const val HOUR = 60 * 60 * 1000L
    /** تصنيف "الكل" (كل الأفلام / كل المسلسلات) */
    const val ALL_CAT = "__all__"

    /**
     * طلب من سيرفر الاشتراك — من التلفزيون مباشرة بس (ما يمر على Supabase أبداً).
     * الردود تنحفظ في قاعدة البيانات المحلية: لو لسا جديدة نرجعها بدون طلب،
     * ولو السيرفر ما رد نرجع آخر نسخة محفوظة مهما كان عمرها.
     */
    private suspend fun api(src: IptvSource, action: String, extra: Map<String, String> = emptyMap()): JsonElement? =
        withContext(Dispatchers.IO) {
            val (base, user, pass) = src.xtream ?: return@withContext null
            val key = "${src.id}|$action|$extra"
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { now - it.at < ttlFor(action) }?.let { return@withContext it.data }
            val stored = runCatching { com.turkialawlqy.aman.offline.LocalDb.cached(key) }.getOrNull()
            if (stored != null && now - stored.second < ttlFor(action)) {
                runCatching { json.parseToJsonElement(stored.first) }.getOrNull()?.let {
                    cache[key] = Cached(stored.second, it); return@withContext it
                }
            }
            val url = ("$base/player_api.php").toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("username", user)?.addQueryParameter("password", pass)
                ?.addQueryParameter("action", action)
                ?.apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }?.build() ?: return@withContext null
            val got = try {
                http.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.20").build()).execute().use { r ->
                    val body = r.body.string()
                    if (!r.isSuccessful) { lastError = "سيرفر الاشتراك رد ${r.code}"; null }
                    else runCatching { json.parseToJsonElement(body) }.getOrElse {
                        // صفحة حجب (HTML) بدل البيانات → غالباً مزوّد الإنترنت يحجب السيرفر
                        lastError = "الرد مو بيانات اشتراك (يمكن الشبكة تحجب السيرفر): ${body.take(80)}"; null
                    }?.also { runCatching { com.turkialawlqy.aman.offline.LocalDb.putCache(key, body) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
                lastError = "ما قدر يتصل بسيرفر الاشتراك: ${e.javaClass.simpleName}"
                null
            }
            if (got != null) { cache[key] = Cached(now, got); lastError = null; return@withContext got }
            // السيرفر ما رد: آخر نسخة محفوظة (حتى لو قديمة)
            cache[key]?.data ?: stored?.let { runCatching { json.parseToJsonElement(it.first) }.getOrNull() }
        }

    /** يمسح المحفوظ لمصدر (زر "تحديث") عشان يجيب من جديد */
    fun forget(src: IptvSource) {
        cache.keys.removeAll { it.startsWith("${src.id}|") }
        entriesCache.keys.removeAll { it.startsWith("${src.id}|") }
        com.turkialawlqy.aman.offline.LocalDb.clearSource(src.id)
    }

    /** آخر سبب فشل (يطلع في الصفحة بدل "ما فيه شي") */
    @Volatile var lastError: String? = null

    /** كم جهاز متصل الحين على الاشتراك من كم (null لو ما قدر يعرف) */
    suspend fun connections(src: IptvSource): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val (base, user, pass) = src.xtream ?: return@withContext null
        val url = ("$base/player_api.php").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("username", user)?.addQueryParameter("password", pass)?.build() ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.20").build()).execute().use { r ->
                val ui = json.parseToJsonElement(r.body.string()).jsonObject["user_info"]?.jsonObject ?: return@use null
                val a = (ui["active_cons"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@use null
                val m = (ui["max_connections"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@use null
                a to m
            }
        }.getOrNull()
    }

    private fun JsonElement?.arr(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun year(v: String?): Int? = v?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    private fun visibleCat(src: IptvSource, type: String, id: String, name: String) =
        "$type:$id" !in src.hiddenCats && !isAdult(name)

    /** تصنيفات نوع (live / vod / series) من كل الاشتراكات */
    // ------------------------------------------------------------------ قوائم M3U العادية (قنوات بس)
    private data class M3uCh(val name: String, val logo: String?, val group: String, val url: String, val epg: String?)

    /** نجيب القائمة من التلفزيون ونحفظها محلياً (6 ساعات)، ونفصّلها */
    private suspend fun m3u(src: IptvSource): List<M3uCh> = withContext(Dispatchers.IO) {
        val key = "${src.id}|m3u|"
        val now = System.currentTimeMillis()
        val stored = runCatching { com.turkialawlqy.aman.offline.LocalDb.cached(key) }.getOrNull()
        val text = if (stored != null && now - stored.second < ttlFor("m3u")) stored.first else try {
            http.newCall(Request.Builder().url(src.url).header("User-Agent", "VLC/3.0.20").build()).execute().use { r ->
                if (!r.isSuccessful) { lastError = "سيرفر القائمة رد ${r.code}"; null }
                else r.body.string().takeIf { "#EXTINF" in it }?.also { runCatching { com.turkialawlqy.aman.offline.LocalDb.putCache(key, it) } }
            }
        } catch (e: Exception) { lastError = "ما قدر يجيب القائمة: ${e.javaClass.simpleName}"; null } ?: stored?.first
        if (text == null) return@withContext emptyList()
        val out = mutableListOf<M3uCh>()
        var info: String? = null
        fun attr(line: String, a: String) = Regex("$a=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) info = line
            else if (line.isNotEmpty() && !line.startsWith("#") && info != null) {
                val inf = info
                info = null
                // الأفلام والحلقات في قوائم m3u_plus مكانها مكتبة Xtream، مو القنوات
                if (Regex("/(movie|series)/", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
                out += M3uCh(inf.substringAfterLast(',').trim().ifBlank { "قناة" }, attr(inf, "tvg-logo"),
                    attr(inf, "group-title") ?: "القنوات", line, attr(inf, "tvg-id"))
                if (out.size >= 30000) break
            }
        }
        out
    }

    suspend fun categories(sources: List<IptvSource>, type: String): List<Cat> = sources.flatMap { src ->
        if (src.xtream == null) {
            return@flatMap if (type != "live") emptyList()
            else m3u(src).map { it.group }.distinct().filter { visibleCat(src, type, it, it) }.map { Cat(src.id, type, it, it) }
        }
        val action = when (type) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        api(src, action).arr().mapNotNull { c ->
            val id = c.s("category_id") ?: return@mapNotNull null
            val name = c.s("category_name") ?: ""
            if (visibleCat(src, type, id, name)) Cat(src.id, type, id, name) else null
        }
    }

    /**
     * بحث بالاسم في كل تصنيفات النوع (من كل الاشتراكات) — المخفي والكبار مستبعدين.
     * القائمة الكاملة تنحفظ مؤقتاً، فالبحث الثاني فوري.
     */
    suspend fun search(sources: List<IptvSource>, type: String, query: String, limit: Int = 120): List<Entry> {
        val words = com.turkialawlqy.aman.ui.normalizeArabic(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val action = when (type) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        val out = mutableListOf<Entry>()
        for (src in sources) {
            if (src.xtream == null) {
                if (type == "live") m3u(src).withIndex().filter { (_, c) ->
                    words.all { it in com.turkialawlqy.aman.ui.normalizeArabic(c.name) } && !isAdult(c.name)
                }.take(limit - out.size).forEach { (i, c) -> out += Entry(src.id, "live", "m$i", c.name, c.logo, c.group, null, c.epg, url = c.url) }
                continue
            }
            val okCats = categories(listOf(src), type).map { it.id }.toSet()
            for (o in api(src, action).arr()) {
                val name = o.s("name") ?: continue
                val n = com.turkialawlqy.aman.ui.normalizeArabic(name)
                if (!words.all { it in n }) continue
                val cat = o.s("category_id") ?: ""
                if (cat !in okCats || isAdult(name)) continue
                val id = (if (type == "series") o.s("series_id") else o.s("stream_id")) ?: continue
                if ("$type:$id" in src.hiddenItems) continue
                out += Entry(
                    src.id, type, id, name.trim(), icon = o.s("stream_icon") ?: o.s("cover"), catId = cat,
                    ext = o.s("container_extension"), epgId = o.s("epg_channel_id"),
                    year = year(o.s("year") ?: o.s("releaseDate") ?: o.s("releasedate")), rating = o.s("rating"), plot = o.s("plot"),
                )
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** برنامج من دليل القناة (من الاشتراك) — archive = متاح للرجوع له */
    data class Program(val start: Long, val end: Long, val title: String, val desc: String?, val archive: Boolean, val serverStart: String)

    /** دليل قناة كامل (الأيام اللي فاتت لو فيه أرشيف + الجاي) — من get_simple_data_table */
    suspend fun epgTable(src: IptvSource, streamId: String): List<Program> {
        val r = api(src, "get_simple_data_table", mapOf("stream_id" to streamId)) as? JsonObject ?: return emptyList()
        fun b64(v: String?) = v?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() ?: it }
        return (r["epg_listings"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
            val st = o.s("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val en = o.s("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            Program(st * 1000, en * 1000, b64(o.s("title"))?.trim().orEmpty().ifBlank { "برنامج" }, b64(o.s("description"))?.trim(),
                o.s("has_archive") == "1", o.s("start") ?: "")
        }?.sortedBy { it.start }.orEmpty()
    }

    /** عدد أيام الأرشيف للقناة (0 = ما فيه رجوع) */
    suspend fun archiveDays(src: IptvSource, streamId: String): Int =
        api(src, "get_live_streams").arr().firstOrNull { it.s("stream_id") == streamId }
            ?.let { o -> if (o.s("tv_archive") == "1") o.s("tv_archive_duration")?.toIntOrNull() ?: 1 else 0 } ?: 0

    /** رابط مشاهدة برنامج فات (timeshift): السيرفر يحسب الوقت بتوقيته هو */
    fun timeshiftUrl(src: IptvSource, streamId: String, p: Program): String? {
        val (b, u, pw) = src.xtream ?: return null
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(p.serverStart) ?: return null
        val (d, h, mi) = m.destructured
        val minutes = ((p.end - p.start) / 60_000).coerceAtLeast(1)
        return "$b/timeshift/$u/$pw/$minutes/$d:$h-$mi/$streamId.ts"
    }

    /**
     * طلب مباشر من التلفزيون (بدون كاش وبدون Supabase) — للوحة لما سيرفر الاشتراك يرفض سيرفرات Supabase.
     * action فاضي = بيانات الحساب. يرجع نص JSON كما هو.
     */
    suspend fun rawDirect(src: IptvSource, action: String, extra: Map<String, String>): String = withContext(Dispatchers.IO) {
        val (base, user, pass) = src.xtream ?: throw IllegalStateException("مو حساب Xtream")
        val url = ("$base/player_api.php").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("username", user)?.addQueryParameter("password", pass)
            ?.apply { if (action.isNotBlank()) addQueryParameter("action", action) }
            ?.apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
            ?: throw IllegalStateException("رابط غلط")
        http.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.20").build()).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IllegalStateException("سيرفر الاشتراك رد ${r.code}")
            json.parseToJsonElement(body) // نتأكد إنه JSON (مو صفحة حجب)
            body
        }
    }

    /**
     * الأحدث (للرئيسية): من أول تصنيفات الاشتراك (أغلب المزوّدين يحطون "الجديد" أول)، مرتبة بتاريخ الإضافة.
     * ما نجيب القائمة الكاملة (ممكن تكون عشرات الآلاف وتثقل التلفزيون).
     */
    suspend fun latest(sources: List<IptvSource>, type: String, limit: Int = 20, cats: Int = 4): List<Entry> =
        sources.filter { it.xtream != null }.flatMap { src ->
            categories(listOf(src), type).take(cats).flatMap { c -> runCatching { entries(src, c) }.getOrDefault(emptyList()) }
        }.distinctBy { "${it.sourceId}:${it.id}" }.sortedByDescending { it.added }.take(limit)

    /** البرنامج الحين واللي بعده (خفيف — get_short_epg) */
    suspend fun shortEpg(src: IptvSource, streamId: String): List<Program> {
        val r = api(src, "get_short_epg", mapOf("stream_id" to streamId, "limit" to "2")) as? JsonObject ?: return emptyList()
        fun b64(v: String?) = v?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() ?: it }
        return (r["epg_listings"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
            val st = o.s("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val en = o.s("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            Program(st * 1000, en * 1000, b64(o.s("title"))?.trim().orEmpty().ifBlank { "برنامج" }, null, false, o.s("start") ?: "")
        }.orEmpty()
    }

    /** عناصر تصنيف */
    /** نتيجة كل تصنيف بالذاكرة (التحويل للقائمة نفسه ياخذ وقت مع آلاف العناصر) */
    private val entriesCache = ConcurrentHashMap<String, Pair<Long, List<Entry>>>()

    suspend fun entries(src: IptvSource, cat: Cat): List<Entry> {
        val ck = "${src.id}|${cat.type}|${cat.id}"
        entriesCache[ck]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        return withContext(Dispatchers.Default) { entriesRaw(src, cat) }.also { if (it.isNotEmpty()) entriesCache[ck] = System.currentTimeMillis() to it }
    }

    /** تجهيز مسبق بالخلفية: التصنيفات + أول تصنيف + الأحدث — عشان الصفحات تفتح فوراً */
    @Volatile private var prefetchedAt = 0L
    suspend fun prefetch(sources: List<IptvSource>) {
        val now = System.currentTimeMillis()
        if (now - prefetchedAt < HOUR || sources.isEmpty()) return
        prefetchedAt = now
        for (type in listOf("vod", "series", "live")) {
            runCatching {
                val cats = categories(sources, type)
                cats.take(2).forEach { c -> sources.firstOrNull { it.id == c.sourceId }?.let { entries(it, c) } }
            }
        }
        runCatching { latest(sources, "vod"); latest(sources, "series") }
    }

    /** يجهّز حلقات المسلسل لما يتحدد بالريموت (الفتح بعدها فوري) */
    suspend fun warmSeries(src: IptvSource, seriesId: String) {
        runCatching { api(src, "get_series_info", mapOf("series_id" to seriesId)) }
    }

    private suspend fun entriesRaw(src: IptvSource, cat: Cat): List<Entry> {
        if (src.xtream == null) return m3u(src).withIndex().filter { it.value.group == cat.id && !isAdult(it.value.name) }.map { (i, c) ->
            Entry(src.id, "live", "m$i", c.name, c.logo, cat.id, null, c.epg, url = c.url)
        }
        val action = when (cat.type) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        // "الكل": القائمة الكاملة (تنحفظ محلياً) — بدون التصنيفات المخفية وتصنيفات الكبار
        val all = cat.id == ALL_CAT
        val okCats = if (all) categories(listOf(src), cat.type).map { it.id }.toSet() else emptySet()
        val raw = if (all) api(src, action) else api(src, action, mapOf("category_id" to cat.id))
        return raw.arr().mapNotNull { o ->
            val id = (if (cat.type == "series") o.s("series_id") else o.s("stream_id")) ?: return@mapNotNull null
            if ("${cat.type}:$id" in src.hiddenItems) return@mapNotNull null
            if (all && (o.s("category_id") ?: "") !in okCats) return@mapNotNull null
            val name = o.s("name") ?: return@mapNotNull null
            if (isAdult(name)) return@mapNotNull null
            Entry(
                src.id, cat.type, id, name.trim(),
                icon = o.s("stream_icon") ?: o.s("cover"), catId = if (all) o.s("category_id") ?: "" else cat.id,
                ext = o.s("container_extension"), epgId = o.s("epg_channel_id"),
                year = year(o.s("year") ?: o.s("releaseDate") ?: o.s("releasedate")),
                rating = o.s("rating"), plot = o.s("plot"),
                added = (o.s("added") ?: o.s("last_modified"))?.toLongOrNull() ?: 0L,
                backdrop = img(o["backdrop_path"]),
            )
        }
    }

    // ------------------------------------------------------------------ تحويل لعناصر التطبيق

    private fun base(src: IptvSource) = src.xtream!!
    private fun short(src: IptvSource) = src.id.replace("-", "").take(6)

    /** قناة مباشرة */
    fun channel(src: IptvSource, e: Entry, catName: String): LiveChannel {
        val stream = e.url ?: base(src).let { (b, u, p) -> "$b/live/$u/$p/${e.id}.ts" }
        return LiveChannel(
            id = "xl:${src.id}:${e.id}", deviceId = "", sourceId = src.id, name = e.name, logo = e.icon,
            streamUrl = stream, groupName = catName, epgId = e.epgId,
        )
    }

    private fun castOf(s: String?) = s.orEmpty().split(',', '،').map { it.trim() }.filter { it.isNotBlank() }.take(15)
        .map { CastMember(name = it.take(60)) }
    private fun genres(s: String?) = s.orEmpty().split(',', '،', '/').map { it.trim() }.filter { it.isNotBlank() }.take(4)
    private fun img(v: JsonElement?): String? = when (v) {
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** فيلم كعنصر تطبيق (مع وصفه وممثليه من الاشتراك) */
    suspend fun movie(src: IptvSource, e: Entry): ContentItem {
        val (b, u, p) = base(src)
        val info = (api(src, "get_vod_info", mapOf("vod_id" to e.id)) as? JsonObject)?.get("info") as? JsonObject
        val key = "xt_m${short(src)}_${e.id}"
        val backdrop = img(info?.get("backdrop_path"))
        com.turkialawlqy.aman.yt.XtMedia.meta[key] = e.name to (backdrop ?: e.icon)
        return ContentItem(
            id = "xv:${src.id}:${e.id}", deviceId = "", kind = "video", youtubeId = key,
            url = "xtmedia:$key@$b/movie/$u/$p/${e.id}.${e.ext ?: "mp4"}",
            title = e.name, thumbnailUrl = backdrop ?: e.icon,
            description = info?.s("plot") ?: info?.s("description"),
            year = year(info?.s("releasedate") ?: info?.s("release_date")) ?: e.year,
            genres = genres(info?.s("genre")), backdropUrl = backdrop, posterUrl = info?.s("movie_image") ?: e.icon,
            castList = castOf(info?.s("cast") ?: info?.s("actors")),
        )
    }

    /** مسلسل + حلقاته بمواسمها */
    /** "xs:<المصدر>:<المسلسل>" → المسلسل وحلقاته (لـ "أكمل المشاهدة": يكمل بالحلقات اللي بعدها) */
    suspend fun seriesByMixId(sources: List<IptvSource>, mixId: String): Pair<ContentItem, List<ContentItem>>? {
        if (!mixId.startsWith("xs:")) return null
        val rest = mixId.removePrefix("xs:")
        val src = sources.firstOrNull { rest.startsWith(it.id + ":") } ?: return null
        val sid = rest.removePrefix(src.id + ":")
        return runCatching { series(src, Entry(src.id, "series", sid, "", null, "", null)) }.getOrNull()
            ?.takeIf { it.second.isNotEmpty() }
    }

    suspend fun series(src: IptvSource, e: Entry): Pair<ContentItem, List<ContentItem>> {
        val (b, u, p) = base(src)
        val r = api(src, "get_series_info", mapOf("series_id" to e.id)) as? JsonObject
        val info = r?.get("info") as? JsonObject
        val backdrop = img(info?.get("backdrop_path"))
        val mixId = "xs:${src.id}:${e.id}"
        val eps = mutableListOf<ContentItem>()
        // بعض السيرفرات ترجع المواسم كـ {"1":[…]} وبعضها كمصفوفة [[…],[…]]
        val epsEl = r?.get("episodes")
        val seasons: List<Pair<String, JsonElement>> = when (epsEl) {
            is JsonObject -> epsEl.entries.map { it.key to it.value }
            is JsonArray -> if (epsEl.firstOrNull() is JsonArray) epsEl.mapIndexed { i, v -> "${i + 1}" to v } else listOf("" to epsEl)
            else -> emptyList()
        }
        // الموسم: من مفتاح القائمة {"2":[…]} أول (أدق)، وبعدين من الحلقة، وبعدين من الاسم (S02E05 / الموسم 2)
        fun fromTitle(t: String?): Int? {
            val x = t.orEmpty().replace(Regex("[٠-٩]")) { (it.value[0] - '٠').toString() }
            return (Regex("\\bS(\\d{1,2})\\s*[ .-]?E\\d{1,3}", RegexOption.IGNORE_CASE).find(x)
                ?: Regex("(?:الموسم|الجزء|موسم|season)\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(x))?.groupValues?.get(1)?.toIntOrNull()
        }
        val keyed = seasons.size > 1
        seasons.forEach { (season, list) ->
            list.arr().forEach { ep ->
                val id = ep.s("id") ?: return@forEach
                val key = "xt_e${short(src)}_$id"
                val sn = (if (keyed) season.toIntOrNull() else null) ?: ep.s("season")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: season.toIntOrNull()?.takeIf { it > 0 } ?: fromTitle(ep.s("title")) ?: 1
                val num = ep.s("episode_num")?.toIntOrNull() ?: 0
                val epInfo = ep["info"] as? JsonObject
                com.turkialawlqy.aman.yt.XtMedia.meta[key] =
                    "${info?.s("name") ?: e.name} · ${ep.s("title")?.trim() ?: "الحلقة $num"}" to (epInfo?.s("movie_image") ?: backdrop ?: e.icon)
                eps += ContentItem(
                    id = "xe:${src.id}:$id", deviceId = "", kind = "video", youtubeId = key,
                    url = "xtmedia:$key@$b/series/$u/$p/$id.${ep.s("container_extension") ?: "mp4"}",
                    title = ep.s("title")?.trim() ?: "الحلقة $num", thumbnailUrl = epInfo?.s("movie_image"),
                    parentId = mixId, position = num, season = sn.takeIf { it > 1 },
                    description = epInfo?.s("plot"),
                )
            }
        }
        val mix = ContentItem(
            id = mixId, deviceId = "", kind = "mix", youtubeId = "xts_${short(src)}_${e.id}", url = "",
            title = info?.s("name") ?: e.name, thumbnailUrl = backdrop ?: e.icon,
            description = info?.s("plot") ?: e.plot, year = year(info?.s("releaseDate") ?: info?.s("release_date")) ?: e.year,
            genres = genres(info?.s("genre")), backdropUrl = backdrop, posterUrl = info?.s("cover") ?: e.icon,
            castList = castOf(info?.s("cast")),
        )
        // كل الحلقات طلعت موسم واحد بس أسماءها فيها مواسم (S02E01 / الموسم 2) → نقسّمها من الأسماء
        val fixed = if (eps.map { it.seasonNo }.distinct().size <= 1) {
            val fromNames = eps.map { fromTitle(it.title) }
            if (fromNames.filterNotNull().distinct().size > 1) eps.mapIndexed { i, ep -> ep.copy(season = fromNames[i]?.takeIf { it > 1 }) } else eps
        } else eps
        return mix to fixed.sortedWith(com.turkialawlqy.aman.data.EpisodeOrder)
    }
}
