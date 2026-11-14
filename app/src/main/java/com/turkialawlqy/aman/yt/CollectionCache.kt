package com.turkialawlqy.aman.yt

import android.util.Log
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.TvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedCollection(
    val items: List<VideoEntry>,
    val fetchedAt: Long,
    val reportedHash: Int = 0,
)

/**
 * نسخة محلية من محتوى كل قائمة/قناة يوتيوب:
 * - تفتح القائمة بدون إنترنت (مع المحفوظ منها)
 * - نرسلها للأب عشان يقدر يخفي فيديوهات معينة
 */
object CollectionCache {
    private const val TAG = "CollectionCache"
    private const val REPORT_MAX = 300
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File get() = File(App.instance.filesDir, "collections").apply { mkdirs() }
    private fun file(itemId: String) = File(dir, "$itemId.json")

    fun get(itemId: String): CachedCollection? = try {
        file(itemId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<CachedCollection>(it) }
    } catch (e: Exception) {
        null
    }

    /** يحفظ القائمة (لو أطول من المحفوظ أو أحدث) ويرسلها للأب لو تغيّرت */
    @Synchronized
    fun put(itemId: String, items: List<VideoEntry>, replace: Boolean = false) {
        if (items.isEmpty()) return
        val old = get(itemId)
        val merged = if (!replace && old != null && old.items.size > items.size &&
            old.items.take(items.size).map { it.url } == items.map { it.url }
        ) old.items else items
        val hash = merged.take(REPORT_MAX).map { it.url }.hashCode()
        val updated = CachedCollection(merged, System.currentTimeMillis(), old?.reportedHash ?: 0)
        try {
            file(itemId).writeText(json.encodeToString(updated))
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
        if (hash != updated.reportedHash) {
            scope.launch {
                if (TvRepository.reportCollection(itemId, merged.take(REPORT_MAX))) {
                    synchronized(this@CollectionCache) {
                        get(itemId)?.let { file(itemId).writeText(json.encodeToString(it.copy(reportedHash = hash))) }
                    }
                }
            }
        }
    }

    /**
     * محتوى القائمة: من النسخة المحلية لو حديثة وكافية، وإلا من يوتيوب.
     * @param count أقل عدد مطلوب
     */
    suspend fun listing(item: ContentItem, count: Int, maxAgeMs: Long): List<VideoEntry> {
        val cached = get(item.id)
        if (cached != null && cached.items.size >= count &&
            System.currentTimeMillis() - cached.fetchedAt < maxAgeMs
        ) return cached.items

        val pager = CollectionPager(item.kind, item.url)
        val list = mutableListOf<VideoEntry>()
        while (list.size < count && pager.hasMore) {
            val page = pager.loadNext()
            if (page.isEmpty()) break
            list += page
        }
        put(item.id, list, replace = true)
        return list
    }

    /** حذف نسخ القوائم اللي انحذفت */
    fun retainOnly(itemIds: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension !in itemIds) f.delete()
        }
    }
}
