package com.turkialawlqy.aman.control

import android.content.Context
import android.media.AudioManager
import com.turkialawlqy.aman.data.Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ريموت من لوحة الأهل: الصوت يتنفذ هنا مباشرة،
 * وأوامر التشغيل (إيقاف/تشغيل/التالي/السابق/إغلاق) يستقبلها المشغّل.
 */
object RemoteControl {
    private const val MAX_AGE_MS = 60 * 1000L

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    fun handle(context: Context, d: Device) {
        val c = d.remoteCmd ?: return
        val prefs = context.getSharedPreferences("aman", Context.MODE_PRIVATE)
        if (prefs.getString("remote_done", null) == c.id) return
        prefs.edit().putString("remote_done", c.id).apply()
        if (c.at > 0 && System.currentTimeMillis() - c.at > MAX_AGE_MS) return // أمر قديم

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        when (c.cmd) {
            "vol_up" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> _commands.tryEmit(c.cmd) // pause | play | next | prev | stop
        }
    }
}
