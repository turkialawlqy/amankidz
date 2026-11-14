package com.turkialawlqy.aman.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String = "",
    @SerialName("device_code") val deviceCode: String = "",
    val locked: Boolean = false,
    // إعدادات الحفظ والتحميل
    @SerialName("save_watched") val saveWatched: Boolean = false,
    @SerialName("auto_download") val autoDownload: Boolean = false,
    @SerialName("download_quality") val downloadQuality: Int = 480,
    @SerialName("collection_download_limit") val collectionDownloadLimit: Int = 10,
    @SerialName("download_parallel") val downloadParallel: Int = 3, // كم فيديو يتحمّل بنفس الوقت (من لوحة الأهل)
    @SerialName("storage_volume") val storageVolume: String? = null,
    @SerialName("settings_pin") val hasPinRaw: String? = null,
    // وقت المشاهدة
    @SerialName("daily_limit_min") val dailyLimitMin: Int? = null,
    @SerialName("allowed_from") val allowedFrom: String? = null,
    @SerialName("allowed_to") val allowedTo: String? = null,
    @SerialName("bonus_minutes") val bonusMinutes: Int = 0,
    @SerialName("bonus_day") val bonusDay: String? = null,
    @SerialName("sleep_at") val sleepAt: String? = null,
    // جدولة التحميل
    @SerialName("download_from") val downloadFrom: String? = null,
    @SerialName("download_to") val downloadTo: String? = null,
    @SerialName("download_wifi_only") val downloadWifiOnly: Boolean = false,
    // "شغّل الآن" من الأب
    @SerialName("play_request") val playRequest: PlayRequest? = null,
    @SerialName("search_enabled") val searchEnabled: Boolean = true,
    // أيام الإجازة (0=الأحد … 6=السبت) وحدودها
    @SerialName("weekend_days") val weekendDays: List<Int> = listOf(5, 6),
    @SerialName("weekend_limit_min") val weekendLimitMin: Int? = null,
    @SerialName("weekend_from") val weekendFrom: String? = null,
    @SerialName("weekend_to") val weekendTo: String? = null,
    // فيديوهات يبغاها الأب تتحمّل أول (بالترتيب)
    @SerialName("download_priority") val downloadPriority: List<String> = emptyList(),
    // مكافأة التعلّم
    @SerialName("edu_every_min") val eduEveryMin: Int? = null,
    @SerialName("edu_reward_min") val eduRewardMin: Int = 10,
    @SerialName("edu_max_min") val eduMaxMin: Int = 60,
    // رسالة للتلفزيون + الريموت + فلتر الكلمات
    @SerialName("tv_message") val tvMessage: TvMessage? = null,
    @SerialName("remote_cmd") val remoteCmd: RemoteCmd? = null,
    @SerialName("blocked_words") val blockedWords: List<String> = emptyList(),
    val theme: String? = null, // ثيم الجهاز (shahid / netflix)
    /** يعرض مكتبة جهاز ثاني (المحتوى والأطفال والأقسام) — فاضي = مكتبته هو */
    @SerialName("library_id") val libraryId: String? = null,
) {
    /** الجهاز اللي محتواه يظهر هنا */
    val libraryDeviceId: String get() = libraryId ?: id

    val hasPin: Boolean get() = !hasPinRaw.isNullOrBlank()

    /** أقصى دقة للتشغيل: لو الحفظ مفعّل نلتزم بجودة الأب عشان نوفر إنترنت ومساحة */
    val playbackMaxHeight: Int get() = if (saveWatched || autoDownload) downloadQuality else 1080
}

@Serializable
data class TvMessage(val id: String, val text: String, val at: Long = 0, val from: String? = null)

@Serializable
data class RemoteCmd(val id: String, val cmd: String, val at: Long = 0)

@Serializable
data class PlayRequest(
    @SerialName("video_id") val videoId: String,
    @SerialName("item_id") val itemId: String? = null,
    val title: String = "",
    val at: String,
)

