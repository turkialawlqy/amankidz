package com.turkialawlqy.aman.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.offline.StorageOption
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * كل تواصل التلفزيون مع Supabase:
 * تسجيل مجهول → تسجيل الجهاز → كود الربط → تحميل المحتوى → التحديث اللحظي.
 * ولو انقطع الإنترنت يعرض آخر نسخة محفوظة من المحتوى.
 */
object TvRepository {
    private const val TAG = "TvRepository"
    private const val HEARTBEAT_MS = 3 * 60 * 1000L
    private const val PAIRING_REFRESH_MS = 10 * 60 * 1000L - 15_000L // قبل ما ينتهي الكود عند الخادم بـ 15 ثانية
    private const val OFFLINE_RETRY_MS = 60 * 1000L
    private const val PREF_HOME = "cached_home"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<TvState>(TvState.Loading)
    val state: StateFlow<TvState> = _state.asStateFlow()

    lateinit var supabase: SupabaseClient
        private set
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private var realtimeChannel: RealtimeChannel? = null
    private var pairingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryJob: Job? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("aman", Context.MODE_PRIVATE)
        supabase = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
        // كل ما تغيّر المحتوى أو الإعدادات: نحفظ نسخة محلية ونشغّل مدير التحميل
        scope.launch {
            state.collect { s ->
                if (s is TvState.Ready && !s.stale) {
                    if (!s.offline) saveHome(s)
                    OfflineStore.ensure(s.device.storageVolume)
                    runCatching { com.turkialawlqy.aman.control.NewEpisodes.check(s.items) } // تنبيه الحلقات الجديدة
                    if (!s.offline) launch { runCatching { com.turkialawlqy.aman.control.SeriesFollow.check(s.iptv) } } // مسلسلات المكتبة المتابعة
                    if (!s.offline) launch { runCatching { com.turkialawlqy.aman.player.XtreamLibrary.prefetch(s.libraries(null)) } } // تجهيز الاشتراك مسبقاً
                    OfflineDownloader.schedule(s)
                    com.turkialawlqy.aman.offline.ImageStore.sync(s) // صور المحتوى تنحفظ للعرض بدون إنترنت
                    if (!s.offline) com.turkialawlqy.aman.player.Epg.refresh(s) // دليل البرامج للقنوات المباشرة
                    if (!s.offline) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            com.turkialawlqy.aman.control.PlayNow.handle(appContext, s)
                            com.turkialawlqy.aman.control.TvMessages.handle(appContext, s.device)
                            com.turkialawlqy.aman.control.RemoteControl.handle(appContext, s.device)
                        }
                    }
                }
            }
        }
        retry()
    }

    fun retry() {
        if (_state.value !is TvState.Ready) {
            // فتح فوري: نعرض آخر نسخة محفوظة على طول، والجديد يوصل بعد ثواني بدون ما تنتظر
            val cached = runCatching { loadHome() }.getOrNull()?.takeIf { it.device.parentId != null }
            _state.value = if (cached != null) TvState.Ready(cached.device, cached.items, profiles = cached.profiles,
                categories = cached.categories, channels = cached.channels, epgUrls = cached.epgUrls, iptv = cached.iptv, stale = true)
            else TvState.Loading
        }
        scope.launch {
            try {
                bootstrap()
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap failed", e)
                val cached = loadHome()
                if (cached != null) {
                    // بدون إنترنت: نعرض المحفوظ ونحاول الاتصال كل دقيقة
                    _state.value = TvState.Ready(cached.device, cached.items, offline = true, profiles = cached.profiles,
                        categories = cached.categories, channels = cached.channels, epgUrls = cached.epgUrls, iptv = cached.iptv)
                    scheduleOfflineRetry()
                } else {
                    _state.value = TvState.Error("تعذر الاتصال بالخادم. تأكد من الإنترنت.")
                }
            }
        }
    }

    private fun scheduleOfflineRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(OFFLINE_RETRY_MS)
            retry()
        }
    }

    private suspend fun bootstrap() {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            _state.value = TvState.Error("لم يتم ضبط عنوان Supabase في التطبيق (local.properties).")
            return
        }
        val auth = supabase.auth
        auth.awaitInitialization()
        if (auth.currentUserOrNull() == null) {
            auth.signInAnonymously()
        }

        val device = registerDevice()
        subscribeRealtime(device.id, device.libraryDeviceId)
        startHeartbeat()
        onDevice(device)
    }

    private suspend fun registerDevice(): Device =
        supabase.postgrest.rpc(
            "register_device",
            buildJsonObject { put("p_app_version", BuildConfig.VERSION_NAME) }
        ).decodeAs()

    private suspend fun fetchDevice(id: String): Device? =
        supabase.from("devices").select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    /** محتوى الجهاز + مكتبته المشتركة (لو يعرض مكتبة جهاز ثاني) */
    private suspend fun fetchItems(device: Device): List<ContentItem> {
        val ids = listOf(device.libraryDeviceId, device.id).distinct()
        val all: List<ContentItem> = supabase.from("content_items").select {
            filter { isIn("device_id", ids) }
            order("position", Order.ASCENDING)
        }.decodeList()
        // محتوى المكتبة أول، وبعده اللي على هذا الجهاز بس (مثل فيديوهات الفلاشة)
        return all.sortedBy { if (it.deviceId == device.libraryDeviceId) 0 else 1 }
    }

    private suspend fun fetchChannels(deviceId: String): List<LiveChannel> = try {
        supabase.from("live_channels").select {
            filter { eq("device_id", deviceId); eq("enabled", true) }
            order("position", Order.ASCENDING)
        }.decodeList()
    } catch (e: Exception) {
        Log.w(TAG, "fetchChannels failed", e) // قبل fix_33
        emptyList()
    }

    private suspend fun fetchSources(deviceId: String): List<IptvSource> = try {
        supabase.from("iptv_sources").select {
            filter { eq("device_id", deviceId) }
        }.decodeList()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun fetchCategories(deviceId: String): List<Category> = try {
        supabase.from("categories").select {
            filter { eq("device_id", deviceId) }
            order("position", Order.ASCENDING)
        }.decodeList()
    } catch (e: Exception) {
        // قبل تشغيل fix_30 الجدول غير موجود
        Log.w(TAG, "fetchCategories failed", e)
        emptyList()
    }

    private suspend fun fetchProfiles(deviceId: String): List<ChildProfile> = try {
        supabase.from("child_profiles").select {
            filter { eq("device_id", deviceId) }
            order("position", Order.ASCENDING)
        }.decodeList()
    } catch (e: Exception) {
        // قبل تشغيل fix_08 الجدول غير موجود
        Log.w(TAG, "fetchProfiles failed", e)
        emptyList()
    }

    private suspend fun onDevice(device: Device) {
        if (device.parentId == null) {
            clearHome()
            startPairing(device)
        } else {
            pairingJob?.cancel()
            pairingJob = null
            if (device.libraryDeviceId != subscribedLibrary) subscribeRealtime(device.id, device.libraryDeviceId)
            val lib = device.libraryDeviceId
            // كل الطلبات بنفس الوقت (بدل وحدة ورا وحدة) — الفتح أسرع
            _state.value = kotlinx.coroutines.coroutineScope {
                val items = async { fetchItems(device) }
                val profiles = async { fetchProfiles(lib) }
                val cats = async { fetchCategories(lib) }
                val chans = async { fetchChannels(lib) }
                val srcs = async { fetchSources(lib) }
                val src = srcs.await()
                TvState.Ready(
                    device, items.await(), profiles = profiles.await(), categories = cats.await(), channels = chans.await(),
                    iptv = src, epgUrls = src.mapNotNull { it.epgUrl?.takeIf { u -> u.isNotBlank() } }.distinct(),
                )
            }
        }
    }

    private fun startPairing(device: Device) {
        if (pairingJob?.isActive == true) return
        pairingJob = scope.launch {
            var fails = 0
            while (isActive) {
                val wait = try {
                    val code: PairingCode = supabase.postgrest.rpc("new_pairing_code")
                        .decodeList<PairingCode>().first()
                    // الكود صالح 10 دقائق عند الخادم → نجدده قبلها بشوي، والعداد يبين الوقت الباقي
                    _state.value = TvState.Pairing(device.deviceCode, code.code, System.currentTimeMillis() + PAIRING_REFRESH_MS)
                    fails = 0
                    PAIRING_REFRESH_MS
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "pairing code failed", e)
                    // بعد كم محاولة فاشلة نعرض السبب بدل "جاري التحميل" للأبد (ونكمل نحاول)
                    if (++fails >= 3 && _state.value !is TvState.Pairing) {
                        _state.value = TvState.Error("تعذر جلب كود الربط من الخادم:\n${e.message?.take(160) ?: e.javaClass.simpleName}")
                    }
                    // ربما تم الربط للتو؛ نتحقق — ونحاول كود جديد بعد ثواني
                    refresh()
                    5_000L
                }
                delay(wait)
            }
        }
    }

    private var refreshJob: Job? = null

    /** تحديث مؤجل شوي: لو جت تغييرات كثيرة ورا بعض (إضافة 30 حلقة مثلاً) نجلب مرة وحدة */
    private fun refreshSoon() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(700)
            refresh()
        }
    }

    private fun onDeviceRow(action: PostgresAction) {
        val record = when (action) {
            is PostgresAction.Update -> action.record
            is PostgresAction.Insert -> action.record
            else -> null
        }
        val cur = _state.value as? TvState.Ready
        val d = record?.let { runCatching { json.decodeFromJsonElement(Device.serializer(), it) }.getOrNull() }
        when {
            cur == null || d == null -> refreshSoon()
            d == cur.device -> Unit // ما تغيّر شي يخص التلفزيون (نسبة تحميل، آخر ظهور…)
            d.libraryId != cur.device.libraryId || d.parentId != cur.device.parentId -> refreshSoon()
            // إعداد تغيّر (قفل، وقت، ثيم…) → نجلب صف الجهاز بس (أضمن من الحدث نفسه)، بدون المحتوى
            else -> scope.launch {
                runCatching { fetchDevice(cur.device.id) }.getOrNull()?.let { fresh ->
                    val now = _state.value as? TvState.Ready ?: return@let
                    if (fresh.libraryId != now.device.libraryId || fresh.parentId != now.device.parentId) refreshSoon()
                    else if (fresh != now.device) _state.value = now.copy(device = fresh)
                }
            }
        }
    }

    /** يعيد تحميل الجهاز والمحتوى (يُستدعى عند أي تغيير لحظي). */
    fun refresh() {
        scope.launch {
            refreshLock.withLock {
                try {
                    val uid = supabase.auth.currentUserOrNull()?.id ?: return@withLock
                    val device = fetchDevice(uid) ?: registerDevice()
                    onDevice(device)
                } catch (e: Exception) {
                    Log.e(TAG, "refresh failed", e)
                }
            }
        }
    }

    /** المكتبة اللي مشترك في تحديثاتها اللحظية حالياً */
    @Volatile private var subscribedLibrary: String? = null

    private suspend fun subscribeRealtime(deviceId: String, libraryId: String = deviceId) {
        realtimeChannel?.let { supabase.realtime.removeChannel(it) }
        subscribedLibrary = libraryId
        val channel = supabase.channel("tv-$deviceId-$libraryId")

        val contentChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "content_items"
            filter("device_id", FilterOperator.EQ, deviceId)
        }
        // المكتبة المشتركة (لو الجهاز يعرض محتوى جهاز ثاني)
        val libraryChanges = if (libraryId != deviceId) merge(
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "content_items"
                filter("device_id", FilterOperator.EQ, libraryId)
            },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "child_profiles"
                filter("device_id", FilterOperator.EQ, libraryId)
            },
        ) else kotlinx.coroutines.flow.emptyFlow()
        val categoryChanges = merge(
            channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "categories" },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "live_channels"
                filter("device_id", FilterOperator.EQ, libraryId)
            },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "iptv_sources"
                filter("device_id", FilterOperator.EQ, libraryId)
            },
        )
        // أحداث الحذف لا تدعم الفلترة في Supabase، فنستمع لها بدون فلتر
        val contentDeletes = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "content_items"
        }
        val deviceChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "devices"
            filter("id", FilterOperator.EQ, deviceId)
        }

        val profileChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "child_profiles"
            filter("device_id", FilterOperator.EQ, deviceId)
        }
        val profileDeletes = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "child_profiles"
        }

        // المدير نشر إصدار جديد → نشيك على التحديث فوراً
        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "app_releases" }
            .onEach { com.turkialawlqy.aman.control.AppUpdater.checkNow() }
            .launchIn(scope)

        merge(contentChanges, contentDeletes, profileChanges, profileDeletes, libraryChanges, categoryChanges)
            .onEach { refreshSoon() }
            .launchIn(scope)
        // تغيير في صف الجهاز: أغلبه من التلفزيون نفسه (نسبة التحميل كل 3 ثواني، الحالة…)
        // → نحدّث الإعدادات بس لو تغيّرت فعلاً، بدون ما نعيد جلب كل المحتوى
        deviceChanges.onEach { onDeviceRow(it) }.launchIn(scope)

        channel.subscribe()
        realtimeChannel = channel
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(HEARTBEAT_MS)
                try {
                    supabase.postgrest.rpc(
                        "device_heartbeat",
                        buildJsonObject { put("p_app_version", BuildConfig.VERSION_NAME) }
                    )
                    // احتياط لو فات حدث لحظي: جلب كامل كل 30 دقيقة بس (مو كل 3 دقائق)
                    // — يوفر استهلاك Supabase المجاني (المحتوى مع الممثلين ممكن يكون مئات الكيلوبايت)
                    if (++n % 10 == 0) refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ إعدادات التلفزيون (بالرمز)

    /** يرجع null لو صحيح، أو رسالة الخطأ */
    suspend fun checkPin(pin: String): String? = try {
        val ok = supabase.postgrest.rpc("device_check_pin", buildJsonObject { put("p_pin", pin) })
            .decodeAs<Boolean>()
        if (ok) null else "الرمز غير صحيح"
    } catch (e: Exception) {
        Log.w(TAG, "checkPin failed", e)
        e.message?.lineSequence()?.firstOrNull { it.any { c -> c in '؀'..'ۿ' } }
            ?: "تعذر التحقق. تأكد من الإنترنت."
    }

    suspend fun updateSettings(
        pin: String,
        saveWatched: Boolean,
        autoDownload: Boolean,
        quality: Int,
        volumeId: String?,
    ): Boolean = try {
        supabase.postgrest.rpc("device_update_settings", buildJsonObject {
            put("p_pin", pin)
            put("p_save_watched", saveWatched)
            put("p_auto_download", autoDownload)
            put("p_download_quality", quality)
            put("p_storage_volume", JsonPrimitive(volumeId))
        })
        refresh()
        true
    } catch (e: Exception) {
        Log.w(TAG, "updateSettings failed", e)
        false
    }

    /** يرسل للأب: أماكن التخزين المتاحة والمساحة المستخدمة وحالة التحميل */
    suspend fun reportStorage(
        volumes: List<StorageOption>,
        usedBytes: Long,
        count: Int,
        status: String?,
        offlineIds: List<String> = emptyList(),
    ) {
        if (_state.value.let { it !is TvState.Ready || it.offline }) return
        try {
            supabase.postgrest.rpc("device_report_storage", buildJsonObject {
                put("p_volumes", json.encodeToJsonElement(volumes))
                put("p_used", usedBytes)
                put("p_count", count)
                put("p_status", JsonPrimitive(status))
                put("p_offline_ids", JsonArray(offlineIds.map { JsonPrimitive(it) }))
            })
        } catch (e: Exception) {
            Log.w(TAG, "reportStorage failed", e)
        }
    }

    /** ما يُعرض الآن على التلفزيون (للريموت في لوحة الأهل) */
    suspend fun reportPlaying(now: kotlinx.serialization.json.JsonObject?) {
        if (_state.value.let { it !is TvState.Ready || it.offline }) return
        try {
            supabase.postgrest.rpc("device_report_playing", buildJsonObject {
                put("p_now", now ?: kotlinx.serialization.json.JsonNull)
            })
        } catch (e: Exception) {
            Log.w(TAG, "reportPlaying failed", e)
        }
    }

    /** الطفل شاف رسالة الأب */
    suspend fun ackMessage(id: String) {
        try {
            supabase.postgrest.rpc("device_ack_message", buildJsonObject { put("p_id", id) })
        } catch (e: Exception) {
            Log.w(TAG, "ackMessage failed", e)
        }
    }

    /** تقدّم التحميل الحالي للأب (null = خلص/وقف) */
    suspend fun reportDownload(progress: kotlinx.serialization.json.JsonObject?) {
        if (_state.value.let { it !is TvState.Ready || it.offline }) return
        try {
            supabase.postgrest.rpc("device_report_download", buildJsonObject {
                put("p_progress", progress ?: kotlinx.serialization.json.JsonNull)
            })
        } catch (e: Exception) {
            Log.w(TAG, "reportDownload failed", e)
        }
    }

    /** يرسل محتوى قائمة/قناة للأب (عشان يقدر يخفي فيديوهات منها) */
    suspend fun reportCollection(itemId: String, entries: List<com.turkialawlqy.aman.yt.VideoEntry>): Boolean {
        if (_state.value.let { it !is TvState.Ready || it.offline }) return false
        return try {
            val arr = JsonArray(entries.mapNotNull { e ->
                val id = com.turkialawlqy.aman.yt.VideoExtractor.videoIdOf(e.url) ?: return@mapNotNull null
                buildJsonObject {
                    put("id", id)
                    put("title", e.title)
                    put("thumb", JsonPrimitive(e.thumbnail))
                    put("duration", e.durationSec)
                }
            })
            val result = supabase.postgrest.rpc("device_report_collection", buildJsonObject {
                put("p_item", itemId)
                put("p_videos", arr)
            })
            // فيديوهات جديدة تنتظر موافقة الأب → إشعار تيليجرام
            val pending = runCatching { result.data.trim().toInt() }.getOrDefault(0)
            if (pending > 0) com.turkialawlqy.aman.control.Notifier.pending(itemId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "reportCollection failed", e)
            false
        }
    }

    /** يرسل سجل المشاهدة ووقت اليوم للأب */
    suspend fun logWatch(
        entries: List<com.turkialawlqy.aman.control.WatchTracker.Entry>,
        day: String,
        usedSec: Long,
        byProfile: Map<String, Long> = emptyMap(),
        eduBy: Map<String, Long> = emptyMap(),
    ): Boolean {
        if (_state.value.let { it !is TvState.Ready || it.offline }) return false
        return try {
            supabase.postgrest.rpc("device_log_watch", buildJsonObject {
                put("p_entries", json.encodeToJsonElement(entries))
                put("p_day", day)
                put("p_sec", usedSec.toInt())
                put("p_by", buildJsonObject { byProfile.forEach { (k, v) -> put(k, v) } })
                put("p_edu", buildJsonObject { eduBy.forEach { (k, v) -> put(k, v) } })
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "logWatch failed", e)
            false
        }
    }

    /** إجراء من ولي الأمر على التلفزيون (بالرمز): bonus | sleep | sleep_cancel */
    suspend fun parentAction(pin: String, action: String, minutes: Int = 0): Boolean = try {
        supabase.postgrest.rpc("device_parent_action", buildJsonObject {
            put("p_pin", pin)
            put("p_action", action)
            put("p_minutes", minutes)
            put("p_day", com.turkialawlqy.aman.control.ParentalRules.today())
        })
        refresh()
        true
    } catch (e: Exception) {
        Log.w(TAG, "parentAction failed", e)
        false
    }

    // ------------------------------------------------------------------ نسخة محلية من الرئيسية

    private fun saveHome(s: TvState.Ready) {
        try {
            prefs.edit().putString(PREF_HOME, json.encodeToString(CachedHome(s.device, s.items, s.profiles, s.categories, s.channels, s.epgUrls, s.iptv))).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveHome failed", e)
        }
    }

    private fun loadHome(): CachedHome? = try {
        prefs.getString(PREF_HOME, null)?.let { json.decodeFromString<CachedHome>(it) }
    } catch (e: Exception) {
        null
    }

    private fun clearHome() {
        prefs.edit().remove(PREF_HOME).apply()
    }
}
