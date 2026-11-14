package com.turkialawlqy.aman.offline

import android.content.Context
import android.util.Log
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.turkialawlqy.aman.data.TvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * نسخة محلية دائمة من صور المحتوى (الصور المصغرة، الخلفيات، صور الممثلين)
 * عشان تظهر بدون إنترنت. تتحمّل بالخلفية كل ما تغيّر المحتوى، وتنحذف لما ينحذف المحتوى.
 * أي صورة لها نسخة هنا تنعرض منها مباشرة (عن طريق [LocalFirst] في Coil).
 */
object ImageStore {
    private const val TAG = "ImageStore"
    private const val MAX_IMAGES = 4000

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun init(context: Context) {
        dir = File(context.filesDir, "images").apply { mkdirs() }
    }

    private fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fileOf(url: String) = File(dir, keyOf(url) + ".img")

    /** الملف المحلي لو موجود */
    fun localFile(url: String): File? {
        if (!::dir.isInitialized) return null
        return fileOf(url).takeIf { it.isFile && it.length() > 0 }
    }

    /** كل صور المحتوى الحالي */
    private fun urlsOf(s: TvState.Ready): Set<String> = buildSet {
        s.items.forEach { it ->
            listOfNotNull(it.thumbnailUrl, it.backdropUrl).forEach { u -> add(u) }
            it.castList.forEach { c -> c.photo?.let { p -> add(p) } }
        }
    }.filter { it.startsWith("http://") || it.startsWith("https://") }.take(MAX_IMAGES).toSet()

    /** يُستدعى كل ما تغيّر المحتوى: يحمّل الناقص ويحذف اللي ما عاد له استخدام */
    fun sync(s: TvState.Ready) {
        if (!::dir.isInitialized) return
        job?.cancel()
        job = scope.launch {
            delay(5_000) // ننتظر لين يهدأ التحديث
            val urls = urlsOf(s)
            if (s.items.isEmpty()) return@launch // احتياط: ما نمسح الصور لو المحتوى فاضي مؤقتاً
            // حذف القديم
            val keep = urls.map { keyOf(it) + ".img" }.toSet()
            dir.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
            // تحميل الناقص (واحد ورا الثاني — خفيف على النت)
            if (s.offline) return@launch
            for (u in urls) {
                if (!isActive) break
                val f = fileOf(u)
                if (f.isFile && f.length() > 0) continue
                try {
                    client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                        val body = r.body
                        if (!r.isSuccessful) return@use
                        val tmp = File(dir, f.name + ".part")
                        tmp.outputStream().use { o -> body.byteStream().copyTo(o) }
                        if (tmp.length() > 0) tmp.renameTo(f) else tmp.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "image failed: $u", e)
                    if (e is java.net.UnknownHostException) break // ما فيه إنترنت → نحاول المرة الجاية
                }
            }
        }
    }

    /** Coil: لو للصورة نسخة محلية نعرضها منها (يشتغل بدون إنترنت) */
    object LocalFirst : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data
            val url = when (data) {
                is String -> data
                is coil3.Uri -> data.toString()
                else -> null
            }
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                localFile(url)?.let { f ->
                    return chain.withRequest(chain.request.newBuilder().data(f).build()).proceed()
                }
            }
            return chain.proceed()
        }
    }
}
