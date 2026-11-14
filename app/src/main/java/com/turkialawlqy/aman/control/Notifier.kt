package com.turkialawlqy.aman.control

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * إشعارات تيليجرام للأب (عن طريق دالة telegram في Supabase):
 * - بدأت المشاهدة  - خلص الوقت اليومي
 * الخادم يمنع التكرار، وهنا نخفف الطلبات كمان.
 */
object Notifier {
    private const val TAG = "Notifier"
    private const val START_THROTTLE_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private lateinit var prefs: SharedPreferences
    private val lastStart = HashMap<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
    }

    fun started(profileId: String?) {
        val k = profileId ?: "_"
        val now = System.currentTimeMillis()
        if (now - (lastStart[k] ?: 0) < START_THROTTLE_MS) return
        lastStart[k] = now
        send("start", profileId)
    }

    /** مرة وحدة باليوم لكل طفل */
    fun limitReached(profileId: String?, usedSec: Long) {
        val key = "limit:${ParentalRules.today()}:${profileId ?: "_"}"
        if (!::prefs.isInitialized || prefs.getBoolean(key, false)) return
        prefs.edit().clear().putBoolean(key, true).apply() // نحتفظ بآخر يوم فقط
        send("limit", profileId, usedSec)
    }

    /** فيديوهات جديدة في قائمة/قناة تنتظر موافقة الأب */
    fun pending(itemId: String) = send("pending", null, itemId = itemId)

    private fun send(kind: String, profileId: String?, usedSec: Long = 0, itemId: String? = null) {
        val state = TvRepository.state.value
        if (state !is TvState.Ready || state.offline) return
        scope.launch {
            try {
                val token = TvRepository.supabase.auth.currentAccessTokenOrNull() ?: return@launch
                val body = buildJsonObject {
                    put("action", "event")
                    put("kind", kind)
                    put("profile_id", JsonPrimitive(profileId))
                    put("day", ParentalRules.today())
                    put("used_sec", usedSec)
                    put("item_id", JsonPrimitive(itemId))
                }.toString()
                val req = Request.Builder()
                    .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/telegram")
                    .header("Authorization", "Bearer $token")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) Log.w(TAG, "notify $kind: HTTP ${r.code}")
                }
            } catch (e: Exception) {
                // الإشعارات اختيارية (ممكن الأب ما فعّلها)
                Log.w(TAG, "notify $kind failed", e)
            }
        }
    }
}
