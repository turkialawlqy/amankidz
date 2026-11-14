package com.turkialawlqy.aman.player

import android.content.Context
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.control.ProfileStore
import com.turkialawlqy.aman.data.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * القنوات المفضلة وآخر القنوات اللي انشافت (لكل ملف شخصي لحاله).
 * نحفظ القناة كاملة لأن قنوات مكتبة الاشتراك ما تكون في قاعدة البيانات.
 */
object LiveStore {
    private const val MAX_RECENT = 15
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs get() = App.instance.getSharedPreferences("live_store", Context.MODE_PRIVATE)
    private fun k(name: String) = (ProfileStore.current.value ?: "_") + "|" + name

    private val _favorites = MutableStateFlow<List<LiveChannel>>(emptyList())
    val favorites: StateFlow<List<LiveChannel>> = _favorites.asStateFlow()
    private val _recent = MutableStateFlow<List<LiveChannel>>(emptyList())
    val recent: StateFlow<List<LiveChannel>> = _recent.asStateFlow()

    private fun read(name: String): List<LiveChannel> = runCatching {
        prefs.getString(k(name), null)?.let { json.decodeFromString(ListSerializer(LiveChannel.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun write(name: String, list: List<LiveChannel>) =
        prefs.edit().putString(k(name), json.encodeToString(ListSerializer(LiveChannel.serializer()), list)).apply()

    /** بعد تغيير الملف الشخصي */
    fun refresh() {
        _favorites.value = read("fav")
        _recent.value = read("recent")
    }

    fun isFavorite(c: LiveChannel) = _favorites.value.any { it.id == c.id }

    /** @return true لو انضافت */
    fun toggleFavorite(c: LiveChannel): Boolean {
        val cur = read("fav")
        val added = cur.none { it.id == c.id }
        val next = if (added) cur + c else cur.filterNot { it.id == c.id }
        write("fav", next); _favorites.value = next
        return added
    }

    fun played(c: LiveChannel) {
        val next = (listOf(c) + read("recent").filterNot { it.id == c.id }).take(MAX_RECENT)
        write("recent", next); _recent.value = next
    }
}