@Serializable
data class ContentItem(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val kind: String, // video | playlist | channel | mix (قائمة يصنعها الأب)
    @SerialName("youtube_id") val youtubeId: String,
    val url: String,
    val title: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val position: Int = 0,
    val offline: Boolean = true, // يدخل ضمن التحميل التلقائي؟
    @SerialName("parent_id") val parentId: String? = null, // فيديو داخل قائمة خاصة
    // فيديوهات أخفاها الأب من قائمة/قناة يوتيوب
    @SerialName("excluded_video_ids") val excludedVideoIds: List<String> = emptyList(),
    // لمين يظهر؟ فاضي = لكل الأطفال
    @SerialName("profile_ids") val profileIds: List<String> = emptyList(),
    // موافقة الأب على الفيديوهات الجديدة (للقوائم والقنوات)
    @SerialName("require_approval") val requireApproval: Boolean = false,
    @SerialName("approved_video_ids") val approvedVideoIds: List<String> = emptyList(),
    // محتوى تعليمي: ما ينحسب من الحد، ويكسب الطفل وقت ترفيه
    val educational: Boolean = false,
    // المقدمة (بالثواني) — زر "تخطي المقدمة" في المشغّل
    @SerialName("intro_start") val introStart: Int? = null,
    @SerialName("intro_end") val introEnd: Int? = null,
    /** نهاية الحلقة (بداية شارة النهاية) بالثواني — لحلقة معيّنة */
    @SerialName("outro_start") val outroStart: Int? = null,
    /** مدة شارة النهاية بالثواني — للمسلسل كله (من آخر الحلقة) */
    @SerialName("outro_len") val outroLen: Int? = null,
    /** القسم (مسلسلات، أفلام، رياضة…) — يحدده الأب من اللوحة */
    @SerialName("category_id") val categoryId: String? = null,
    // تفاصيل المسلسل/الفيلم (من TMDB أو يدوي من اللوحة)
    val description: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    /** وسوم (خليجي، عائلي…) — صفوف داخل القسم */
    val tags: List<String> = emptyList(),
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("cast_list") val castList: List<CastMember> = emptyList(),
    /** رقم الموسم للحلقة (فاضي = الموسم 1) */
    val season: Int? = null,
    /** مسلسل "أتابعه": الحلقات الجديدة تتحمّل لحالها وينزل تنبيه */
    val follow: Boolean = false,
) {
    val seasonNo: Int get() = season ?: 1

    /** فيه تفاصيل تستاهل صفحة؟ */
    val hasDetails: Boolean get() = !description.isNullOrBlank() || castList.isNotEmpty() || !backdropUrl.isNullOrBlank()

    /** هل يُسمح بهذا الفيديو من هذي القائمة/القناة؟ (مو مخفي، وموافَق عليه لو الموافقة مفعّلة) */
    fun allows(videoId: String?): Boolean {
        if (videoId == null) return true
        if (videoId in excludedVideoIds) return false
        return !requireApproval || videoId in approvedVideoIds
    }
}

@Serializable
data class ChildProfile(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val avatar: String = "🧒",
    @SerialName("daily_limit_min") val dailyLimitMin: Int? = null,
    @SerialName("weekend_limit_min") val weekendLimitMin: Int? = null,
    val position: Int = 0,
    val pin: String? = null, // رمز أمان للطفل (اختياري)
    val theme: String? = null, // ثيم الطفل (null = ثيم الجهاز)
)

/** قناة بث مباشر (IPTV) اختارها الأب من اللوحة */
@Serializable
data class LiveChannel(
    val id: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("source_id") val sourceId: String? = null,
    val name: String,
    val logo: String? = null,
    @SerialName("stream_url") val streamUrl: String,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("epg_id") val epgId: String? = null,
    val position: Int = 0,
    val enabled: Boolean = true,
    @SerialName("profile_ids") val profileIds: List<String> = emptyList(),
)

/** مصدر IPTV (رابط M3U أو حساب Xtream) — ومنه "مكتبة الاشتراك" التلقائية */
@Serializable
data class IptvSource(
    val id: String,
    val kind: String = "m3u",
    val name: String = "",
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    /** اعرض الاشتراك كامل تلقائياً (قنوات + أفلام + مسلسلات) */
    @SerialName("auto_library") val autoLibrary: Boolean = true,
    /** تصنيفات أخفاها الأب: "live:12" "vod:5" "series:7" */
    @SerialName("hidden_cats") val hiddenCats: List<String> = emptyList(),
    /** عناصر أخفاها الأب: "live:123" "vod:456" "series:789" */
    @SerialName("hidden_items") val hiddenItems: List<String> = emptyList(),
    @SerialName("profile_ids") val profileIds: List<String> = emptyList(),
) {
    /** بيانات Xtream (من الحساب أو من رابط M3U فيه username و password) */
    val xtream: Triple<String, String, String>? get() {
        if (kind == "xtream" && !username.isNullOrBlank() && !password.isNullOrBlank()) return Triple(url.trimEnd('/'), username, password)
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val user = u.getQueryParameter("username") ?: return null
        val pass = u.getQueryParameter("password") ?: return null
        val path = (u.path ?: "").substringBeforeLast('/', "")
        return Triple("${u.scheme}://${u.encodedAuthority}$path", user, pass)
    }
}

/** ترتيب الحلقات: الموسم ثم الترتيب */
val EpisodeOrder: Comparator<ContentItem> = compareBy<ContentItem>({ it.seasonNo }, { it.position })

