package com.turkialawlqy.aman.control

import android.content.Context
import android.content.Intent
import android.util.Log
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.player.PlayerActivity

/** "شغّل الآن": الأب يختار فيديو من الموقع فيشتغل على التلفزيون مباشرة */
object PlayNow {
    private const val TAG = "PlayNow"
    private const val MAX_AGE_MS = 3 * 60 * 1000L

    fun handle(context: Context, state: TvState.Ready) {
        val req = state.device.playRequest ?: return
        val prefs = context.getSharedPreferences("aman", Context.MODE_PRIVATE)
        if (prefs.getString("play_request_done", null) == req.at) return
        prefs.edit().putString("play_request_done", req.at).apply()

        val at = ParentalRules.parseIsoMillis(req.at) ?: return
        if (System.currentTimeMillis() - at > MAX_AGE_MS) return // طلب قديم
        if (state.device.locked) return
        if (ParentalRules.current(state).blocked) return
        // العنصر لازم يكون موجود (المشغّل يتحقق كمان من الإخفاء)
        if (req.itemId != null && state.items.none { it.id == req.itemId }) return

        try {
            val intent = PlayerActivity.buildIntent(
                context,
                // رابط العنصر نفسه (أفلام الاشتراك والفلاشة لها روابط خاصة)، وإلا من المعرّف
                urls = listOf(state.items.firstOrNull { it.id == req.itemId }?.url?.takeIf { it.isNotBlank() }
                    ?: com.turkialawlqy.aman.yt.VideoExtractor.urlFor(req.videoId)),
                index = 0,
                sourceItemId = req.itemId,
            ).putExtra(PlayerActivity.EXTRA_PARENT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        } catch (e: Exception) {
            // أندرويد يمنع فتح شاشة والتطبيق بالخلفية
            Log.w(TAG, "could not start player", e)
        }
    }
}
