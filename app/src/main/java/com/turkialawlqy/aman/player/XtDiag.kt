package com.turkialawlqy.aman.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * فحص سريع لما يرفض سيرفر الاشتراك (403): نجرب نفس الرابط من التلفزيون بطرق مختلفة
 * ونكتب النتيجة على الشاشة (بدون اسم المستخدم وكلمة المرور) عشان نعرف السبب بالضبط.
 */
object XtDiag {
    private val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Parts(val base: String, val user: String, val pass: String)

    /** http://host:port/(live|movie|series)/user/pass/id.ext */
    private fun parts(url: String): Parts? {
        val u = url.toHttpUrlOrNull() ?: return null
        val seg = u.pathSegments
        val i = seg.indexOfFirst { it == "live" || it == "movie" || it == "series" }
        return if (i >= 0 && seg.size > i + 2) {
            val prefix = seg.take(i).joinToString("/").let { if (it.isEmpty()) "" else "/$it" }
            Parts("${u.scheme}://${u.host}:${u.port}$prefix", seg[i + 1], seg[i + 2])
        } else if (seg.size >= 3) { // الصيغة القصيرة للقنوات: /user/pass/id
            Parts("${u.scheme}://${u.host}:${u.port}", seg[seg.size - 3], seg[seg.size - 2])
        } else null
    }

    private fun mask(s: String, p: Parts?) =
        if (p == null) s else s.replace(p.user, "•••").replace(p.pass, "•••")

    suspend fun run(url: String): String = withContext(Dispatchers.IO) {
        val p = parts(url)
        val out = mutableListOf<String>()
        // 1) حالة الحساب من نفس التلفزيون
        if (p != null) runCatching {
            val api = "${p.base}/player_api.php".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("username", p.user).addQueryParameter("password", p.pass).build()
            http.newCall(Request.Builder().url(api).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build()).execute().use { r ->
                val body = r.body.string()
                val ui = runCatching { json.parseToJsonElement(body).jsonObject["user_info"]?.jsonObject }.getOrNull()
                if (ui == null) out += "الحساب: الرد ${r.code} مو بيانات اشتراك"
                else {
                    fun f(k: String) = (ui[k] as? JsonPrimitive)?.contentOrNull ?: "?"
                    out += "الحساب: ${f("status")} · الأجهزة المتصلة: ${f("active_cons")} من ${f("max_connections")}"
                }
            }
        }.onFailure { out += "الحساب: ما قدر يتصل (${it.javaClass.simpleName})" }

        // 2) نفس الرابط بهويات مختلفة (بعد مهلة عشان السيرفر يفك المحاولة الفاشلة)
        delay(4000)
        val tries = listOf("VLC" to "VLC/3.0.20 LibVLC/3.0.20", "Exo" to "ExoPlayerLib/2.19.1", "okhttp" to null)
        for ((name, ua) in tries) {
            out += "$name: " + runCatching { trace(url, ua, p) }.getOrElse { "خطأ ${it.javaClass.simpleName}" }
            delay(2500)
        }
        out.joinToString("\n")
    }

    /** يتبع التحويلات بنفسه ويكتب كل خطوة: 302 → host → 403 (Server) «نص» */
    private fun trace(start: String, ua: String?, p: Parts?): String {
        var url = start
        val steps = mutableListOf<String>()
        repeat(4) {
            val b = Request.Builder().url(url).header("Range", "bytes=0-1023")
            if (ua != null) b.header("User-Agent", ua) else b.removeHeader("User-Agent")
            http.newCall(b.build()).execute().use { r ->
                val host = r.request.url.host
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: return steps.plus("${r.code} بدون وجهة").joinToString(" → ")
                    url = r.request.url.resolve(loc)?.toString() ?: return steps.joinToString(" → ")
                    steps += "${r.code} من $host"
                    return@repeat
                }
                val server = r.header("Server")?.take(20)
                val text = if (r.isSuccessful) "" else r.body.source().let { s ->
                    s.request(200); s.buffer.clone().readUtf8().replace(Regex("<[^>]*>|\\s+"), " ").trim().take(70)
                }
                steps += "${r.code} من $host" + (server?.let { " ($it)" } ?: "") + (if (text.isNotBlank()) " «${mask(text, p)}»" else "")
                return steps.joinToString(" → ")
            }
        }
        return steps.joinToString(" → ")
    }
}
