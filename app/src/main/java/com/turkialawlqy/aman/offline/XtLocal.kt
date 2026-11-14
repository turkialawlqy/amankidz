package com.turkialawlqy.aman.offline

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.data.ContentItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * أفلام وحلقات الاشتراك اللي نزّلتها من الكمبيوتر وحطيتها على الفلاشة.
 * اسم الملف فيه معرّف الحلقة بين قوسين، مثل:  S01E13 - الحلقة 13 [xt_e1a2b3c_12345].mkv
 * (اللوحة تسوي الأسماء كذا في ملف «تحميل على الكمبيوتر»)
 *
 * الملف يرتبط بالحلقة الموجودة في المحتوى (ما ينضاف كفيديو جديد):
 * 1) بنفس المعرّف، أو
 * 2) برقم الحلقة في الاشتراك (آخر الرقم) — لو الحلقة انضافت من اشتراك قبل أو تغيّر معرّفها.
 * لو استوردته «نسخ للتلفزيون» ينحفظ بالذاكرة ويرتبط بنفس الطريقة.
 */
object XtLocal {
    private val KEY = Regex("\\[(xt_[a-z]+[0-9a-f]{6}_([^\\]]+))]", RegexOption.IGNORE_CASE)
    /** ملفات الفلاشة: المعرّف → الملف، ورقم الحلقة → الملف */
    private val files = ConcurrentHashMap<String, Uri>()
    private val byNum = ConcurrentHashMap<String, Uri>()
    @Volatile private var scannedAt = 0L

    private val prefs get() = App.instance.getSharedPreferences("xtlocal", Context.MODE_PRIVATE)

    /** معرّف الملف من اسمه: [xt_e…_123] → (المعرّف، رقم الحلقة) */
    fun keyOf(fileName: String): Pair<String, String>? =
        KEY.find(fileName)?.groupValues?.let { it[1] to it[2] }

    /** رقم الحلقة في الاشتراك من رابطها: …/series/u/p/123.mkv → 123 */
    private fun numOf(url: String?): String? =
        url?.let { u -> u.substringAfter('@', u) }?.let { Regex("/(?:series|movie)/[^/]+/[^/]+/([^/.]+)\\.\\w+$").find(it)?.groupValues?.get(1) }

    /** يدوّر في الفلاشات (سريع: من فهرس الوسائط) — مرة كل 5 دقايق بالكثير */
    fun refresh(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && scannedAt != 0L && now - scannedAt < 5 * 60_000) return
        scannedAt = now
        val found = runCatching { LocalMedia.scan(context) }.getOrDefault(emptyList())
        val map = HashMap<String, Uri>()
        val nums = HashMap<String, Uri>()
        for (folder in found) for (v in folder.videos) {
            keyOf(v.name)?.let { (k, n) -> map[k] = v.uri; nums[n] = v.uri }
        }
        files.keys.retainAll(map.keys); files.putAll(map)
        byNum.keys.retainAll(nums.keys); byNum.putAll(nums)
    }

    /** نسخة محفوظة بالتلفزيون (من الاستيراد) */
    private fun copied(videoId: String): Uri? =
        prefs.getString(videoId, null)?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    fun registerCopy(videoId: String, file: File) = prefs.edit().putString(videoId, file.absolutePath).apply()

    private fun lookup(videoId: String, url: String?): Uri? =
        copied(videoId) ?: files[videoId] ?: numOf(url)?.let { byNum[it] }

    fun find(context: Context, videoId: String?, url: String? = null): Uri? {
        if (videoId == null) return null
        lookup(videoId, url)?.let { return it }
        refresh(context)
        return lookup(videoId, url)
    }

    /** الحلقات اللي لها ملف (على الفلاشة أو منسوخ) — تنعرض "محفوظ" وما تتحمّل */
    fun savedIds(items: List<ContentItem>): Set<String> =
        items.asSequence().filter { com.turkialawlqy.aman.yt.XtMedia.isXtId(it.youtubeId) }
            .filter { lookup(it.youtubeId, it.url) != null }.map { it.youtubeId }.toSet()

    /** وين ينربط ملف من الفلاشة؟ (حلقة موجودة في المحتوى) */
    fun targetFor(fileName: String, items: List<ContentItem>): ContentItem? {
        val (k, n) = keyOf(fileName) ?: return null
        return items.firstOrNull { it.youtubeId == k }
            ?: items.firstOrNull { com.turkialawlqy.aman.yt.XtMedia.isXtId(it.youtubeId) && numOf(it.url) == n }
    }
}