/** رقم الحلقة داخل موسمها (1، 2، 3…) */
fun List<ContentItem>.episodeNumber(index: Int): Int {
    val s = getOrNull(index)?.seasonNo ?: return index + 1
    return take(index + 1).count { it.seasonNo == s }
}

/** "الحلقة 3" أو "م2 · الحلقة 3" لو المسلسل فيه أكثر من موسم */
fun List<ContentItem>.episodeLabel(index: Int): String {
    val multi = map { it.seasonNo }.distinct().size > 1
    val n = episodeNumber(index)
    return if (multi) "م${getOrNull(index)?.seasonNo ?: 1} · الحلقة $n" else "الحلقة $n"
}

/** ممثل في مسلسل/فيلم */
@Serializable
data class CastMember(
    val id: Int? = null,
    val name: String = "",
    val character: String? = null,
    val photo: String? = null,
) {
    /** نفس الممثل؟ (بالرقم من TMDB، وإلا بالاسم) */
    fun same(o: CastMember) = if (id != null && o.id != null) id == o.id else name.trim() == o.name.trim()
}

/** قسم رئيسي (مسلسلات، أفلام، أطفال، رياضة…) — من لوحة التحكم */
@Serializable
data class Category(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val icon: String = "grid",
    val position: Int = 0,
)

@Serializable
data class PairingCode(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class CachedHome(
    val device: Device,
    val items: List<ContentItem>,
    val profiles: List<ChildProfile> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<LiveChannel> = emptyList(),
    val epgUrls: List<String> = emptyList(),
    val iptv: List<IptvSource> = emptyList(),
)

sealed interface TvState {
    data object Loading : TvState
    data class Error(val message: String) : TvState
    /** expiresAtMs = وقت تغيّر الكود (بساعة التلفزيون) — للعداد التنازلي */
    data class Pairing(val deviceCode: String, val code: String, val expiresAtMs: Long = 0L) : TvState
    /** offline = نعرض آخر نسخة محفوظة لأن الخادم غير متاح */
    data class Ready(
        val device: Device,
        val items: List<ContentItem>,
        val offline: Boolean = false,
        val profiles: List<ChildProfile> = emptyList(),
        val categories: List<Category> = emptyList(),
        val channels: List<LiveChannel> = emptyList(),
        val epgUrls: List<String> = emptyList(),
        val iptv: List<IptvSource> = emptyList(),
        /** نسخة محفوظة من آخر مرة (تنعرض فوراً وقت التشغيل لين يوصل الجديد من الخادم) */
        val stale: Boolean = false,
    ) : TvState {
        /** اشتراكات Xtream اللي تنعرض كاملة لهذا الملف الشخصي */
        fun libraries(profileId: String?): List<IptvSource> =
            iptv.filter { it.autoLibrary && (it.xtream != null || it.url.isNotBlank()) && (profileId == null || it.profileIds.isEmpty() || profileId in it.profileIds) }

        /** القنوات اللي تظهر لهذا الملف الشخصي */
        fun visibleChannels(profileId: String?): List<LiveChannel> =
            channels.filter { it.enabled && (profileId == null || it.profileIds.isEmpty() || profileId in it.profileIds) }

        fun profile(id: String?): ChildProfile? = profiles.firstOrNull { it.id == id }

        /** هل العنصر تعليمي؟ (فيديو داخل قائمة من "قوائمي" ياخذ حكم القائمة) */
        fun isEducational(itemId: String?): Boolean {
            val it = items.firstOrNull { x -> x.id == itemId } ?: return false
            if (it.educational) return true
            return it.parentId != null && items.firstOrNull { p -> p.id == it.parentId }?.educational == true
        }

        /** فلتر الكلمات: عنوان فيه كلمة محظورة؟ */
        fun titleBlocked(title: String?): Boolean {
            if (title.isNullOrBlank() || device.blockedWords.isEmpty()) return false
            val t = com.turkialawlqy.aman.ui.normalizeArabic(title)
            return device.blockedWords.any { w ->
                val n = com.turkialawlqy.aman.ui.normalizeArabic(w.trim())
                n.isNotEmpty() && n in t
            }
        }

        /** المحتوى اللي يشوفه هذا الطفل (فيديوهات قوائمي تتبع القائمة نفسها) */
        fun visibleItems(profileId: String?): List<ContentItem> {
            if (profiles.isEmpty() || profileId == null) return items
            val top = items
                .filter { it.parentId == null && (it.profileIds.isEmpty() || profileId in it.profileIds) }
                .map { it.id }.toSet()
            return items.filter { if (it.parentId == null) it.id in top else it.parentId in top }
        }
    }
}
