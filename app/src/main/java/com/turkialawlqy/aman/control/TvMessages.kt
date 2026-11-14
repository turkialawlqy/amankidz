package com.turkialawlqy.aman.control

import android.content.Context
import com.turkialawlqy.aman.data.Device
import com.turkialawlqy.aman.data.TvMessage
import com.turkialawlqy.aman.data.TvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** رسالة من الأب تظهر على الشاشة ("العشاء جاهز") وتوقف الفيديو لين يضغط الطفل حسناً */
object TvMessages {
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _current = MutableStateFlow<TvMessage?>(null)
    val current: StateFlow<TvMessage?> = _current.asStateFlow()

    fun handle(context: Context, d: Device) {
        val m = d.tvMessage ?: return
        val prefs = context.getSharedPreferences("aman", Context.MODE_PRIVATE)
        if (prefs.getString("msg_seen", null) == m.id || _current.value?.id == m.id) return
        if (m.at > 0 && System.currentTimeMillis() - m.at > MAX_AGE_MS) return // رسالة قديمة
        prefs.edit().putString("msg_seen", m.id).apply()
        _current.value = m
    }

    /** الطفل ضغط "حسناً" */
    fun dismiss() {
        val m = _current.value ?: return
        _current.value = null
        scope.launch { TvRepository.ackMessage(m.id) }
    }
}
