package com.turkialawlqy.aman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.BuildConfig
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.Device
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.player.PlayerActivity
import com.turkialawlqy.aman.yt.CollectionPager
import com.turkialawlqy.aman.yt.VideoEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.turkialawlqy.aman.player.ResumeStore
import com.turkialawlqy.aman.yt.CollectionCache

// ------------------------------------------------------------------ حالات عامة

@Composable
fun LoadingScreen(text: String = "جاري التحميل…") {
    Column(
        Modifier.fillMaxSize().screenBackground(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(72.dp)
        Spacer(Modifier.height(18.dp))
        Text("أمان كيدز", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(14.dp))
        Text(text, color = TextDim, fontSize = 18.sp)
    }
}

/** شاشة رسالة (قفل، خلص الوقت، خطأ…): أول رمز في العنوان يصير أيقونة كبيرة */
@Composable
fun MessageScreen(title: String, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    val firstToken = title.substringBefore(' ')
    val hasIcon = firstToken.isNotEmpty() && firstToken.none { it.isLetterOrDigit() }
    val icon = if (hasIcon) firstToken else null
    val cleanTitle = if (hasIcon) title.substringAfter(' ') else title
    Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(48.dp)
                .widthIn(max = 720.dp)
                .glass(36.dp)
                .padding(horizontal = 60.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(cleanTitle, color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextDim, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 30.sp)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(30.dp))
                FocusButton(actionLabel, onAction, Modifier.focusRequester(focus), fontSize = 20, primary = true)
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}

// ------------------------------------------------------------------ شاشة الربط

@Composable
fun PairingScreen(deviceCode: String, code: String, expiresAtMs: Long = 0L) {
    val ltr = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
    // العداد التنازلي لتغيّر الكود
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((expiresAtMs - now) / 1000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxSize().screenBackground().padding(horizontal = 56.dp, vertical = 40.dp).focusable(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BrandMark(64.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "أهلاً بك في أمان كيدز", color = TextMain, fontSize = 38.sp, lineHeight = 54.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 2,
            )
            Spacer(Modifier.height(18.dp))
            Text("١. افتح من جوالك:", color = TextDim, fontSize = 20.sp, lineHeight = 30.sp)
            val site = BuildConfig.PARENT_SITE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
            Text(
                site.ifBlank { "لوحة تحكم الأهل" }, color = Accent, fontSize = 22.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = ltr,
            )
            Spacer(Modifier.height(10.dp))
            Text("٢. صفحة الأجهزة ربط تلفزيون جديد", color = TextDim, fontSize = 20.sp, lineHeight = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text("٣. اكتب الكود اللي جنبك", color = TextDim, fontSize = 20.sp, lineHeight = 30.sp)
            Spacer(Modifier.height(28.dp))
            Text("معرّف الجهاز", color = TextDim, fontSize = 14.sp)
            Text(deviceCode, color = TextDim, fontSize = 15.sp, maxLines = 1, softWrap = false, style = ltr)
        }
        Column(
            Modifier.weight(1.15f).glass(36.dp).padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("كود الربط", color = TextDim, fontSize = 18.sp)
            Spacer(Modifier.height(18.dp))
            // الكود لازم ينعرض من اليسار لليمين حتى لو التطبيق كله RTL
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    code.forEach { ch ->
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .widthIn(min = 56.dp)
                                .background(Surface2, RoundedCornerShape(18.dp)).border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch.toString(), color = Accent, fontSize = 50.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (expiresAtMs > 0) {
                Text(
                    if (left > 0) "يتغير الكود بعد %d:%02d".format(left / 60, left % 60) else "يجيب كود جديد…",
                    color = if (left in 1..60) Accent else TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (left / 585f).coerceIn(0f, 1f) },
                    modifier = Modifier.width(260.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Accent, trackColor = Surface2,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text("حروف إنجليزية كبيرة وأرقام", color = TextDim, fontSize = 15.sp)
        }
    }
}

// ------------------------------------------------------------------ قائمة تشغيل / قناة

@Composable
fun CollectionScreen(
    item: ContentItem,
    children: List<ContentItem> = emptyList(),
    onOpenActor: (com.turkialawlqy.aman.data.CastMember) -> Unit = {},
) {
    // المسلسل (قائمة من الأب) أو فيلم له تفاصيل → صفحة التفاصيل
    if (item.kind == "mix" || item.kind == "video") {
        if (item.kind == "mix" && children.isEmpty()) {
            MessageScreen(item.title, "القائمة فاضية.\nأضف لها فيديوهات من لوحة الأهل.")
            return
        }
        // مسلسل من الاشتراك انضاف لقوائمك: المواسم من الاشتراك نفسه (لو انحفظت كلها موسم واحد)
        val linked = item.kind == "mix" && (item.url.startsWith("xtseries:") || item.youtubeId.startsWith("xts_"))
        val fixedKids by androidx.compose.runtime.produceState(children, item.id, children) {
            if (!linked || children.map { it.seasonNo }.distinct().size > 1) { value = children; return@produceState }
            value = runCatching { withSeasonsFromLibrary(item, children) }.getOrDefault(children)
        }
        DetailsScreen(item, fixedKids, onOpenActor)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = remember(item.id) { CollectionPager(item.kind, item.url) }
    // نبدأ بالنسخة المحلية (تفتح فوراً وتشتغل بدون إنترنت)، ثم نحدّثها من يوتيوب
    val cached = remember(item.id) { CollectionCache.get(item.id)?.items.orEmpty() }
    val videos = remember(item.id) { mutableStateListOf<VideoEntry>().apply { addAll(cached) } }
    var fromCache by remember(item.id) { mutableStateOf(cached.isNotEmpty()) }
    var loading by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var offlineOnly by remember(item.id) { mutableStateOf(false) }
    var notice by remember(item.id) { mutableStateOf<String?>(null) }
    val savedIds by OfflineStore.completeIds.collectAsState()
    val progress by ResumeStore.progress.collectAsState()
    val dl by OfflineDownloader.downloading.collectAsState()
    val q by OfflineDownloader.queued.collectAsState()
    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }

    // الفيديوهات اللي أخفاها الأب ما تظهر
    // + لو الموافقة مفعّلة: الجديد ما يظهر لين يوافق الأب
    val ready = com.turkialawlqy.aman.data.TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready
    val visible = videos.filter { v ->
        item.allows(OfflineDownloader.videoIdOf(v.url)) && ready?.titleBlocked(v.title) != true // + فلتر الكلمات
    }
    fun isSaved(v: VideoEntry) = OfflineDownloader.videoIdOf(v.url)?.let { it in savedIds } == true

    fun loadMore() {
        if (loading || offlineOnly || !pager.hasMore) return
        loading = true
        scope.launch {
            try {
                val page = withTimeout(20_000) { pager.loadNext() }
                if (fromCache) {
                    // أول صفحة من يوتيوب تستبدل النسخة المحلية
                    fromCache = false
                    videos.clear()
                }
                videos += page
                error = null
                CollectionCache.put(item.id, videos.toList())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                offlineOnly = true
                if (videos.isEmpty()) {
                    // ما فيه نسخة محلية: نعرض المحفوظ من هذي القائمة/القناة
                    val saved = OfflineStore.entries().filter { it.complete && item.id in it.sources }
                    if (saved.isNotEmpty()) {
                        videos += saved.map {
                            VideoEntry("https://www.youtube.com/watch?v=${it.videoId}", it.title, it.thumbnail, it.durationSec, null)
                        }
                    } else {
                        offlineOnly = false
                        error = "تعذر تحميل المحتوى. تأكد من الإنترنت."
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(item.id) { loadMore() }

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= videos.size - 8
        }
    }
    LaunchedEffect(nearEnd, videos.size) { if (nearEnd && videos.isNotEmpty() && !fromCache) loadMore() }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); notice = null } }

    when {
        error != null -> MessageScreen(item.title, error!!, "إعادة المحاولة") { error = null; loadMore() }
        visible.isEmpty() && !offlineOnly && (loading || pager.hasMore) -> LoadingScreen()
        visible.isEmpty() -> MessageScreen(item.title, "لا توجد فيديوهات هنا")
        else -> Column(Modifier.fillMaxSize().screenBackground()) {
            CollectionHeader(
                title = item.title,
                imageUrl = item.thumbnailUrl,
                circle = item.kind == "channel",
                info = "${visible.size} فيديو" + if (visible.any { isSaved(it) }) " · ${visible.count { isSaved(it) }} محفوظ" else "",
                notice = notice ?: if (offlineOnly) "بدون إنترنت — المحفوظ فقط يشتغل" else null,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 56.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                itemsIndexed(visible, key = { i, v -> "$i-${v.url}" }) { idx, v ->
                    val saved = isSaved(v)
                    val dim = offlineOnly && !saved
                    FocusTile(
                        title = v.title,
                        imageUrl = v.thumbnail,
                        width = 260.dp,
                        subtitle = if (dim) "يحتاج إنترنت" else formatDuration(v.durationSec),
                        offlineBadge = if (saved) OFFLINE_BADGE else null,
                        progress = OfflineDownloader.videoIdOf(v.url)?.let { progress[it] },
                        downloadPercent = OfflineDownloader.videoIdOf(v.url)?.let { dl[it] },
                        queued = OfflineDownloader.videoIdOf(v.url)?.let { it in q } == true,
                        dimmed = dim,
                        modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = {
                            if (dim) {
                                notice = "هذا الفيديو غير محفوظ ويحتاج إنترنت"
                            } else {
                                // بدون إنترنت نشغّل المحفوظ فقط وبالترتيب
                                val playable = if (offlineOnly) visible.filter { isSaved(it) } else visible
                                PlayerActivity.start(
                                    context, playable.map { it.url }, playable.indexOf(v).coerceAtLeast(0),
                                    sourceItemId = item.id,
                                )
                            }
                        },
                    )
                }
            }
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        }
    }
}

// ------------------------------------------------------------------ قائمة خاصة صنعها الأب

@Composable
private fun MixScreen(item: ContentItem, children: List<ContentItem>) {
    val context = LocalContext.current
    val savedIds by OfflineStore.completeIds.collectAsState()
    val mixProgress by ResumeStore.progress.collectAsState()
    val dl by OfflineDownloader.downloading.collectAsState()
    val q by OfflineDownloader.queued.collectAsState()
    val firstFocus = remember { FocusRequester() }

    if (children.isEmpty()) {
        MessageScreen(item.title, "القائمة فاضية.\nأضف لها فيديوهات من لوحة الأهل.")
        return
    }
    Column(Modifier.fillMaxSize().screenBackground()) {
        CollectionHeader(
            title = item.title,
            imageUrl = item.thumbnailUrl ?: children.firstOrNull()?.thumbnailUrl,
            circle = false,
            info = "${children.size} فيديو · قائمة من ولي الأمر",
            notice = null,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(children, key = { _, v -> v.id }) { idx, v ->
                FocusTile(
                    title = v.title,
                    imageUrl = v.thumbnailUrl,
                    width = 260.dp,
                    offlineBadge = if (v.youtubeId in savedIds) OFFLINE_BADGE else null,
                    progress = mixProgress[v.youtubeId],
                    downloadPercent = dl[v.youtubeId],
                    queued = v.youtubeId in q,
                    modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = {
                        // تشغيل القائمة بالترتيب من الفيديو المختار
                        PlayerActivity.start(
                            context,
                            urls = children.map { it.url },
                            index = idx,
                            sourceItemId = item.id,
                            itemIds = children.map { it.id },
                        )
                    },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

/** رأس صفحة القائمة/القناة: صورة + عنوان + معلومات */
@Composable
private fun CollectionHeader(title: String, imageUrl: String?, circle: Boolean, info: String, notice: String?) {
    Row(
        Modifier.padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (circle) CircleShape else RoundedCornerShape(16.dp)
        coil3.compose.AsyncImage(
            model = imageUrl, contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.height(84.dp).width(if (circle) 84.dp else 150.dp).clip(shape).background(Surface).border(1.dp, GlassBorder, shape),
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(info, color = TextDim, fontSize = 16.sp)
        }
        if (notice != null) InfoChip(notice, color = OnAccent, background = Accent)
    }
}


/**
 * تشغيل فيديو مع باقي حلقات مسلسله (عشان أزرار التالية/السابقة تشتغل)
 * — من "تابع المشاهدة" أو البحث. لو مو حلقة بمسلسل يشغّله لحاله.
 */
internal fun playWithEpisodes(
    context: android.content.Context,
    items: List<ContentItem>,
    url: String,
    videoId: String?,
    sourceId: String?,
) {
    val inMix = items.filter { it.parentId != null && ((videoId != null && it.youtubeId == videoId) || it.url == url) }
    val item = inMix.firstOrNull { it.parentId == sourceId } ?: inMix.firstOrNull()
    val parentId = item?.parentId
    if (item != null && parentId != null) {
        val kids = items.filter { it.parentId == parentId }.sortedWith(com.turkialawlqy.aman.data.EpisodeOrder)
        val i = kids.indexOfFirst { it.id == item.id }
        if (i >= 0) {
            PlayerActivity.start(context, kids.map { it.url }, i, sourceItemId = parentId, itemIds = kids.map { it.id })
            return
        }
    }
    PlayerActivity.start(context, listOf(url), 0, sourceItemId = sourceId)
}


/** يجيب مواسم المسلسل من الاشتراك (قاعدة البيانات المحلية/السيرفر مباشرة) ويطبقها على حلقات القائمة برقم الحلقة */
private suspend fun withSeasonsFromLibrary(mix: ContentItem, kids: List<ContentItem>): List<ContentItem> {
    val ready = com.turkialawlqy.aman.data.TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready ?: return kids
    val sources = ready.iptv
    val mixId = Regex("^xtseries:([0-9a-f-]{36}):(.+)$").find(mix.url)?.groupValues?.let { "xs:${it[1]}:${it[2]}" }
        ?: Regex("^xts_([0-9a-f]{6})_(.+)$").find(mix.youtubeId)?.groupValues?.let { g ->
            sources.firstOrNull { it.id.replace("-", "").take(6) == g[1] }?.let { "xs:${it.id}:${g[2]}" }
        } ?: return kids
    val eps = com.turkialawlqy.aman.player.XtreamLibrary.seriesByMixId(sources, mixId)?.second ?: return kids
    if (eps.map { it.seasonNo }.distinct().size <= 1) return kids
    // رقم الحلقة في الاشتراك = آخر رقم في رابطها …/series/u/p/<id>.mkv
    fun epId(url: String) = Regex("/series/[^/]+/[^/]+/([^/.]+)\\.\\w+$").find(url.substringAfter('@'))?.groupValues?.get(1)
    val seasonById = eps.mapNotNull { e -> epId(e.url)?.let { it to e.season } }.toMap()
    var hit = 0
    val out = kids.map { k ->
        val id = epId(k.url)
        if (id != null && seasonById.containsKey(id)) { hit++; k.copy(season = seasonById[id]) } else k
    }
    return if (hit > 0) out.sortedWith(com.turkialawlqy.aman.data.EpisodeOrder) else kids
}
