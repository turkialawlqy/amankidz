@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.turkialawlqy.aman.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.data.ChildProfile
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.IptvSource
import com.turkialawlqy.aman.data.LiveChannel
import com.turkialawlqy.aman.player.XtreamLibrary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * التصميم الجديد (مثل تطبيقات IPTV الحديثة): قائمة جانبية بالشعار، شريط علوي (بحث + إعدادات + الملف)،
 * وفي الرئيسية: بنر متحرك + "البث المباشر حالياً" + أحدث الأفلام + المسلسلات المضافة حديثاً.
 */
object Hub {
    /** اسم التطبيق (اقتراح — غيّره هنا بس) */
    const val BRAND = "سنا TV"
    const val TAGLINE = "Smart IPTV"

    val Bg = Color(0xFF0C111D)
    val Rail = Color(0xFF111827)
    val Card = Color(0xFF182033)
    val Card2 = Color(0xFF222C44)
    val Pink = Color(0xFFE23C7E)
    val Purple = Color(0xFF8B5CF6)
    val Dim = Color(0xFF98A2B8)
}

// ------------------------------------------------------------------ القائمة الجانبية

internal data class HubNav(val key: String, val label: String, val icon: String, val tint: Color, val onClick: () -> Unit)

@Composable
internal fun HubRail(nav: List<HubNav>, tab: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().width(250.dp).focusRestorer().background(Hub.Rail).padding(horizontal = 16.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.padding(start = 10.dp, bottom = 26.dp)) {
            Text(Hub.BRAND, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(Hub.TAGLINE, color = Hub.Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        nav.forEach { n -> HubRailItem(n, n.key == tab) }
    }
}

@Composable
private fun HubRailItem(n: HubNav, selected: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().height(50.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(when { focused -> Color.White; selected -> Hub.Card2; else -> Color.Transparent })
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = n.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // خط ملوّن على طرف المختار
        Box(Modifier.width(4.dp).height(26.dp).background(if (selected && !focused) Hub.Purple else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        NavIcon(n.icon, if (focused) Color(0xFF111111) else n.tint, 24.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            n.label, color = if (focused) Color(0xFF111111) else if (selected) Color.White else Color(0xFFD5DAE5),
            fontSize = 17.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ الشريط العلوي

@Composable
internal fun HubTopBar(
    profile: ChildProfile?,
    remainingLabel: String?,
    onSearch: (() -> Unit)?,
    onSettings: () -> Unit,
    onMine: (() -> Unit)?,
    onProfile: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 36.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSearch != null) HubSearchPill(onSearch)
        Spacer(Modifier.weight(1f))
        StatusArea(remainingLabel)
        Spacer(Modifier.width(14.dp))
        HubRoundIcon("settings", "الإعدادات", onSettings)
        if (onMine != null) { Spacer(Modifier.width(10.dp)); HubRoundIcon("folder", "قوائمي", onMine) }
        if (onProfile != null) {
            Spacer(Modifier.width(10.dp))
            if (profile != null) HubRoundText(profile.avatar, onProfile) else HubRoundIcon("profile", "الملف", onProfile)
        }
    }
}

@Composable
private fun HubSearchPill(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.width(380.dp).height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else Hub.Card)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon("search", if (focused) Color(0xFF111111) else Hub.Dim, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text("بحث", color = if (focused) Color(0xFF111111) else Hub.Dim, fontSize = 16.sp)
    }
}

@Composable
private fun HubRoundIcon(icon: String, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp).onFocusChanged { focused = it.isFocused }.clip(CircleShape)
            .background(if (focused) Color.White else Hub.Card)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { NavIcon(icon, if (focused) Color(0xFF111111) else Color.White, 22.dp) }
}

@Composable
private fun HubRoundText(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp).onFocusChanged { focused = it.isFocused }.clip(CircleShape)
            .background(if (focused) Color.White else Hub.Card)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp) }
}

// ------------------------------------------------------------------ الرئيسية

