package com.turkialawlqy.aman.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * التحديث: يشيك على آخر إصدار رفعه المدير من لوحة التحكم ويحمّله بالخلفية،
 * وبعدها يسأل قبل التثبيت (ما يثبّت بصمت أبداً — عشان Play Protect ما يعتبره ضار).
 * ويتأكد إن ملف التحديث موقّع بنفس مفتاح التطبيق قبل ما يثبّته.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val CHECK_EVERY_MS = 30 * 60 * 1000L // + فوراً لما ينشر المدير إصدار (تحديث لحظي)

    @Serializable
    data class Release(
        @SerialName("version_code") val versionCode: Int,
        @SerialName("version_name") val versionName: String,
        val notes: String = "",
        @SerialName("apk_path") val apkPath: String,
        @SerialName("size_bytes") val sizeBytes: Long = 0,
    )

    sealed interface Status {
        data object None : Status
        data class Downloading(val release: Release, val percent: Int) : Status
        data class Ready(val release: Release, val file: File) : Status
        data class Failed(val release: Release, val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private lateinit var dir: File
    private var job: Job? = null

    private val _status = MutableStateFlow<Status>(Status.None)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // نحذف ملفات التحديث القديمة (بعد ما انثبّت التحديث)
        dir.listFiles()?.forEach { f ->
            val code = f.name.removePrefix("aman-").removeSuffix(".apk").toIntOrNull()
            if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
        }
        // كل دقيقة: لو فيه تحديث جاهز → نثبّته بأنسب وقت
        val app = context.applicationContext
        scope.launch {
            while (true) {
                delay(60 * 1000L)
                try { autoInstallIfIdle(app) } catch (e: Exception) { Log.w(TAG, "auto install", e) }
            }
        }
        scope.launch {
            // ننتظر لين يتصل التلفزيون بالخادم
            TvRepository.state.first { it is TvState.Ready && !it.offline }
            while (true) {
                check()
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** يشيك الآن (يُستدعى كمان من الإعدادات) */
    fun checkNow() {
        scope.launch { check() }
    }

    private suspend fun check() {
        if (job?.isActive == true) return
        try {
            val latest = TvRepository.supabase.from("app_releases").select {
                order("version_code", Order.DESCENDING)
                limit(1)
            }.decodeList<Release>().firstOrNull() ?: return
            if (latest.versionCode <= BuildConfig.VERSION_CODE) {
                _status.value = Status.None
                return
            }
            val file = File(dir, "aman-${latest.versionCode}.apk")
            if (file.exists() && (latest.sizeBytes <= 0 || file.length() == latest.sizeBytes)) {
                _status.value = Status.Ready(latest, file)
                appCtx?.let { autoInstallIfIdle(it) }
                return
            }
            job = scope.launch { download(latest, file) }
        } catch (e: Exception) {
            // قبل تشغيل fix_09 الجدول غير موجود
            Log.w(TAG, "check failed", e)
        }
    }

    private fun download(r: Release, file: File) {
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/releases/" +
            r.apkPath.split('/').joinToString("/") { Uri.encode(it) }
        val tmp = File(dir, "${file.name}.part")
        try {
            _status.value = Status.Downloading(r, 0)
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; _status.value = Status.Downloading(r, pct) }
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(file)) error("rename failed")
            _status.value = Status.Ready(r, file)
            appCtx?.let { autoInstallIfIdle(it) } // نثبّت بأنسب وقت
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            tmp.delete()
            _status.value = Status.Failed(r, e.message ?: "تعذر التحميل")
        }
    }

    // ------------------------------------------------------------------ التثبيت التلقائي
    /** عدد الشاشات المفتوحة (0 = التطبيق بالخلفية) — يحدّثه App */
    @Volatile
    var visibleActivities = 0

    /** آخر مرة استخدم فيها الطفل التطبيق */
    @Volatile
    var lastInteraction = System.currentTimeMillis()

    @Volatile
    private var installing = false

    /** أندرويد طلب تأكيد يدوي (أول مرة فقط) → نعرض زر "تحديث" */
    @Volatile
    var needsConfirm = false

    /** سبب فشل التثبيت (يطلع للأب في رسالة بعد ما يضغط "ثبّت") */
    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError.asStateFlow()
    fun clearInstallError() { _installError.value = null }

    /** ملف التحديث موجود؟ (عشان نعرض "احفظه في التنزيلات") */
    fun pendingFile(): File? = (_status.value as? Status.Ready)?.file
        ?: dir.listFiles()?.filter { it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }

    /**
     * ينسخ ملف التحديث لمجلد "التنزيلات" (Download) عشان تثبّته يدوياً من مدير الملفات —
     * مثلاً لو النسخة المثبتة موقّعة بمفتاح مختلف (لازم تحذفها أول).
     * يرجّع مكان الملف أو null.
     */
    fun exportToDownloads(context: Context): String? {
        val src = pendingFile() ?: return null
        val name = "AmanKids-${src.name.removePrefix("aman-").removeSuffix(".apk")}.apk"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                "Download/$name"
            } else {
                // أندرويد قديم: مجلد التطبيق في الذاكرة (يبان في مدير الملفات)
                val d = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return null
                val f = File(d, name)
                src.copyTo(f, overwrite = true)
                f.absolutePath.substringAfter("/storage/emulated/0/")
            }
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            null
        }
    }

    /** تحديث جاهز ونعرض للطفل/الأب إشعار "بيتثبت خلال ثواني" */
    private val _prompt = MutableStateFlow<Release?>(null)
    val prompt: StateFlow<Release?> = _prompt.asStateFlow()
    @Volatile
    private var snoozeUntil = 0L

    /** أندرويد ما سمح للتطبيق يثبّت تحديثاته (لازم يسمح الأب مرة وحدة) */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * نعرض سؤال التحديث بأنسب وقت بدون ما نقاطع المشاهدة (ما نثبّت بدون موافقة):
     * - التطبيق مفتوح بس مو يشغّل فيديو → "تحديث جديد" (ثبّت الآن / لاحقاً)
     * - يشغّل فيديو أو بالخلفية → ننتظر
     */
    private fun autoInstallIfIdle(context: Context) {
        val ready = _status.value as? Status.Ready
        if (ready == null || installing) { _prompt.value = null; return }
        val playing = com.turkialawlqy.aman.player.PlayerActivity.isActive
        when {
            visibleActivities == 0 -> Unit
            playing -> _prompt.value = null
            System.currentTimeMillis() < snoozeUntil -> Unit
            else -> _prompt.value = ready.release
        }
    }

    /** "لاحقاً": نأجل ساعة */
    fun snooze() {
        snoozeUntil = System.currentTimeMillis() + 60 * 60 * 1000L
        _prompt.value = null
    }

    /** من زر "ثبّت الآن" في سؤال التحديث */
    fun installFromPrompt(context: Context) {
        _prompt.value = null
        install(context)
    }

    /** زر "تثبيت" أو "تحديث" */
    fun install(context: Context) {
        _installError.value = null
        if (_status.value !is Status.Ready) {
            _installError.value = "ملف التحديث مو جاهز — انتظر يخلص التحميل وجرّب مرة ثانية"
            checkNow()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // أول مرة: لازم نسمح للتطبيق يثبّت تحديثاته
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) {
                // بعض التلفزيونات (سوني مثلاً) ما فيها هالشاشة مباشرة
                Log.w(TAG, "cannot open unknown sources settings", e)
                _installError.value = "لازم تسمح للتطبيق يثبّت تحديثاته: إعدادات التلفزيون ← التطبيقات ← وصول خاص للتطبيقات ← " +
                    "تثبيت تطبيقات غير معروفة ← أمان كيدز ← سماح. أو احفظ التحديث في التنزيلات وثبّته من مدير الملفات."
                return
            }
        }
        installSilently(context, userInitiated = true)
    }

    /** ملف التحديث لازم يكون موقّع بنفس مفتاح التطبيق المثبّت (وإلا ما نثبّته) */
    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        fun certs(info: android.content.pm.PackageInfo?): Set<String> {
            if (info == null) return emptySet()
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
            } else info.signatures
            return sigs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                   else android.content.pm.PackageManager.GET_SIGNATURES
        val mine = certs(pm.getPackageInfo(context.packageName, flag))
        val other = certs(pm.getPackageArchiveInfo(apk.absolutePath, flag))
        mine.isNotEmpty() && other.isNotEmpty() && mine.intersect(other).isNotEmpty() &&
            pm.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName == context.packageName
    } catch (e: Exception) {
        Log.w(TAG, "signature check failed", e)
        false
    }

    /**
     * التثبيت عن طريق PackageInstaller — أندرويد يعرض تأكيد التثبيت دائماً.
     */
    private fun installSilently(context: Context, userInitiated: Boolean) {
        val ready = _status.value as? Status.Ready ?: return
        if (!sameSigner(context, ready.file)) {
            // ما نحذف الملف: الأب يقدر يحفظه في التنزيلات ويثبّته يدوياً بعد حذف النسخة القديمة
            _installError.value = SIGNER_MSG
            return
        }
        installing = true
        scope.launch {
            try {
                val pi = context.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                }
                val sessionId = pi.createSession(params)
                pi.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateReceiver::class.java)
                        .putExtra(UpdateReceiver.EXTRA_USER, userInitiated)
                    var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or android.app.PendingIntent.FLAG_MUTABLE
                    val pending = android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "silent install failed", e)
                installing = false
                if (userInitiated && !legacyInstall(context)) { // آخر حل: شاشة التثبيت العادية
                    _installError.value = "تعذر فتح شاشة التثبيت: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    /** نتيجة التثبيت (من UpdateReceiver) */
    fun onInstallResult(context: Context, status: Int, confirm: Intent?, userInitiated: Boolean, message: String?) {
        installing = false
        when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // أندرويد يطلب تأكيد التثبيت — نعرضه لأن المستخدم ضغط "ثبّت"
                needsConfirm = true
                if ((userInitiated || visibleActivities > 0) && confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w(TAG, "cannot show confirm", e)
                    }
                }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> Unit // التطبيق بيتقفل ويرجع بالإصدار الجديد
            else -> {
                Log.w(TAG, "install failed: $status $message")
                // الملف يبقى جاهز (نقدر نعيد المحاولة أو نحفظه في التنزيلات) — بس نوضح السبب
                _installError.value = when (status) {
                    android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "انلغى التثبيت"
                    android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT,
                    android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> SIGNER_MSG
                    android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "المساحة في التلفزيون ما تكفي للتحديث — فضّي مساحة وجرّب"
                    android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED ->
                        "الجهاز منع التثبيت (Play Protect أو إعدادات المصادر غير المعروفة)"
                    android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "ملف التحديث تالف — بيتحمّل من جديد"
                    else -> "تعذر التثبيت" + (message?.let { ": $it" } ?: "")
                }
                if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID) {
                    (_status.value as? Status.Ready)?.file?.delete()
                    _status.value = Status.None
                    checkNow()
                }
            }
        }
    }

    private const val SIGNER_MSG =
        "النسخة المثبتة على هذا الجهاز موقّعة بمفتاح مختلف عن التحديث (غالباً نسخة تجريبية قديمة)، فأندرويد يرفض التحديث فوقها. " +
            "الحل مرة وحدة: احفظ التحديث في التنزيلات، احذف التطبيق، وثبّت الملف من مدير الملفات. " +
            "(المحتوى ما يضيع — بس بتربط الجهاز من جديد أو تستخدم «استبدال جهاز» من اللوحة)"

    private fun legacyInstall(context: Context): Boolean {
        val ready = _status.value as? Status.Ready ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            false
        }
    }
}
