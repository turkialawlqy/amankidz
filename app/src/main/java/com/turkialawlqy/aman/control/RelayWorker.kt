package com.turkialawlqy.aman.control

import android.util.Log
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.player.XtreamLibrary
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * اللوحة تطلب من التلفزيون يجيب بيانات الاشتراك (لو سيرفر الاشتراك يرفض سيرفرات Supabase).
 * التلفزيون يطلبها من شبكة البيت ويرد بالنتيجة في جدول tv_relay.
 */
object RelayWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ALLOWED = setOf("", "get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams",
        "get_series_categories", "get_series", "get_series_info", "get_vod_info")
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(rec: JsonObject) {
        fun s(k: String) = (rec[k] as? JsonPrimitive)?.contentOrNull
        if (s("status") != "pending") return
        val id = s("id") ?: return
        val action = s("action") ?: return
        if (action !in ALLOWED) return
        val sourceId = s("source_id") ?: return
        val params = (rec["params"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }?.toMap().orEmpty().filterKeys { it in setOf("category_id", "series_id", "vod_id", "stream_id") }
        scope.launch {
            val src = (TvRepository.state.value as? TvState.Ready)?.iptv?.firstOrNull { it.id == sourceId } ?: return@launch
            var result: kotlinx.serialization.json.JsonElement = JsonNull
            var error: String? = null
            try {
                val body = XtreamLibrary.rawDirect(src, action, params)
                if (body.length > 15_000_000) throw IllegalStateException("الرد كبير مرة")
                result = json.parseToJsonElement(body)
            } catch (e: Exception) {
                error = e.message ?: "فشل"
            }
            try {
                TvRepository.supabase.postgrest.rpc("device_relay_answer", buildJsonObject {
                    put("p_id", id)
                    put("p_result", result)
                    put("p_error", JsonPrimitive(error))
                })
            } catch (e: Exception) {
                Log.w("RelayWorker", "answer failed", e)
            }
        }
    }
}
