package com.turkialawlqy.aman.yt

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.turkialawlqy.aman.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * يحل "تحديات" يوتيوب (sig و n) بنفس سكربت yt-dlp الرسمي (yt-dlp/ejs) داخل WebView.
 * التشغيل داخل Web Worker عشان كود يوتيوب ما يقدر يغيّر الصفحة.
 * السكربتات في assets/ejs (رخصة Unlicense).
 */
object JsChallengeSolver {
    private const val TAG = "JsChallengeSolver"

    data class Solved(val n: Map<String, String>, val sig: Map<String, String>)

    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val inputs = ConcurrentHashMap<Int, String>()
    private val results = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()
    private val preprocessed = ConcurrentHashMap<String, String>() // playerId → كود مُجهّز
    private val initLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val solverCode: String by lazy {
        val assets = App.instance.assets
        val lib = assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        val core = assets.open("ejs/yt.solver.core.min.js").bufferedReader().use { it.readText() }
        """
        try { Object.defineProperty(self, 'location', { value: self.location, writable: true, configurable: true }); } catch (e) {}
        $lib
        Object.assign(globalThis, lib);
        $core
        self.onmessage = function (e) {
          var out;
          try { out = JSON.stringify(jsc(JSON.parse(e.data.input))); }
          catch (err) { out = JSON.stringify({ type: 'error', error: String((err && err.stack) || err) }); }
          self.postMessage({ id: e.data.id, out: out });
        };
        """.trimIndent()
    }

    private val pageHtml = """
        <!doctype html><html><head><meta charset="utf-8"></head><body><script>
        var worker = null;
        function boot() {
          try {
            var blob = new Blob([AmanJsc.solverCode()], { type: 'application/javascript' });
            worker = new Worker(URL.createObjectURL(blob));
            worker.onmessage = function (e) { AmanJsc.done(e.data.id, e.data.out); };
            worker.onerror = function (e) { AmanJsc.failed(String(e.message || e)); };
            AmanJsc.ready();
          } catch (err) { AmanJsc.failed(String(err)); }
        }
        function solve(id) { worker.postMessage({ id: id, input: AmanJsc.input(id) }); }
        boot();
        </script></body></html>
    """.trimIndent()

    @Suppress("unused")
    private object Bridge {
        @JavascriptInterface fun solverCode(): String = JsChallengeSolver.solverCode
        @JavascriptInterface fun input(id: Int): String = inputs.remove(id) ?: "{}"
        @JavascriptInterface fun done(id: Int, out: String) { results.remove(id)?.complete(out) }
        @JavascriptInterface fun ready() { JsChallengeSolver.ready.complete(Unit) }
        @JavascriptInterface fun failed(msg: String) {
            Log.e(TAG, "worker failed: $msg")
            val err = buildJsonObject { put("type", "error"); put("error", msg) }.toString()
            results.values.forEach { it.complete(err) }
            results.clear()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureStarted() {
        initLock.withLock {
            if (webView != null && ready.isCompleted) return
            withContext(Dispatchers.Main) {
                webView?.destroy()
                ready = CompletableDeferred()
                webView = WebView(App.instance).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(Bridge, "AmanJsc")
                    loadDataWithBaseURL("https://aman.local/", pageHtml, "text/html", "utf-8", null)
                }
            }
            withTimeout(20_000) { ready.await() }
        }
    }

    /**
     * @param playerId معرّف مشغّل يوتيوب (لتخزين النسخة المجهّزة)
     * @param playerJs كود base.js
     */
    suspend fun solve(playerId: String, playerJs: String, n: List<String>, sig: List<String>): Solved {
        if (n.isEmpty() && sig.isEmpty()) return Solved(emptyMap(), emptyMap())
        ensureStarted()

        val types = buildList { if (n.isNotEmpty()) add("n"); if (sig.isNotEmpty()) add("sig") }
        val requests = buildJsonArray {
            if (n.isNotEmpty()) add(buildJsonObject {
                put("type", "n"); put("challenges", buildJsonArray { n.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
            if (sig.isNotEmpty()) add(buildJsonObject {
                put("type", "sig"); put("challenges", buildJsonArray { sig.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }
        val pre = preprocessed[playerId]
        val input = if (pre != null) buildJsonObject {
            put("type", "preprocessed"); put("preprocessed_player", pre); put("requests", requests)
        } else buildJsonObject {
            put("type", "player"); put("player", playerJs); put("requests", requests); put("output_preprocessed", true)
        }

        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        results[id] = deferred
        inputs[id] = input.toString()
        withContext(Dispatchers.Main) { webView?.evaluateJavascript("solve($id)", null) }
        val raw = try {
            withTimeout(60_000) { deferred.await() }
        } finally {
            results.remove(id); inputs.remove(id)
        }

        val out = json.parseToJsonElement(raw).jsonObject
        if (out["type"]?.jsonPrimitive?.contentOrNull == "error") {
            throw IllegalStateException("JS solver: ${out["error"]?.jsonPrimitive?.contentOrNull}")
        }
        out["preprocessed_player"]?.jsonPrimitive?.contentOrNull?.let { preprocessed[playerId] = it }

        val responses = out["responses"]?.jsonArray ?: throw IllegalStateException("JS solver: no responses")
        var nMap = emptyMap<String, String>()
        var sigMap = emptyMap<String, String>()
        types.forEachIndexed { i, type ->
            val r = responses.getOrNull(i)?.jsonObject ?: return@forEachIndexed
            if (r["type"]?.jsonPrimitive?.contentOrNull != "result") {
                Log.w(TAG, "$type failed: ${r["error"]}")
                return@forEachIndexed
            }
            val data = (r["data"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            if (type == "n") nMap = data else sigMap = data
        }
        return Solved(nMap, sigMap)
    }
}
