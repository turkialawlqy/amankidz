package com.turkialawlqy.aman.player

import android.util.Log
import android.util.Xml
import com.turkialawlqy.aman.data.TvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * دليل البرامج (XMLTV) للقنوات المباشرة: "يعرض الحين" و"بعده".
 * يقرا الملف قطعة قطعة (ممكن يكون كبير) ويحفظ بس برامج القنوات المختارة للساعات الجاية.
 * يتحدث كل 6 ساعات.
 */
object Epg {
    private const val TAG = "Epg"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val WINDOW_MS = 18 * 60 * 60 * 1000L

    data class Program(val start: Long, val end: Long, val title: String)

    private val _guide = MutableStateFlow<Map<String, List<Program>>>(emptyMap())
    val guide: StateFlow<Map<String, List<Program>>> = _guide.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastKey = ""
    private var lastAt = 0L
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun now(epgId: String?, at: Long = System.currentTimeMillis()): Program? =
        epgId?.let { _guide.value[it] }?.firstOrNull { at >= it.start && at < it.end }

    fun next(epgId: String?, at: Long = System.currentTimeMillis()): Program? =
        epgId?.let { _guide.value[it] }?.firstOrNull { it.start >= at }

    fun refresh(s: TvState.Ready) {
        val ids = s.channels.mapNotNull { it.epgId?.takeIf { e -> e.isNotBlank() } }.toSet()
        if (ids.isEmpty() || s.epgUrls.isEmpty()) return
        val key = s.epgUrls.joinToString() + "|" + ids.size
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < REFRESH_MS) return
        if (job?.isActive == true) return
        lastKey = key; lastAt = now
        job = scope.launch {
            val out = HashMap<String, MutableList<Program>>()
            for (url in s.epgUrls) {
                try {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "VLC/3.0.20").build()).execute().use { r ->
                        if (!r.isSuccessful) return@use
                        parse(maybeGzip(r.body.byteStream()), ids, out)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "epg failed: $url", e)
                    lastAt = 0L // نحاول مرة ثانية بعدين
                }
            }
            if (out.isNotEmpty()) _guide.value = out.mapValues { (_, v) -> v.sortedBy { it.start } }
        }
    }

    private fun maybeGzip(input: InputStream): InputStream {
        val b = BufferedInputStream(input, 64 * 1024)
        b.mark(2)
        val m1 = b.read(); val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) GZIPInputStream(b, 64 * 1024) else b
    }

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }
    private val fmtNoTz = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss", Locale.US) }

    /** "20261012183000 +0300" */
    private fun time(v: String?): Long? {
        if (v.isNullOrBlank()) return null
        val t = v.trim()
        return runCatching { fmt.get()!!.parse(t)?.time }.getOrNull()
            ?: runCatching { fmtNoTz.get()!!.parse(t.take(14))?.time }.getOrNull()
    }

    private fun parse(input: InputStream, ids: Set<String>, out: HashMap<String, MutableList<Program>>) {
        val now = System.currentTimeMillis()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var ch: String? = null
        var start = 0L
        var end = 0L
        var inTitle = false
        var title: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        val c = p.getAttributeValue(null, "channel")
                        if (c != null && c in ids) {
                            ch = c
                            start = time(p.getAttributeValue(null, "start")) ?: 0L
                            end = time(p.getAttributeValue(null, "stop")) ?: 0L
                            title = null
                        } else ch = null
                    }
                    "title" -> if (ch != null && title == null) inTitle = true
                }
                XmlPullParser.TEXT -> if (inTitle) title = p.text?.trim()
                XmlPullParser.END_TAG -> when (p.name) {
                    "title" -> inTitle = false
                    "programme" -> {
                        val c = ch
                        if (c != null && end > now && start < now + WINDOW_MS && !title.isNullOrBlank()) {
                            out.getOrPut(c) { mutableListOf() }.add(Program(start, end, title!!))
                        }
                        ch = null
                    }
                }
            }
            ev = p.next()
        }
    }
}
