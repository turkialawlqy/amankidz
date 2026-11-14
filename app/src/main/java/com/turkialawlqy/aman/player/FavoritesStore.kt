package com.turkialawlqy.aman.player

import android.content.Context
import android.content.SharedPreferences
import com.turkialawlqy.aman.control.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** "مفضلتي": كل طفل له قائمته (ضغطة مطوّلة على OK أثناء المشاهدة) */
object FavoritesStore {
    private const val MAX = 100
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private val _items = MutableStateFlow<List<VideoMeta>>(emptyList())
    val items: StateFlow<List<VideoMeta>> = _items.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        refresh()
    }

    private fun key() = ProfileStore.current.value ?: "_"

    fun refresh() {
        if (!::prefs.isInitialized) return
        _items.value = try {
            prefs.getString(key(), null)?.let { json.decodeFromString<List<VideoMeta>>(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(videoId: String) = _items.value.any { it.videoId == videoId }

    /** يرجّع true لو انضاف، false لو انشال */
    fun toggle(meta: VideoMeta): Boolean {
        val list = _items.value.toMutableList()
        val added = list.removeAll { it.videoId == meta.videoId }.not()
        if (added) list.add(0, meta.copy(at = System.currentTimeMillis()))
        val trimmed = list.take(MAX)
        _items.value = trimmed
        prefs.edit().putString(key(), json.encodeToString(trimmed)).apply()
        return added
    }
}
