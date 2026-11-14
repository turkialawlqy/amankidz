package com.turkialawlqy.aman.control

import android.content.Context
import android.content.SharedPreferences
import com.turkialawlqy.aman.data.ChildProfile
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** الطفل اللي يشاهد الآن (يختار صورته عند فتح التطبيق) */
object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aman", Context.MODE_PRIVATE)
    }

    /** آخر طفل اختار (عشان يكون التركيز عليه في شاشة الاختيار) */
    fun lastId(): String? = prefs.getString("last_profile", null)

    fun select(id: String?) {
        WatchTracker.end() // الفيديو الحالي يُحسب على الطفل السابق
        _current.value = id
        if (id != null) prefs.edit().putString("last_profile", id).apply()
        WatchTracker.refresh()
        com.turkialawlqy.aman.player.ResumeStore.refresh()
        com.turkialawlqy.aman.player.FavoritesStore.refresh()
        com.turkialawlqy.aman.player.LiveStore.refresh()
    }

    /** يطلب الاختيار من جديد (عند فتح التطبيق، أو زر "تبديل") */
    fun clear() = select(null)

    fun currentProfile(): ChildProfile? =
        (TvRepository.state.value as? TvState.Ready)?.profile(_current.value)
}
