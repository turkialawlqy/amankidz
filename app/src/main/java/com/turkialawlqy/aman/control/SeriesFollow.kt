package com.turkialawlqy.aman.control

import android.content.Context
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.IptvSource
import com.turkialawlqy.aman.offline.LocalDb
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.player.VideoMeta
import com.turkialawlqy.aman.player.XtreamLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "تابع المسلسل" من مكتبة الاشتراك — كله على التلفزيون (قاعدة البيانات المحلية، بدون Supabase):
 * التلفزيون يشيك على المسلسلات المتابعة كل 6 ساعات، وأي حلقة جديدة تتحمّل لحالها وينزل تنبيه.
 */
object SeriesFollow {
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private val lock = Mutex()
    private val prefs get() = App.instance.getSharedPreferences("series_follow", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    /** المسلسلات المتابعة (للزر في صفحة المسلسل) */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun load() { _keys.value = runCatching { LocalDb.follows().map { it.seriesKey }.toSet() }.getOrDefault(emptySet()) }

    /** @return true لو صار متابَع */
    fun toggle(mix: ContentItem, episodes: List<ContentItem>, sourceId: String, seriesId: String): Boolean {
        val on = !LocalDb.isFollowed(mix.id)
        if (on) LocalDb.follow(LocalDb.Follow(mix.id, sourceId, seriesId, mix.title, mix.posterUrl ?: mix.thumbnailUrl,
            episodes.map { it.youtubeId }.toSet(), System.currentTimeMillis()))
        else LocalDb.unfollow(mix.id)
        load()
        return on
    }

    /** يشيك على الحلقات الجديدة (مرة كل 6 ساعات بالكثير) */
    suspend fun check(sources: List<IptvSource>, force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("at", 0) < EVERY_MS) return@withLock
        val follows = runCatching { LocalDb.follows() }.getOrDefault(emptyList())
        if (follows.isEmpty()) return@withLock
        prefs.edit().putLong("at", now).apply()
        val news = mutableListOf<String>()
        for (f in follows) {
            val (mix, eps) = XtreamLibrary.seriesByMixId(sources, f.seriesKey) ?: continue
            val fresh = eps.filter { it.youtubeId !in f.known }
            if (fresh.isEmpty()) continue
            fresh.forEach { ep ->
                OfflineDownloader.requestNow(VideoMeta(ep.youtubeId, ep.title, ep.thumbnailUrl ?: mix.thumbnailUrl, ep.url, f.seriesKey))
            }
            LocalDb.setKnown(f.seriesKey, eps.map { it.youtubeId }.toSet())
            news += if (fresh.size == 1) "نزلت حلقة جديدة من «${f.title}»" else "نزلت ${fresh.size} حلقات جديدة من «${f.title}»"
        }
        if (news.isNotEmpty()) NewEpisodes.notify(news.joinToString(" · "))
    }
}
