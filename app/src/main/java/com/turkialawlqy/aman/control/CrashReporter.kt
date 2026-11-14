package com.turkialawlqy.aman.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.data.TvRepository
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * تقرير الأعطال للوحة (بدون adb):
 * - انهيار التطبيق: ينحفظ في ملف وينرسل أول ما يفتح التطبيق مرة ثانية.
 * - تعليق الواجهة: مراقب يشيك على الواجهة كل ثانيتين؛ لو ما ردت 5 ثواني ناخذ وين كانت عالقة بالضبط.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val FREEZE_MS = 5_000L
    private const val FREEZE_THROTTLE_MS = 10 * 60_000L

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastFreezeReport = 0L

    fun init(context: Context) {
        dir = File(context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "v${BuildConfig.VERSION_NAME} · thread ${t.name}\n" + Log.getStackTraceString(e).take(20000)
                )
            }
            previous?.uncaughtException(t, e)
        }
        startWatchdog()
        // الانهيارات اللي صارت قبل: نرسلها لما يتصل
        scope.launch {
            delay(20_000)
            flushFiles()
        }
    }

    /** يرسل الملفات المحفوظة (انهيارات سابقة) */
    private suspend fun flushFiles() {
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty().takeLast(10)
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val first = text.lineSequence().drop(1).firstOrNull().orEmpty()
            if (send("crash", first, text)) f.delete()
        }
    }

    /** خطأ عادي يستاهل يوصل للأب (مثلاً فشل تشغيل متكرر) */
    fun report(message: String, detail: String = "") {
        scope.launch { send("error", message, detail) }
    }

    /** نشيل بيانات الاشتراك من أي رابط قبل الإرسال */
    private fun mask(s: String) = s
        .replace(Regex("/(live|movie|series)/[^/\\s]+/[^/\\s]+/"), "/$1/•••/•••/")
        .replace(Regex("(username|password)=[^&\\s]+"), "$1=•••")

    private suspend fun send(kind: String, message0: String, detail0: String): Boolean = try {
        val message = mask(message0)
        val detail = mask(detail0)
        TvRepository.supabase.postgrest.rpc("device_report_error", buildJsonObject {
            put("p_kind", kind)
            put("p_message", message.take(500))
            put("p_detail", detail.take(20000))
            put("p_version", BuildConfig.VERSION_NAME)
        })
        true
    } catch (e: Exception) {
        Log.w(TAG, "report failed", e)
        false
    }

    // ------------------------------------------------------------------ مراقب التعليق
    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                val sent = SystemClock.elapsedRealtime()
                Thread.sleep(FREEZE_MS)
                if (!answered.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFreezeReport > FREEZE_THROTTLE_MS) {
                        lastFreezeReport = now
                        val stack = Looper.getMainLooper().thread.stackTrace
                        val trace = stack.take(60).joinToString("\n") { "  at $it" }
                        // أول سطر من كودنا = وين علّق
                        val ours = stack.firstOrNull { it.className.startsWith("com.turkialawlqy") }
                        val msg = "الواجهة علّقت ${(now - sent) / 1000}+ ثواني" + (ours?.let { " عند ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "")
                        Log.w(TAG, msg + "\n" + trace)
                        scope.launch { send("freeze", msg, trace) }
                    }
                    // ننتظر لين ترد قبل ما نعيد الفحص
                    while (!answered.get()) Thread.sleep(500)
                } else {
                    Thread.sleep(2_000)
                }
            }
        }, "freeze-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