@Composable
internal fun HubHomePage(
    modifier: Modifier,
    libraries: List<IptvSource>,
    myChannels: List<LiveChannel>,
    heroFocus: FocusRequester,
    onDetail: (Pair<ContentItem, List<ContentItem>>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib = XtreamLibrary
    val favCh by com.turkialawlqy.aman.player.LiveStore.favorites.collectAsState()
    val recentCh by com.turkialawlqy.aman.player.LiveStore.recent.collectAsState()
    val movies by produceState<List<XtreamLibrary.Entry>?>(null, libraries) { value = runCatching { lib.latest(libraries, "vod") }.getOrDefault(emptyList()) }
    val series by produceState<List<XtreamLibrary.Entry>?>(null, libraries) { value = runCatching { lib.latest(libraries, "series") }.getOrDefault(emptyList()) }
    // قنوات الصف: المفضلة ← آخر القنوات ← قنواتك ← أول تصنيف من الاشتراك
    val liveRow by produceState<List<LiveChannel>?>(null, libraries, favCh, recentCh, myChannels) {
        value = favCh.ifEmpty { recentCh }.ifEmpty { myChannels }.ifEmpty {
            runCatching {
                val src = libraries.firstOrNull() ?: return@runCatching emptyList()
                val cat = lib.categories(listOf(src), "live").firstOrNull() ?: return@runCatching emptyList()
                lib.entries(src, cat).take(15).map { lib.channel(src, it, cat.name) }
            }.getOrDefault(emptyList())
        }.take(15)
    }
    val followKeys by com.turkialawlqy.aman.control.SeriesFollow.keys.collectAsState()
    val followed by produceState<List<XtreamLibrary.Entry>>(emptyList(), followKeys, libraries) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.turkialawlqy.aman.offline.LocalDb.follows() }.getOrDefault(emptyList())
        }.filter { f -> libraries.any { it.id == f.sourceId } }
            .map { f -> XtreamLibrary.Entry(f.sourceId, "series", f.seriesId, f.title, f.cover, "", null) }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    fun open(x: XtreamLibrary.Entry, type: String) {
        val src = libraries.firstOrNull { it.id == x.sourceId } ?: return
        if (busy != null) return
        busy = x.id
        scope.launch {
            try {
                if (type == "vod") onDetail(lib.movie(src, x) to emptyList())
                else { val (mix, eps) = lib.series(src, x); if (eps.isNotEmpty()) onDetail(mix to eps) }
            } finally { busy = null }
        }
    }
    val heroes = remember(series, movies) {
        (series.orEmpty().filter { it.backdrop != null } + series.orEmpty() + movies.orEmpty()).distinctBy { "${it.type}:${it.id}" }.take(6)
    }

    // البنر جاهز → التركيز على "شاهد الآن"
    LaunchedEffect(heroes.isNotEmpty()) {
        if (heroes.isNotEmpty() && BrowseMemory.lastPoster?.startsWith("hub:") != true) { delay(60); runCatching { heroFocus.requestFocus() } }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 28.dp, end = 36.dp, top = 8.dp, bottom = 48.dp)) {
        item("hero") {
            if (heroes.isNotEmpty()) HubHero(heroes, heroFocus, onPlay = { open(it, it.type) }, onInfo = { open(it, it.type) })
            else Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(16.dp)).background(Hub.Card), contentAlignment = Alignment.Center) {
                Text(if (movies == null) "يجيب من الاشتراك…" else (lib.lastError ?: "ما فيه محتوى بعد"), color = Hub.Dim, fontSize = 16.sp,
                    modifier = Modifier.focusRequester(heroFocus).clickable { })
            }
        }
        val lr = liveRow
        if (!lr.isNullOrEmpty()) item("live") {
            HubSection("البث المباشر حالياً")
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(lr, key = { _, c -> c.id }) { i, c ->
                    HubChannelCard(c, libraries) { com.turkialawlqy.aman.player.LivePlayerActivity.start(context, lr, i) }
                }
            }
        }
        if (followed.isNotEmpty()) item("follows") {
            HubSection("مسلسلات تتابعها")
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(followed, key = { _, x -> "f:${x.sourceId}:${x.id}" }) { _, x ->
                    HubPoster(x, "f", busy == x.id, libraries) { open(x, "series") }
                }
            }
        }
        if (movies == null) item("sk") { HubSection("أحدث الأفلام"); PosterSkeleton(columns = 7, rows = 1) }
        val mv = movies
        if (!mv.isNullOrEmpty()) item("movies") {
            HubSection("أحدث الأفلام")
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(mv, key = { _, x -> "${x.sourceId}:${x.id}" }) { _, x ->
                    HubPoster(x, "m", busy == x.id, libraries) { open(x, "vod") }
                }
            }
        }
        val sr = series
        if (!sr.isNullOrEmpty()) item("series") {
            HubSection("المسلسلات المضافة حديثاً")
            LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(sr, key = { _, x -> "${x.sourceId}:${x.id}" }) { _, x ->
                    HubPoster(x, "s", busy == x.id, libraries) { open(x, "series") }
                }
            }
        }
    }
}

