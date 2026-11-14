package com.turkialawlqy.aman.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import java.util.Locale

/**
 * اختيار الصوت والترجمة (أفلام الاشتراك فيها غالباً أكثر من صوت وترجمة).
 * آخر لغة اختارها المستخدم تنحفظ وتتطبق على الفيديوهات الجاية تلقائياً.
 */
object TrackPrefs {
    private const val PREFS = "tracks"
    private const val OFF = "off"

    fun apply(context: Context, player: ExoPlayer) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val audio = p.getString("audio", "ar")
        val text = p.getString("text", null)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audio)
            .apply {
                if (text == OFF) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else if (text != null) setPreferredTextLanguage(text).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            .build()
    }

    private data class Option(val label: String, val type: Int, val group: Tracks.Group?, val index: Int, val selected: Boolean, val lang: String?)

    private fun options(player: ExoPlayer): List<Option> {
        val out = mutableListOf<Option>()
        val groups = player.currentTracks.groups
        var textOn = false
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val sel = g.isTrackSelected(i)
                if (g.type == C.TRACK_TYPE_TEXT && sel) textOn = true
                out += Option(label(f, g.type), g.type, g, i, sel, f.language)
            }
        }
        val audio = out.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = out.filter { it.type == C.TRACK_TYPE_TEXT }
        return audio + (if (text.isNotEmpty()) listOf(Option("بدون ترجمة", C.TRACK_TYPE_TEXT, null, -1, !textOn, OFF)) + text else emptyList())
    }

    fun hasChoices(player: ExoPlayer): Boolean {
        val o = options(player)
        return o.count { it.type == C.TRACK_TYPE_AUDIO } > 1 || o.any { it.type == C.TRACK_TYPE_TEXT }
    }

    private fun label(f: Format, type: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            when (it.lowercase().take(2)) {
                "ar" -> "عربي"; "en" -> "إنجليزي"; "fr" -> "فرنسي"; "tr" -> "تركي"; "hi" -> "هندي"; "es" -> "إسباني"
                "ko" -> "كوري"; "ja" -> "ياباني"; "de" -> "ألماني"; "fa" -> "فارسي"; "ur" -> "أوردو"
                else -> Locale(it).getDisplayLanguage(Locale("ar")).ifBlank { it }
            }
        }
        val name = listOfNotNull(lang, f.label?.takeIf { it.isNotBlank() && it != lang }).joinToString(" · ").ifBlank { "مسار" }
        val extra = if (type == C.TRACK_TYPE_AUDIO && f.channelCount >= 6) " (5.1)" else ""
        val kind = if (type == C.TRACK_TYPE_AUDIO) "الصوت" else "الترجمة"
        return "$kind: $name$extra"
    }

    fun showDialog(activity: Activity, player: ExoPlayer, onDone: () -> Unit) {
        val opts = options(player)
        if (opts.isEmpty()) return
        val labels = opts.map { (if (it.selected) "✓  " else "     ") + it.label }.toTypedArray()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("الصوت والترجمة")
            .setItems(labels) { _, which ->
                val o = opts[which]
                val b = player.trackSelectionParameters.buildUpon()
                if (o.group == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else {
                    b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                    if (o.type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(o.lang)
                    else b.setPreferredAudioLanguage(o.lang)
                }
                player.trackSelectionParameters = b.build()
                // نحفظ اللغة للفيديوهات الجاية
                val key = if (o.type == C.TRACK_TYPE_AUDIO) "audio" else "text"
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key, o.lang ?: if (o.group == null) OFF else null).apply()
                onDone()
            }
            .show()
    }
}