/** بوستر في صفوف الرئيسية: يرجع التحديد عليه بعد صفحة التفاصيل، ويجهّز المسلسل لما يتحدد */
@Composable
private fun HubPoster(x: XtreamLibrary.Entry, row: String, busy: Boolean, libraries: List<IptvSource>, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pk = "hub:$row:${x.sourceId}:${x.id}"
    val back = remember { FocusRequester() }
    if (pk == BrowseMemory.lastPoster) LaunchedEffect(Unit) { delay(120); runCatching { back.requestFocus() } }
    Box(Modifier.width(132.dp)) {
        PosterTile(x.name, x.icon, x.year, loading = busy, modifier = Modifier.focusRequester(back),
            onFocus = if (x.type == "series") ({
                libraries.firstOrNull { it.id == x.sourceId }?.let { src -> scope.launch { XtreamLibrary.warmSeries(src, x.id) } }
            }) else null) {
            BrowseMemory.lastPoster = pk
            onOpen()
        }
    }
}

@Composable
private fun HubSection(title: String) {
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

/** البنر: يتغير كل 8 ثواني، والنقاط تحت */
@Composable
private fun HubHero(
    list: List<XtreamLibrary.Entry>,
    focus: FocusRequester,
    onPlay: (XtreamLibrary.Entry) -> Unit,
    onInfo: (XtreamLibrary.Entry) -> Unit,
) {
    var i by remember(list) { mutableStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(list, hasFocus) {
        if (hasFocus) return@LaunchedEffect // واقف على الأزرار: لا تقلب
        while (true) { delay(8000); i = (i + 1) % list.size }
    }
    val x = list[i.coerceIn(0, list.size - 1)]
    Box(
        Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(16.dp)).background(Hub.Card)
            .onFocusChanged { hasFocus = it.hasFocus },
    ) {
        coil3.compose.AsyncImage(
            model = x.backdrop ?: x.icon, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // طبقة غامقة جهة الكلام عشان ينقرى
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE60C111D), Color(0x990C111D), Color(0x000C111D)))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 30.dp).width(440.dp)) {
            Text(x.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(x.year?.toString(), if (x.type == "series") "مسلسل" else "فيلم", x.rating?.takeIf { it != "0" }?.let { "★ $it" }).joinToString(" · ")
            Text(sub, color = Color(0xFFD5DAE5), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            if (!x.plot.isNullOrBlank()) Text(x.plot, color = Color(0xFFC3C9D6), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HubPillButton("شاهد الآن", Hub.Pink, Modifier.focusRequester(focus)) { onPlay(x) }
                HubPillButton("المزيد من المعلومات", Color(0xFF4B5367)) { onInfo(x) }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.indices.forEach { k ->
                Box(Modifier.size(if (k == i) 18.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (k == i) Color.White else Color(0x80FFFFFF)))
            }
        }
    }
}

@Composable
private fun HubPillButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "pill")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) { Text(text, color = if (focused) Color(0xFF111111) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

/** بطاقة قناة: الشعار + البرنامج الحين + شريط تقدّمه */
@Composable
private fun HubChannelCard(c: LiveChannel, libraries: List<IptvSource>, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val guide by com.turkialawlqy.aman.player.Epg.guide.collectAsState()
    val now by produceState<XtreamLibrary.Program?>(null, c.id, guide) {
        val t = System.currentTimeMillis()
        val fromXml = c.epgId?.let { guide[it] }?.firstOrNull { t in it.start until it.end }
        value = if (fromXml != null) XtreamLibrary.Program(fromXml.start, fromXml.end, fromXml.title, null, false, "")
        else {
            val src = libraries.firstOrNull { it.id == c.sourceId }
            val sid = if (c.id.startsWith("xl:")) c.id.substringAfterLast(':') else null
            if (src?.xtream != null && sid != null && !sid.startsWith("m")) {
                delay(1500) // الدليل بعد ما تخلص البوسترات (ما يزاحمها على السيرفر)
                runCatching { XtreamLibrary.shortEpg(src, sid) }.getOrDefault(emptyList()).firstOrNull { t in it.start until it.end }
            }
            else null
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(170.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(Hub.Card)
            .then(if (focused) Modifier.border(3.dp, Hub.Purple, shape) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Hub.Card2), contentAlignment = Alignment.Center) {
            if (c.logo.isNullOrBlank()) Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            else coil3.compose.AsyncImage(model = c.logo, contentDescription = c.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(14.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(now?.title ?: c.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = now
            if (p != null) {
                val left = ((p.end - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                Text("باقي $left د", color = Hub.Dim, fontSize = 11.sp)
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    val f = ((System.currentTimeMillis() - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Hub.Pink))
                }
            } else Text("مباشر", color = Hub.Dim, fontSize = 11.sp)
        }
    }
}

// ------------------------------------------------------------------ المفضلة

@Composable
internal fun HubFavoritesPage(
    modifier: Modifier,
    items: List<ContentItem>,
    libraries: List<IptvSource>,
    firstFocus: FocusRequester,
    onDetail: (Pair<ContentItem, List<ContentItem>>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val followKeys by com.turkialawlqy.aman.control.SeriesFollow.keys.collectAsState()
    val followed by produceState<List<XtreamLibrary.Entry>>(emptyList(), followKeys, libraries) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.turkialawlqy.aman.offline.LocalDb.follows() }.getOrDefault(emptyList())
        }.filter { f -> libraries.any { it.id == f.sourceId } }
            .map { f -> XtreamLibrary.Entry(f.sourceId, "series", f.seriesId, f.title, f.cover, "", null) }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    val favCh by com.turkialawlqy.aman.player.LiveStore.favorites.collectAsState()
    val favVid by com.turkialawlqy.aman.player.FavoritesStore.items.collectAsState()
    LazyColumn(modifier, contentPadding = PaddingValues(start = 28.dp, end = 36.dp, top = 8.dp, bottom = 48.dp)) {
        item("t") {
            Text("المفضلة", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.focusRequester(firstFocus).clickable { })
            if (favCh.isEmpty() && favVid.isEmpty() && followed.isEmpty()) Text(
                "ما فيه شي بعد — وقت المشاهدة: ضغطة مطوّلة على OK تضيف القناة أو الفيديو للمفضلة",
                color = Hub.Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (followed.isNotEmpty()) item("follows") {
            HubSection("مسلسلات أتابعها")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(followed, key = { _, x -> "f:${x.sourceId}:${x.id}" }) { _, x ->
                    Box(Modifier.width(132.dp)) {
                        PosterTile(x.name, x.icon, null, loading = busy == x.id) {
                            val src = libraries.firstOrNull { it.id == x.sourceId } ?: return@PosterTile
                            if (busy != null) return@PosterTile
                            busy = x.id
                            scope.launch {
                                try { val (mix, eps) = XtreamLibrary.series(src, x); if (eps.isNotEmpty()) onDetail(mix to eps) }
                                finally { busy = null }
                            }
                        }
                    }
                }
            }
        }
        if (favCh.isNotEmpty()) item("ch") {
            HubSection("قنوات")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(favCh, key = { _, c -> c.id }) { i, c ->
                    HubChannelCard(c, emptyList()) { com.turkialawlqy.aman.player.LivePlayerActivity.start(context, favCh, i) }
                }
            }
        }
        if (favVid.isNotEmpty()) item("v") {
            HubSection("أفلام وفيديوهات")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(favVid, key = { _, m -> m.videoId }) { i, m ->
                    Box(Modifier.width(240.dp)) {
                        FocusTile(title = m.title, imageUrl = m.thumb, width = 240.dp,
                            onClick = { playWithEpisodes(context, items, m.url, m.videoId, m.sourceId) })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ جدول البرامج

@Composable
internal fun HubGuidePage(modifier: Modifier, libraries: List<IptvSource>, myChannels: List<LiveChannel>, firstFocus: FocusRequester) {
    val context = LocalContext.current
    val favCh by com.turkialawlqy.aman.player.LiveStore.favorites.collectAsState()
    val recentCh by com.turkialawlqy.aman.player.LiveStore.recent.collectAsState()
    val chans by produceState<List<LiveChannel>?>(null, libraries, favCh, recentCh, myChannels) {
        val base = (favCh + recentCh + myChannels).distinctBy { it.id }
        value = base.ifEmpty {
            runCatching {
                val src = libraries.firstOrNull() ?: return@runCatching emptyList()
                val cat = XtreamLibrary.categories(listOf(src), "live").firstOrNull() ?: return@runCatching emptyList()
                XtreamLibrary.entries(src, cat).take(30).map { XtreamLibrary.channel(src, it, cat.name) }
            }.getOrDefault(emptyList())
        }.take(40)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 28.dp, end = 36.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("t") {
            Text("جدول البرامج", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("المفضلة وآخر القنوات أول. وقت المشاهدة: يسار يفتح دليل القناة كامل والأرشيف", color = Hub.Dim, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        val list = chans
        if (list == null) item("l") { Text("يجيب القنوات…", color = Hub.Dim, fontSize = 16.sp) }
        else if (list.isEmpty()) item("e") { Text("ما فيه قنوات", color = Hub.Dim, fontSize = 16.sp) }
        else itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
            HubGuideRow(c, libraries, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                com.turkialawlqy.aman.player.LivePlayerActivity.start(context, list, i)
            }
        }
    }
}

@Composable
private fun HubGuideRow(c: LiveChannel, libraries: List<IptvSource>, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val guide by com.turkialawlqy.aman.player.Epg.guide.collectAsState()
    val progs by produceState<List<XtreamLibrary.Program>>(emptyList(), c.id, guide) {
        val t = System.currentTimeMillis()
        val xml = c.epgId?.let { guide[it] }.orEmpty().filter { it.end > t }.take(2)
            .map { XtreamLibrary.Program(it.start, it.end, it.title, null, false, "") }
        value = xml.ifEmpty {
            val src = libraries.firstOrNull { it.id == c.sourceId }
            val sid = if (c.id.startsWith("xl:")) c.id.substringAfterLast(':') else null
            if (src?.xtream != null && sid != null && !sid.startsWith("m"))
                runCatching { XtreamLibrary.shortEpg(src, sid) }.getOrDefault(emptyList()) else emptyList()
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("h:mm", java.util.Locale.US) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().height(76.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (focused) Hub.Card2 else Hub.Card)
            .then(if (focused) Modifier.border(2.dp, Hub.Purple, shape) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(Hub.Card2), contentAlignment = Alignment.Center) {
            if (!c.logo.isNullOrBlank()) coil3.compose.AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp))
        Spacer(Modifier.width(14.dp))
        val now = progs.firstOrNull()
        Column(Modifier.weight(1f)) {
            Text(now?.let { "الحين: ${it.title}" } ?: "ما فيه دليل", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    val f = ((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Hub.Pink))
                }
            }
        }
        progs.getOrNull(1)?.let { n ->
            Spacer(Modifier.width(14.dp))
            Text("${fmt.format(java.util.Date(n.start))} · ${n.title}", color = Hub.Dim, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
        }
    }
}


/**
 * تمرير مثل تطبيقات التلفزيون: العنصر المحدد يثبت قريب من بداية الصف/الصفحة بدل ما يزحف للطرف،
 * فالحركة بالريموت أوضح وأسرع.
 */
internal val TvScrollSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = containerSize * 0.08f
        // العنصر ظاهر كامل وقريب من المكان المطلوب → لا تحرك (يمنع الاهتزاز)
        if (offset >= 0 && offset + size <= containerSize && kotlin.math.abs(offset - pivot) < size * 0.5f) return 0f
        return offset - pivot
    }
}
