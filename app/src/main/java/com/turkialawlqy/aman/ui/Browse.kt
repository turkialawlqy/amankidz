@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.turkialawlqy.aman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.data.Category
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import com.turkialawlqy.aman.data.ChildProfile
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.Device
import com.turkialawlqy.aman.data.episodeLabel
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.player.PlayerActivity
import com.turkialawlqy.aman.player.ResumeStore
import com.turkialawlqy.aman.player.VideoMeta

/*
 * الصفحة الرئيسية بأسلوب منصات البث — تصميم مسطّح (ألوان سادة، بدون تدرّجات ولا ظلال).
 *  - ثيم "شاهد": قائمة جانبية (يمين) بأيقونات تتوسع لما تتحدد
 *  - ثيم "نتفلكس": تبويبات فوق
 * الأقسام (مسلسلات، أفلام، رياضة…) يضيفها الأب من اللوحة، وكل قسم صفحة.
 */

/** العنصر المعروض كبير أعلى الصفحة */
private data class Featured(
    val tag: String,
    val title: String,
    val image: String?,
    val meta: List<String> = emptyList(),
    val progress: Float? = null,
    val description: String? = null,
    val primary: String,
    val onPrimary: () -> Unit,
    val secondary: String? = null,
    val onSecondary: (() -> Unit)? = null,
)

/** عنصر في القائمة (قسم أو إجراء) */
private data class NavEntry(val key: String, val label: String, val icon: String, val onClick: () -> Unit)

/** مكان المستخدم في الصفحة (القسم، تبويب الاشتراك، التصنيف، آخر فيلم/مسلسل فتحه) — يرجع له بعد صفحة التفاصيل أو المشغّل */
internal object BrowseMemory {
    var tab: String? = null
    var libType: String = "vod"
    val libSelected = HashMap<String, String?>()
    var lastPoster: String? = null
    var libSearch: String? = null
    var loadedCat: String? = null
}

private const val HOME = "home"
private const val LIVE = "live"
private const val XLIB = "xlib"
private const val VOD = "vod"
private const val SERIES = "series"
private const val FAV = "fav"
private const val EPG = "epg"
private const val MINE = "mine"
private const val SEARCH = "search"

// ------------------------------------------------------------------ مكتبة الاشتراك (Xtream): قنوات / أفلام / مسلسلات
// يمين: التصنيفات — يسار: المحتوى. كله من الاشتراك مباشرة، والأب يخفي اللي ما يبغاه من اللوحة.

@Composable
private fun LibraryPage(
    modifier: Modifier,
    libraries: List<com.turkialawlqy.aman.data.IptvSource>,
    myChannels: List<com.turkialawlqy.aman.data.LiveChannel>,
    firstFocus: FocusRequester,
    startSearch: Boolean = false,
    onDetail: (Pair<ContentItem, List<ContentItem>>) -> Unit,
) {
    var type by remember { mutableStateOf(BrowseMemory.libType) }
    var searching by remember { mutableStateOf(startSearch || BrowseMemory.libSearch != null) }
    LaunchedEffect(type) { BrowseMemory.libType = type }
    Column(modifier) {
        Row(Modifier.padding(start = PAGE_PAD, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("vod" to "أفلام", "series" to "مسلسلات", "live" to "قنوات مباشرة").forEach { (k, label) ->
                FocusButton(label, { type = k; searching = false; BrowseMemory.libSearch = null; BrowseMemory.lastPoster = null },
                    if (k == type && !searching) Modifier.focusRequester(firstFocus) else Modifier,
                    selected = type == k, fontSize = 16)
            }
            FocusButton("بحث", { searching = true; if (BrowseMemory.libSearch == null) BrowseMemory.libSearch = "" },
                if (searching) Modifier.focusRequester(firstFocus) else Modifier, selected = searching, fontSize = 16)
        }
        if (searching) LibrarySearch(Modifier.weight(1f), type, libraries, onDetail)
        else LibraryTypePage(Modifier.weight(1f), type, libraries, myChannels, remember { FocusRequester() }, onDetail = onDetail)
    }
}

/** بحث في الاشتراك (النوع المختار: أفلام / مسلسلات / قنوات) */
@Composable
internal fun LibrarySearch(
    modifier: Modifier,
    type: String,
    libraries: List<com.turkialawlqy.aman.data.IptvSource>,
    onDetail: (Pair<ContentItem, List<ContentItem>>) -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lib = com.turkialawlqy.aman.player.XtreamLibrary
    var query by remember { mutableStateOf(BrowseMemory.libSearch.orEmpty()) }
    var results by remember(type) { mutableStateOf<List<com.turkialawlqy.aman.player.XtreamLibrary.Entry>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isBlank()) runCatching { field.requestFocus() } }
    LaunchedEffect(query, type) {
        BrowseMemory.libSearch = query
        if (query.trim().length < 2) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(600) // ينتظر يخلص كتابة
        searching = true
        results = runCatching { lib.search(libraries, type, query) }.getOrDefault(emptyList())
        searching = false
    }
    val typeName = when (type) { "vod" -> "الأفلام"; "series" -> "المسلسلات"; else -> "القنوات" }
    Column(modifier.padding(horizontal = PAGE_PAD).padding(top = 14.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(60) },
            singleLine = true,
            placeholder = { Text("ابحث في $typeName…", color = TextDim) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                focusedBorderColor = Accent, unfocusedBorderColor = Surface2, cursorColor = Accent,
            ),
            modifier = Modifier.fillMaxWidth(0.6f).focusRequester(field),
        )
        val r = results
        when {
            query.trim().length < 2 -> Text("اكتب حرفين على الأقل — البحث يشمل كل تصنيفات $typeName في الاشتراك",
                color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            searching || r == null -> Text("يدوّر… (أول بحث ياخذ شوي)", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            r.isEmpty() -> Text("ما لقينا شي باسم \"$query\"", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
            type == "live" -> {
                val chs = r.mapNotNull { e -> libraries.firstOrNull { it.id == e.sourceId }?.let { lib.channel(it, e, "نتائج البحث") } }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(190.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    gridItemsIndexed(chs, key = { _, c -> c.id }) { i, c ->
                        ChannelTile(c, i + 1, null, Modifier) { com.turkialawlqy.aman.player.LivePlayerActivity.start(context, chs, i) }
                    }
                }
            }
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                gridItems(r, key = { "${it.sourceId}:${it.id}" }) { x ->
                    val pk = "search:$type:${x.sourceId}:${x.id}"
                    val back = remember { FocusRequester() }
                    if (pk == BrowseMemory.lastPoster) LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { back.requestFocus() } }
                    PosterTile(x.name, x.icon, x.year, loading = busy == x.id, modifier = Modifier.focusRequester(back)) {
                        val src = libraries.firstOrNull { it.id == x.sourceId } ?: return@PosterTile
                        BrowseMemory.lastPoster = pk
                        busy = x.id
                        scope.launch {
                            try {
                                if (type == "vod") onDetail(lib.movie(src, x) to emptyList())
                                else { val (mix, eps) = lib.series(src, x); if (eps.isNotEmpty()) onDetail(mix to eps) }
                            } finally { busy = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryTypePage(
    modifier: Modifier,
    type: String,
    libraries: List<com.turkialawlqy.aman.data.IptvSource>,
    myChannels: List<com.turkialawlqy.aman.data.LiveChannel>,
    firstFocus: FocusRequester,
    /** أفلامك ومسلسلاتك من «قوائمي» (تنضم لصفحة الأفلام/المسلسلات) */
    myItems: List<ContentItem> = emptyList(),
    onOpenMine: (ContentItem) -> Unit = {},
    onDetail: (Pair<ContentItem, List<ContentItem>>) -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lib = com.turkialawlqy.aman.player.XtreamLibrary
    val MYLIST = "__mylist__"
    val MINE = "__mine__"
    val FAV = "__fav__"
    val RECENT = "__recent__"
    val FOLLOWS = "__follows__"
    val followKeys by com.turkialawlqy.aman.control.SeriesFollow.keys.collectAsState()
    val favCh by com.turkialawlqy.aman.player.LiveStore.favorites.collectAsState()
    val recentCh by com.turkialawlqy.aman.player.LiveStore.recent.collectAsState()
    LaunchedEffect(Unit) { com.turkialawlqy.aman.player.LiveStore.refresh() }
    var cats by remember(type, libraries) { mutableStateOf<List<com.turkialawlqy.aman.player.XtreamLibrary.Cat>?>(null) }
    var selected by remember(type) { mutableStateOf(BrowseMemory.libSelected[type]) } // sourceId:catId
    LaunchedEffect(type, selected) { BrowseMemory.libSelected[type] = selected }
    var entries by remember(type) { mutableStateOf<List<com.turkialawlqy.aman.player.XtreamLibrary.Entry>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val guide by com.turkialawlqy.aman.player.Epg.guide.collectAsState()

    LaunchedEffect(type, libraries) {
        // "الكل" أول (كل الأفلام / كل المسلسلات / كل القنوات) — يعرض ويدوّر في كل الاشتراك
        cats = libraries.filter { it.xtream != null }.map {
            com.turkialawlqy.aman.player.XtreamLibrary.Cat(it.id, type, com.turkialawlqy.aman.player.XtreamLibrary.ALL_CAT,
                if (libraries.size > 1) "الكل · ${it.name}" else "الكل")
        } + lib.categories(libraries, type)
        // التصنيف المحفوظ انحذف/انخفى → نبدأ من الأول
        if (selected != null && selected != MINE && selected != FAV && selected != RECENT && selected != FOLLOWS && selected != MYLIST && cats?.none { "${it.sourceId}:${it.id}" == selected } == true) selected = null
        if (selected == FAV || selected == RECENT || selected == FOLLOWS || selected == MYLIST) { /* تبقى */ }
        else if (selected == null) selected = if (type == "live" && com.turkialawlqy.aman.player.LiveStore.favorites.value.isNotEmpty()) FAV
            else if (type == "live" && myChannels.isNotEmpty()) MINE
            else (cats?.firstOrNull { it.id != com.turkialawlqy.aman.player.XtreamLibrary.ALL_CAT } ?: cats?.firstOrNull())?.let { "${it.sourceId}:${it.id}" }
    }
    // يعيد الجلب لما تجي التصنيفات بعد (الرجوع من صفحة المسلسل: التصنيف محفوظ بس التصنيفات لسا تنجاب)
    LaunchedEffect(selected, cats, followKeys) {
        val sel = selected ?: return@LaunchedEffect
        if (sel == MINE || sel == FAV || sel == RECENT || sel == MYLIST) { entries = null; return@LaunchedEffect }
        if (sel == FOLLOWS) {
            // المسلسلات اللي تتابعها (من قاعدة البيانات المحلية)
            entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.turkialawlqy.aman.offline.LocalDb.follows() }.getOrDefault(emptyList())
            }.filter { f -> libraries.any { it.id == f.sourceId } }
                .map { f -> com.turkialawlqy.aman.player.XtreamLibrary.Entry(f.sourceId, "series", f.seriesId, f.title, f.cover, "", null) }
            return@LaunchedEffect
        }
        val c = cats?.firstOrNull { "${it.sourceId}:${it.id}" == sel } ?: return@LaunchedEffect
        if (entries == null || BrowseMemory.loadedCat != "$type|$sel") entries = null
        BrowseMemory.loadedCat = "$type|$sel"
        val src = libraries.firstOrNull { it.id == c.sourceId } ?: return@LaunchedEffect
        entries = lib.entries(src, c)
    }
    val catName = when (selected) {
        FAV -> "القنوات المفضلة"
        FOLLOWS -> "مسلسلات أتابعها"
        MYLIST -> if (type == "series") "مسلسلاتي" else "أفلامي"
        RECENT -> "آخر القنوات"
        else -> cats?.firstOrNull { "${it.sourceId}:${it.id}" == selected }?.name ?: "قنواتي"
    }
    val liveList: List<com.turkialawlqy.aman.data.LiveChannel> = remember(entries, selected, favCh, recentCh) {
        if (type != "live") emptyList()
        else if (selected == MINE) myChannels
        else if (selected == FAV) favCh
        else if (selected == RECENT) recentCh
        else entries.orEmpty().mapNotNull { e -> libraries.firstOrNull { it.id == e.sourceId }?.let { lib.channel(it, e, catName) } }
    }

    Row(modifier.padding(top = 12.dp)) {
        // التصنيفات
        LazyColumn(
            Modifier.width(260.dp).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(start = PAGE_PAD, end = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item("title") {
                Text("التصنيفات", color = TextDim, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (myItems.isNotEmpty() && type != "live") item(MYLIST) {
                CatRow(if (type == "series") "★ مسلسلاتي" else "★ أفلامي", selected == MYLIST, Modifier) { selected = MYLIST }
            }
            if (type == "series" && followKeys.isNotEmpty()) item(FOLLOWS) {
                CatRow("★ أتابعها", selected == FOLLOWS, Modifier) { selected = FOLLOWS }
            }
            if (type == "live" && favCh.isNotEmpty()) item(FAV) {
                CatRow("★ المفضلة", selected == FAV, Modifier) { selected = FAV }
            }
            if (type == "live" && recentCh.isNotEmpty()) item(RECENT) {
                CatRow("آخر القنوات", selected == RECENT, Modifier) { selected = RECENT }
            }
            if (type == "live" && myChannels.isNotEmpty()) item(MINE) {
                CatRow("قنواتي", selected == MINE, Modifier.focusRequester(firstFocus)) { selected = MINE }
            }
            val list = cats
            if (list == null) item("loading") { Text("يجيب التصنيفات…", color = TextDim, fontSize = 15.sp) }
            else if (list.isEmpty() && !(type == "live" && myChannels.isNotEmpty())) item("none") {
                Text(lib.lastError?.let { "تعذر جلب الاشتراك:\n$it" } ?: "ما فيه شي هنا", color = TextDim, fontSize = 14.sp)
            }
            else itemsIndexed(list, key = { _, c -> "${c.sourceId}:${c.id}" }) { i, c ->
                val k = "${c.sourceId}:${c.id}"
                CatRow(c.name, selected == k,
                    if (i == 0 && !(type == "live" && myChannels.isNotEmpty())) Modifier.focusRequester(firstFocus) else Modifier) { selected = k }
            }
        }
        // المحتوى
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val e = entries
            when {
                type == "live" && (selected == MINE || selected == FAV || selected == RECENT || e != null) -> {
                    if (liveList.isEmpty()) Text("ما فيه قنوات", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(24.dp))
                    else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(190.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = PAGE_PAD, top = 52.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        gridItemsIndexed(liveList, key = { _, c -> c.id }) { i, c ->
                            val now = guide[c.epgId]?.firstOrNull { System.currentTimeMillis() in it.start until it.end }
                            // الرجوع من المشغّل: التحديد على نفس القناة
                            val ck = "ch:${c.id}"
                            val back = remember { FocusRequester() }
                            if (ck == BrowseMemory.lastPoster) LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { back.requestFocus() } }
                            ChannelTile(c, i + 1, now?.title, Modifier.focusRequester(back)) {
                                BrowseMemory.lastPoster = ck
                                com.turkialawlqy.aman.player.LivePlayerActivity.start(context, liveList, i)
                            }
                        }
                    }
                }
                selected == MYLIST -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = PAGE_PAD, top = 52.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    gridItems(myItems, key = { "my:${it.id}" }) { it2 ->
                        PosterTile(it2.title, it2.posterUrl ?: it2.thumbnailUrl, it2.year, loading = false) { onOpenMine(it2) }
                    }
                }
                e == null -> if (selected == null) Unit else PosterSkeleton()
                e.isEmpty() -> Text("ما فيه شي في هذا التصنيف", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, top = 56.dp))
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                        initialFirstVisibleItemIndex = e.indexOfFirst { "$type:${it.sourceId}:${it.id}" == BrowseMemory.lastPoster }.coerceAtLeast(0)),
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = PAGE_PAD, top = 52.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    gridItems(e, key = { "${it.sourceId}:${it.id}" }) { x ->
                        val pk = "$type:${x.sourceId}:${x.id}"
                        val back = remember { FocusRequester() }
                        if (pk == BrowseMemory.lastPoster) LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(80); runCatching { back.requestFocus() }
                        }
                        PosterTile(x.name, x.icon, x.year, loading = busy == x.id, modifier = Modifier.focusRequester(back),
                            onFocus = if (type == "series") ({
                                libraries.firstOrNull { it.id == x.sourceId }?.let { src -> scope.launch { lib.warmSeries(src, x.id) } }
                            }) else null) {
                            val src = libraries.firstOrNull { it.id == x.sourceId } ?: return@PosterTile
                            BrowseMemory.lastPoster = pk
                            busy = x.id
                            scope.launch {
                                try {
                                    if (type == "vod") onDetail(lib.movie(src, x) to emptyList())
                                    else {
                                        val (mix, eps) = lib.series(src, x)
                                        if (eps.isNotEmpty()) onDetail(mix to eps)
                                    }
                                } finally { busy = null }
                            }
                        }
                    }
                }
            }
            Text(catName, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp), maxLines = 1)
        }
    }
}

@Composable
private fun CatRow(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        name,
        color = when { focused -> Color(0xFF111111); selected -> Accent; else -> TextDim },
        fontSize = 16.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(CardRadius))
            .background(if (focused) Color.White else if (selected) Surface else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** بوستر فيلم/مسلسل (2:3) */
@Composable
internal fun PosterTile(title: String, image: String?, year: Int?, loading: Boolean, modifier: Modifier = Modifier,
                        onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.05f else 1f, label = "poster")
    val shape = RoundedCornerShape(CardRadius)
    // تحدد بالريموت وثبت عليه شوي → نجهّز تفاصيله بالخلفية
    if (focused && onFocus != null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(350); onFocus() }
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(Surface2)
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image.isNullOrBlank()) Text(title.take(30), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            else coil3.compose.AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (loading) Text("يفتح…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Text(title, color = if (focused) TextMain else TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        if (year != null) Text("$year", color = TextDim.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ القنوات المباشرة (IPTV)

@Composable
internal fun LivePage(modifier: Modifier, channels: List<com.turkialawlqy.aman.data.LiveChannel>, firstFocus: FocusRequester) {
    val context = LocalContext.current
    val guide by com.turkialawlqy.aman.player.Epg.guide.collectAsState()
    val groups = remember(channels) { channels.groupBy { it.groupName?.takeIf { g -> g.isNotBlank() } ?: "القنوات" } }
    val favState by com.turkialawlqy.aman.player.LiveStore.favorites.collectAsState()
    val recentState by com.turkialawlqy.aman.player.LiveStore.recent.collectAsState()
    LazyColumn(modifier, contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp)) {
        item("title") {
            Text("قنوات مباشرة", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = PAGE_PAD))
            Text("فوق وتحت وقت المشاهدة يقلّب بين القنوات، وتقدر تكتب رقم القناة بالريموت — ضغطة مطوّلة على OK تضيفها للمفضلة، ويسار يفتح دليل البرامج والأرشيف",
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = PAGE_PAD, vertical = 4.dp))
        }
        var first = true
        // المفضلة وآخر القنوات أول (من نفس قنواتك بس)
        val favs = favState.filter { f -> channels.any { it.id == f.id } }
        val recents = recentState.filter { r -> channels.any { it.id == r.id } }
        val extra = listOfNotNull(
            favs.takeIf { it.isNotEmpty() }?.let { "★ المفضلة" to it },
            recents.takeIf { it.isNotEmpty() }?.let { "آخر القنوات" to it },
        )
        (extra + groups.toList()).forEach { (g, list) ->
            val isFirst = first
            first = false
            item("g-$g") {
                Column(Modifier.padding(top = 10.dp)) {
                    SectionTitle(g, count = list.size, horizontalPadding = PAGE_PAD, compact = true)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_PAD, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                            val now = guide[c.epgId]?.firstOrNull { System.currentTimeMillis() in it.start until it.end }
                            val ci = channels.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
                            ChannelTile(
                                c, ci + 1, now?.title,
                                if (isFirst && i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            ) { com.turkialawlqy.aman.player.LivePlayerActivity.start(context, channels, ci) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChannelTile(c: com.turkialawlqy.aman.data.LiveChannel, number: Int, now: String?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.05f else 1f, label = "ch")
    val shape = RoundedCornerShape(CardRadius)
    Column(
        modifier.width(200.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Surface2)
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (c.logo.isNullOrBlank()) {
                Text(c.name.take(14), color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            } else {
                coil3.compose.AsyncImage(
                    model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
            Text(
                "$number", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp),
            )
        }
        Text(c.name, color = if (focused) TextMain else TextDim, fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        if (now != null) Text(now, color = TextDim.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HomeScreen(
    device: Device,
    items: List<ContentItem>,
    offline: Boolean,
    onOpenSettings: () -> Unit,
    onOpenCollection: (ContentItem) -> Unit,
    remainingLabel: String? = null,
    profile: ChildProfile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    banner: String? = null,
    categories: List<Category> = emptyList(),
    channels: List<com.turkialawlqy.aman.data.LiveChannel> = emptyList(),
    libraries: List<com.turkialawlqy.aman.data.IptvSource> = emptyList(),
) {
    val context = LocalContext.current
    // تفاصيل فيلم/مسلسل من مكتبة الاشتراك (مو من المحتوى)
    var xtDetail by remember { mutableStateOf<Pair<ContentItem, List<ContentItem>>?>(null) }
    xtDetail?.let { (item, eps) ->
        BackHandler { xtDetail = null }
        DetailsScreen(item, eps) { }
        return
    }
    val theme = AppThemes.current
    val top = items.filter { it.parentId == null }
    // الأقسام اللي فيها محتوى لهذا الطفل
    val usedCats = categories.filter { c -> top.any { it.categoryId == c.id } }
    var tab by remember { mutableStateOf(BrowseMemory.tab ?: HOME) }
    LaunchedEffect(tab) { BrowseMemory.tab = tab }
    val hasLib = libraries.isNotEmpty()
    val hasLive = hasLib || channels.isNotEmpty()
    val valid = setOf(HOME, FAV, EPG) + (if (hasLive) setOf(LIVE) else emptySet()) +
        (if (hasLib) setOf(VOD, SERIES, MINE, SEARCH) else emptySet()) + usedCats.map { it.id }
    if (tab !in valid) tab = HOME
    BackHandler(enabled = tab != HOME) { tab = HOME }

    // القائمة الجانبية (التصميم الجديد): الاشتراك أول، وبعده قوائمك وأقسامك
    val nav = buildList {
        add(HubNav(HOME, "الرئيسية", "home", Color(0xFF8AB4FF)) { tab = HOME })
        if (hasLive) add(HubNav(LIVE, "البث المباشر", "tv", Color(0xFF34D399)) { tab = LIVE })
        if (hasLib) {
            add(HubNav(VOD, "الأفلام", "movie", Color(0xFFF472B6)) { tab = VOD })
            add(HubNav(SERIES, "المسلسلات", "series", Color(0xFFA78BFA)) { tab = SERIES })
        }
        add(HubNav(FAV, "المفضلة", "heart", Color(0xFFFB923C)) { tab = FAV })
        if (hasLive) add(HubNav(EPG, "جدول البرامج", "guide", Color(0xFF60A5FA)) { tab = EPG })
        if (hasLib && top.isNotEmpty()) add(HubNav(MINE, "قوائمي", "folder", Color(0xFFFACC15)) { tab = MINE })
        usedCats.forEach { c -> add(HubNav(c.id, c.name, c.icon, Color(0xFFD5DAE5)) { tab = c.id }) }
    }
    val heroFocus = remember { FocusRequester() }
    // أفلامك ومسلسلاتك من «قوائمي» تنضم لصفحات الاشتراك
    val movieCat = Regex("فيلم|افلام|أفلام|movie", RegexOption.IGNORE_CASE)
    val mySeries = remember(top) { top.filter { it.kind == "mix" } }
    val myMovies = remember(top, usedCats) {
        top.filter { it.kind == "video" && (it.youtubeId.startsWith("xt_m") ||
            usedCats.firstOrNull { c -> c.id == it.categoryId }?.name?.let { n -> movieCat.containsMatchIn(n) } == true) }
    }
    val myHome: @Composable (Modifier) -> Unit = { m ->
        BrowsePage(
            modifier = m, items = items, top = top, isHome = true, categories = usedCats, offline = offline,
            banner = banner, heroFocus = heroFocus, pageKey = tab, onOpenCollection = onOpenCollection,
            context = context, libraryOn = hasLib,
        )
    }
    val body: @Composable (Modifier) -> Unit = { m ->
        when (tab) {
            HOME -> if (hasLib) Column(m) {
                banner?.let { Text(it, color = Hub.Pink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, top = 4.dp)) }
                HubHomePage(Modifier.weight(1f), libraries, channels, heroFocus) { xtDetail = it }
            } else myHome(m)
            LIVE -> if (hasLib) LibraryTypePage(m, "live", libraries, channels, heroFocus) { xtDetail = it } else LivePage(m, channels, heroFocus)
            VOD -> LibraryTypePage(m, "vod", libraries, channels, heroFocus, myMovies, onOpenCollection) { xtDetail = it }
            SERIES -> LibraryTypePage(m, "series", libraries, channels, heroFocus, mySeries, onOpenCollection) { xtDetail = it }
            SEARCH -> LibraryPage(m, libraries, channels, heroFocus, startSearch = true) { xtDetail = it }
            FAV -> HubFavoritesPage(m, items, libraries, heroFocus) { xtDetail = it }
            EPG -> HubGuidePage(m, libraries, channels, heroFocus)
            MINE -> myHome(m)
            else -> BrowsePage(
                modifier = m, items = items, top = top.filter { it.categoryId == tab }, isHome = false, categories = usedCats,
                offline = offline, banner = banner, heroFocus = heroFocus, pageKey = tab, onOpenCollection = onOpenCollection,
                context = context, libraryOn = hasLib,
            )
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvScrollSpec) {
    Row(Modifier.fillMaxSize().background(Hub.Bg)) {
        HubRail(nav, tab, Modifier)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HubTopBar(
                profile = profile, remainingLabel = remainingLabel,
                onSearch = if (hasLib) ({ tab = SEARCH }) else onSearch,
                onSettings = onOpenSettings,
                onMine = if (hasLib && top.isNotEmpty()) ({ tab = MINE }) else null,
                onProfile = onSwitchProfile,
            )
            body(Modifier.weight(1f).fillMaxWidth())
        }
    }
    }
    LaunchedEffect(tab) { runCatching { heroFocus.requestFocus() } }
}

// ------------------------------------------------------------------ الصفحة (الرئيسية أو قسم)

@Composable
internal fun BrowsePage(
    modifier: Modifier,
    items: List<ContentItem>,
    top: List<ContentItem>,
    isHome: Boolean,
    categories: List<Category>,
    offline: Boolean,
    banner: String?,
    heroFocus: FocusRequester,
    pageKey: String,
    onOpenCollection: (ContentItem) -> Unit,
    context: android.content.Context,
    libraryOn: Boolean = false,
) {
    val savedIds by OfflineStore.completeIds.collectAsState()
    val resume by ResumeStore.progress.collectAsState()
    val continueMeta by ResumeStore.continueWatching.collectAsState()
    val metaScope = androidx.compose.runtime.rememberCoroutineScope()
    val favoriteMeta by com.turkialawlqy.aman.player.FavoritesStore.items.collectAsState()
    val savedPerSource = remember(savedIds) {
        OfflineStore.entries().filter { it.complete }.flatMap { it.sources }.groupingBy { it }.eachCount()
    }

    // الحلقات لكل مسلسل — تنحسب مرة وحدة لما يتغير المحتوى (مو مع كل حركة بالريموت)
    val kidsByParent = remember(items) {
        items.filter { it.parentId != null }.groupBy { it.parentId!! }.mapValues { (_, v) -> v.sortedWith(com.turkialawlqy.aman.data.EpisodeOrder) }
    }
    val itemById = remember(items) { items.associateBy { it.id } }
    fun childrenOf(mix: ContentItem) = kidsByParent[mix.id].orEmpty()
    // صورة القائمة = صورة أول حلقة لو ما لها صورة
    fun withThumb(it: ContentItem) =
        if (it.kind == "mix" && it.thumbnailUrl.isNullOrBlank()) it.copy(thumbnailUrl = childrenOf(it).firstOrNull()?.thumbnailUrl) else it

    val badge: (ContentItem) -> String? = { item ->
        when (item.kind) {
            "video" -> if (item.youtubeId in savedIds) OFFLINE_BADGE else null
            else -> {
                val n = if (item.kind == "mix") childrenOf(item).count { it.youtubeId in savedIds } else savedPerSource[item.id] ?: 0
                if (n > 0) "$n محفوظة" else null
            }
        }
    }
    fun allowedMeta(m: VideoMeta) = m.videoId.startsWith("xt_") && libraryOn ||
        m.sourceId != null && itemById[m.sourceId]?.allows(m.videoId) == true
    fun thumbOf(m: VideoMeta): String? {
        m.thumb?.takeIf { it.isNotBlank() }?.let { return it }
        val it = items.firstOrNull { x -> x.youtubeId == m.videoId }
        return it?.thumbnailUrl?.takeIf { t -> t.isNotBlank() }
            ?: (it?.parentId ?: m.sourceId)?.let { p -> items.firstOrNull { x -> x.id == p }?.thumbnailUrl }
    }
    fun asItem(m: VideoMeta) = ContentItem(
        id = "meta:${m.videoId}", deviceId = "", kind = "video", youtubeId = m.videoId,
        url = m.url, title = m.title, thumbnailUrl = thumbOf(m),
    )
    // "تابع المشاهدة" و"مفضلتي" (في الرئيسية بس، أو المحتوى اللي من هذا القسم)
    val topIds = top.map { it.id }.toSet()
    fun inPage(m: VideoMeta) = allowedMeta(m) && (isHome || m.sourceId in topIds ||
        items.firstOrNull { it.youtubeId == m.videoId }?.parentId in topIds)
    val continueRow = remember(continueMeta, items, top, isHome) { continueMeta.filter { inPage(it) } }
    val favoriteRow = remember(favoriteMeta, items, isHome) { if (isHome) favoriteMeta.filter { allowedMeta(it) } else emptyList() }
    fun playMeta(list: List<VideoMeta>, idx: Int) {
        val m = list[idx]
        // حلقة من مكتبة الاشتراك: نجيب المسلسل ونكمل بالحلقات اللي بعدها
        val sid = m.sourceId
        if (sid != null && sid.startsWith("xs:")) {
            metaScope.launch {
                val sources = (com.turkialawlqy.aman.data.TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready)?.iptv.orEmpty()
                val eps = com.turkialawlqy.aman.player.XtreamLibrary.seriesByMixId(sources, sid)?.second
                val i = eps?.indexOfFirst { it.youtubeId == m.videoId } ?: -1
                if (eps != null && i >= 0) PlayerActivity.start(context, urls = eps.map { it.url }, index = i,
                    sourceItemId = sid, itemIds = eps.map { it.id })
                else playWithEpisodes(context, items, m.url, m.videoId, m.sourceId)
            }
            return
        }
        playWithEpisodes(context, items, m.url, m.videoId, m.sourceId)
    }
    fun playMix(mix: ContentItem, from: Int) {
        val kids = childrenOf(mix)
        if (kids.isEmpty()) { onOpenCollection(mix); return }
        PlayerActivity.start(context, urls = kids.map { it.url }, index = from.coerceIn(0, kids.size - 1),
            sourceItemId = mix.id, itemIds = kids.map { it.id })
    }
    val videos = top.filter { it.kind == "video" }
    fun playVideo(v: ContentItem, list: List<ContentItem>) {
        val i = list.indexOfFirst { it.id == v.id }.coerceAtLeast(0)
        PlayerActivity.start(context, urls = list.map { it.url }, index = i, itemIds = list.map { it.id })
    }
    fun catName(item: ContentItem) = categories.firstOrNull { it.id == item.categoryId }?.name

    fun featureOf(item0: ContentItem, list: List<ContentItem> = videos): Featured {
        val item = withThumb(item0)
        return when (item.kind) {
            "mix" -> {
                val kids = childrenOf(item)
                val k = kids.indexOfFirst { (resume[it.youtubeId] ?: 0f) in 0.02f..0.95f }
                    .takeIf { it >= 0 } ?: kids.indexOfFirst { (resume[it.youtubeId] ?: 0f) < 0.95f }.coerceAtLeast(0)
                val started = kids.any { (resume[it.youtubeId] ?: 0f) > 0.02f }
                Featured(
                    tag = catName(item) ?: "مسلسل", title = item.title, image = item.backdropUrl ?: item.thumbnailUrl,
                    meta = listOfNotNull(metaLine(item, kids.size).ifBlank { null }, badge(item)),
                    progress = kids.getOrNull(k)?.let { resume[it.youtubeId] },
                    description = item.description,
                    primary = if (started) "تابع ${kids.episodeLabel(k)}" else "تشغيل",
                    onPrimary = { playMix(item, if (started) k else 0) },
                    secondary = "التفاصيل", onSecondary = { onOpenCollection(item) },
                )
            }
            "video" -> Featured(
                tag = catName(item) ?: "فيديو", title = item.title, image = item.backdropUrl ?: item.thumbnailUrl,
                meta = listOfNotNull(metaLine(item).ifBlank { null }, badge(item)),
                progress = resume[item.youtubeId],
                description = item.description,
                primary = if ((resume[item.youtubeId] ?: 0f) > 0.02f) "استئناف" else "تشغيل",
                onPrimary = { playVideo(item, list) },
                secondary = if (item.hasDetails) "التفاصيل" else null,
                onSecondary = if (item.hasDetails) ({ onOpenCollection(item) }) else null,
            )
            else -> Featured(
                tag = catName(item) ?: if (item.kind == "channel") "قناة" else "قائمة تشغيل",
                title = item.title, image = item.backdropUrl ?: item.thumbnailUrl,
                meta = listOfNotNull(metaLine(item).ifBlank { null }, badge(item)),
                description = item.description,
                primary = "افتح", onPrimary = { onOpenCollection(item) },
            )
        }
    }
    fun featureOfMeta(list: List<VideoMeta>, idx: Int, tag: String): Featured {
        val m = list[idx]
        return Featured(
            tag = tag, title = m.title, image = thumbOf(m), progress = resume[m.videoId],
            primary = "استئناف", onPrimary = { playMeta(list, idx) },
        )
    }

    // الصفوف
    data class RowSpec(val key: String, val title: String, val tiles: List<ContentItem>, val circle: Boolean = false,
                       val onFocus: (Int, ContentItem) -> Featured, val onClick: (Int, ContentItem) -> Unit)
    // الصفوف تنحسب لما يتغير المحتوى أو المحفوظ أو المشاهدة بس — مو مع كل تحديد
    val rows = remember(items, top, isHome, categories, continueRow, favoriteRow, savedIds, resume) { buildList {
        if (continueRow.isNotEmpty()) add(RowSpec("continue", "تابع المشاهدة", continueRow.map { asItem(it) },
            onFocus = { i, _ -> featureOfMeta(continueRow, i, "تابع المشاهدة") }, onClick = { i, _ -> playMeta(continueRow, i) }))
        if (favoriteRow.isNotEmpty()) add(RowSpec("favorites", "مفضلتي", favoriteRow.map { asItem(it) },
            onFocus = { i, _ -> featureOfMeta(favoriteRow, i, "مفضلتي") }, onClick = { i, _ -> playMeta(favoriteRow, i) }))
        fun kindRows(list: List<ContentItem>, prefix: String) {
            val series = list.filter { it.kind == "mix" || it.kind == "playlist" }.map { withThumb(it) }
            val vids = list.filter { it.kind == "video" }
            val chans = list.filter { it.kind == "channel" }
            if (series.isNotEmpty()) add(RowSpec("$prefix-series", "مسلسلات وقوائم", series,
                onFocus = { _, it -> featureOf(it) }, onClick = { _, it -> onOpenCollection(it) }))
            if (vids.isNotEmpty()) add(RowSpec("$prefix-videos", "فيديوهات", vids,
                onFocus = { _, it -> featureOf(it, vids) },
                onClick = { _, it -> if (it.hasDetails) onOpenCollection(it) else playVideo(it, vids) }))
            if (chans.isNotEmpty()) add(RowSpec("$prefix-channels", "قنوات", chans, circle = true,
                onFocus = { _, it -> featureOf(it) }, onClick = { _, it -> onOpenCollection(it) }))
        }
        if (isHome) {
            // كل قسم صف، وبعدها المحتوى اللي بدون قسم
            categories.forEach { c ->
                val list = top.filter { it.categoryId == c.id }.map { withThumb(it) }
                if (list.isNotEmpty()) add(RowSpec("cat-${c.id}", c.name, list,
                    onFocus = { _, it -> featureOf(it, list.filter { v -> v.kind == "video" }) },
                    onClick = { _, it ->
                        if (it.kind == "video" && !it.hasDetails) playVideo(it, list.filter { v -> v.kind == "video" }) else onOpenCollection(it)
                    }))
            }
            kindRows(top.filter { it.categoryId == null || categories.none { c -> c.id == it.categoryId } }, "other")
        } else {
            // داخل القسم: كل وسم صف (خليجي، عائلي…)، والباقي حسب النوع
            val tagged = top.filter { it.tags.isNotEmpty() }
            val tagNames = tagged.flatMap { it.tags }.distinct()
            tagNames.forEach { t ->
                val list = tagged.filter { t in it.tags }.map { withThumb(it) }
                val vids = list.filter { it.kind == "video" }
                add(RowSpec("tag-$t", t, list,
                    onFocus = { _, it -> featureOf(it, vids) },
                    onClick = { _, it -> if (it.kind == "video" && !it.hasDetails) playVideo(it, vids) else onOpenCollection(it) }))
            }
            kindRows(top.filter { it.tags.isEmpty() }, "cat")
        }
    } }

    val initial = remember(pageKey, items, continueRow.size) {
        rows.firstOrNull()?.let { r -> r.onFocus(0, r.tiles.first()) }
    }
    var featured by remember(pageKey) { mutableStateOf(initial) }
    if (featured == null && initial != null) featured = initial

    Column(modifier) {
        val f = featured
        if (f == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("ما فيه محتوى هنا بعد", color = TextDim, fontSize = 20.sp)
            }
            return@Column
        }
        Hero(f, offline, banner, heroFocus)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { r ->
                item(r.key) {
                    BrowseRow(
                        r.title, r.tiles, circle = r.circle, badgeFor = badge,
                        progressFor = { resume[it.youtubeId] },
                        onFocus = { i, it -> featured = r.onFocus(i, it) },
                        onClick = r.onClick,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ الواجهة الكبيرة

@Composable
private fun Hero(f: Featured, offline: Boolean, banner: String?, focus: FocusRequester) {
    val radius = CardRadius
    Row(
        Modifier.fillMaxWidth().height(310.dp).padding(start = PAGE_PAD, end = PAGE_PAD, top = 28.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // الكلام (يمين)
        Column(Modifier.weight(0.44f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.tag, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (offline) {
                    Spacer(Modifier.width(12.dp))
                    InfoChip("بدون إنترنت")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                f.title, color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, lineHeight = 42.sp, overflow = TextOverflow.Ellipsis,
            )
            if (f.meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(f.meta.joinToString("  ·  "), color = TextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            if (!f.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    f.description, color = TextMain.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val p = f.progress
            if (p != null && p > 0.02f) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(220.dp).height(4.dp).background(Color(0xFF5A5A5A))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Accent))
                }
            }
            if (banner != null) {
                Spacer(Modifier.height(8.dp))
                Text(banner, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton(f.primary, f.onPrimary, Modifier.focusRequester(focus), fontSize = 17)
                val sec = f.secondary
                if (sec != null) FocusButton(sec, f.onSecondary ?: {}, fontSize = 17)
            }
        }
        Spacer(Modifier.width(32.dp))
        // الصورة (يسار)
        Box(
            Modifier.weight(0.56f).fillMaxHeight().clip(RoundedCornerShape(radius)).background(Surface),
        ) {
            androidx.compose.animation.Crossfade(f.image, label = "heroImg") { img ->
                coil3.compose.AsyncImage(
                    model = img, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun BrowseRow(
    title: String,
    tiles: List<ContentItem>,
    circle: Boolean,
    badgeFor: (ContentItem) -> String?,
    progressFor: (ContentItem) -> Float?,
    onFocus: (Int, ContentItem) -> Unit,
    onClick: (Int, ContentItem) -> Unit,
) {
    val dl by OfflineDownloader.downloading.collectAsState()
    val q by OfflineDownloader.queued.collectAsState()
    Column {
        SectionTitle(title, horizontalPadding = PAGE_PAD, compact = true, modifier = Modifier.padding(top = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_PAD, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(tiles, key = { _, it -> it.id }) { idx, item ->
                FocusTile(
                    title = item.title,
                    imageUrl = item.thumbnailUrl,
                    width = if (circle) 130.dp else 230.dp,
                    circle = circle,
                    offlineBadge = badgeFor(item),
                    progress = progressFor(item),
                    downloadPercent = if (item.kind == "video") dl[item.youtubeId] else null,
                    queued = item.kind == "video" && item.youtubeId in q,
                    onFocused = { onFocus(idx, item) },
                    onClick = { onClick(idx, item) },
                )
            }
        }
    }
}

private val PAGE_PAD = 44.dp

// ------------------------------------------------------------------ نتفلكس: تبويبات فوق

@Composable
private fun TopNav(
    nav: List<NavEntry>,
    tab: String,
    profile: ChildProfile?,
    onSwitchProfile: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    remainingLabel: String?,
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = PAGE_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(44.dp)
        Spacer(Modifier.width(28.dp))
        nav.forEach { e -> TopTab(e.label, selected = e.key == tab, onClick = e.onClick) }
        Spacer(Modifier.weight(1f))
        StatusArea(remainingLabel)
        Spacer(Modifier.width(16.dp))
        TopTab("الإعدادات", selected = false, onClick = onOpenSettings)
        if (profile != null && onSwitchProfile != null) {
            Spacer(Modifier.width(8.dp))
            ProfileButton(profile, onSwitchProfile)
        }
    }
}

@Composable
private fun TopTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(CardRadius))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = when {
                focused -> Color(0xFF111111)
                selected -> TextMain
                else -> TextDim
            },
            fontSize = 17.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProfileButton(profile: ChildProfile, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (AppThemes.current.pillButtons) CircleShape else RoundedCornerShape(CardRadius)
    Box(
        Modifier.size(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Surface2)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(profile.avatar, fontSize = 20.sp) }
}

// ------------------------------------------------------------------ شاهد: قائمة جانبية

private val RAIL_COLLAPSED = 84.dp
private val RAIL_EXPANDED = 240.dp

@Composable
private fun SideNav(
    nav: List<NavEntry>,
    tab: String,
    profile: ChildProfile?,
    onSwitchProfile: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (open) RAIL_EXPANDED else RAIL_COLLAPSED, label = "rail")
    Column(
        modifier.fillMaxHeight().width(w)
            .background(Surface)
            .onFocusChanged { open = it.hasFocus }
            .padding(vertical = 26.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(44.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (open) BrandMark(44.dp) else Text("أ", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
        nav.forEach { e -> RailItem(e.label, e.icon, selected = e.key == tab, expanded = open, onClick = e.onClick) }
        Spacer(Modifier.weight(1f))
        if (profile != null && onSwitchProfile != null) {
            RailItem(profile.name, "", selected = false, expanded = open, onClick = onSwitchProfile, avatar = profile.avatar)
        }
        RailItem("الإعدادات", "settings", selected = false, expanded = open, onClick = onOpenSettings)
    }
}

@Composable
private fun RailItem(label: String, icon: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit, avatar: String? = null) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> Color(0xFF111111)
        selected -> Accent
        else -> TextDim
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(CardRadius))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (avatar != null) Text(avatar, fontSize = 20.sp) else NavIcon(icon, fg, 24.dp)
        }
        if (expanded) {
            Spacer(Modifier.width(16.dp))
            Text(
                label, color = if (focused) Color(0xFF111111) else if (selected) TextMain else TextDim,
                fontSize = 17.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ مشترك

@Composable
internal fun StatusArea(remainingLabel: String?) {
    val context = LocalContext.current
    val update by com.turkialawlqy.aman.control.AppUpdater.status.collectAsState()
    (update as? com.turkialawlqy.aman.control.AppUpdater.Status.Ready)?.let { u ->
        FocusButton("تحديث ${u.release.versionName}", { com.turkialawlqy.aman.control.AppUpdater.install(context) }, fontSize = 14, primary = true)
        Spacer(Modifier.width(12.dp))
    }
    if (remainingLabel != null) {
        InfoChip(remainingLabel)
        Spacer(Modifier.width(12.dp))
    }
    ClockChip()
}

/** الساعة (تتحدث تلقائياً) */
@Composable
fun ClockChip() {
    val time by produceState(clockText()) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = clockText()
        }
    }
    Text(
        time, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr),
    )
}

private fun clockText(): String =
    java.text.SimpleDateFormat("h:mm", java.util.Locale.ENGLISH).format(java.util.Date())

/**
 * أيقونات القائمة مرسومة بخطوط بسيطة (بدون إيموجي) — نفس الشكل على كل تلفزيون.
 * الأنواع: home, search, settings, series, movie, kids, sports, learn, docs, music, star, grid
 */
@Composable
fun NavIcon(kind: String, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.09f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, w * y)
        when (kind) {
            "home" -> {
                val path = Path().apply {
                    moveTo(w * 0.12f, w * 0.48f); lineTo(w * 0.5f, w * 0.14f); lineTo(w * 0.88f, w * 0.48f)
                    moveTo(w * 0.22f, w * 0.40f); lineTo(w * 0.22f, w * 0.86f); lineTo(w * 0.78f, w * 0.86f); lineTo(w * 0.78f, w * 0.40f)
                }
                drawPath(path, color, style = st)
            }
            "tv" -> {
                drawRoundRect(color, p(0.1f, 0.22f), Size(w * 0.8f, w * 0.54f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = st)
                drawLine(color, p(0.35f, 0.88f), p(0.65f, 0.88f), sw, StrokeCap.Round)
                drawLine(color, p(0.38f, 0.08f), p(0.5f, 0.2f), sw, StrokeCap.Round)
                drawLine(color, p(0.62f, 0.08f), p(0.5f, 0.2f), sw, StrokeCap.Round)
            }
            "search" -> {
                drawCircle(color, w * 0.28f, p(0.44f, 0.42f), style = st)
                drawLine(color, p(0.64f, 0.63f), p(0.86f, 0.86f), sw, StrokeCap.Round)
            }
            "settings" -> {
                drawCircle(color, w * 0.14f, p(0.5f, 0.5f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Offset(w * 0.5f, w * 0.5f)
                    val r1 = w * 0.28f; val r2 = w * 0.40f
                    drawLine(color, c + Offset((kotlin.math.cos(a) * r1).toFloat(), (kotlin.math.sin(a) * r1).toFloat()),
                        c + Offset((kotlin.math.cos(a) * r2).toFloat(), (kotlin.math.sin(a) * r2).toFloat()), sw, StrokeCap.Round)
                }
                drawCircle(color, w * 0.28f, p(0.5f, 0.5f), style = st)
            }
            "series" -> { // شاشة فوق شاشة
                drawRoundRect(color, p(0.12f, 0.30f), Size(w * 0.76f, w * 0.52f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = st)
                drawLine(color, p(0.24f, 0.17f), p(0.76f, 0.17f), sw, StrokeCap.Round)
            }
            "movie" -> { // شريط فيلم
                drawRoundRect(color, p(0.14f, 0.16f), Size(w * 0.72f, w * 0.68f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = st)
                drawLine(color, p(0.32f, 0.16f), p(0.32f, 0.84f), sw)
                drawLine(color, p(0.68f, 0.16f), p(0.68f, 0.84f), sw)
                drawLine(color, p(0.14f, 0.5f), p(0.32f, 0.5f), sw)
                drawLine(color, p(0.68f, 0.5f), p(0.86f, 0.5f), sw)
            }
            "kids" -> { // وجه مبتسم
                drawCircle(color, w * 0.38f, p(0.5f, 0.5f), style = st)
                drawCircle(color, w * 0.045f, p(0.37f, 0.42f))
                drawCircle(color, w * 0.045f, p(0.63f, 0.42f))
                drawArc(color, 20f, 140f, false, p(0.32f, 0.38f), Size(w * 0.36f, w * 0.30f), style = st)
            }
            "sports" -> { // كرة
                drawCircle(color, w * 0.38f, p(0.5f, 0.5f), style = st)
                drawLine(color, p(0.12f, 0.5f), p(0.88f, 0.5f), sw)
                drawArc(color, -60f, 120f, false, p(0.05f, 0.12f), Size(w * 0.5f, w * 0.76f), style = st)
                drawArc(color, 120f, 120f, false, p(0.45f, 0.12f), Size(w * 0.5f, w * 0.76f), style = st)
            }
            "learn" -> { // كتاب مفتوح
                val path = Path().apply {
                    moveTo(w * 0.5f, w * 0.26f); lineTo(w * 0.5f, w * 0.84f)
                    moveTo(w * 0.5f, w * 0.26f); quadraticTo(w * 0.32f, w * 0.16f, w * 0.1f, w * 0.22f); lineTo(w * 0.1f, w * 0.78f)
                    quadraticTo(w * 0.32f, w * 0.72f, w * 0.5f, w * 0.84f)
                    quadraticTo(w * 0.68f, w * 0.72f, w * 0.9f, w * 0.78f); lineTo(w * 0.9f, w * 0.22f)
                    quadraticTo(w * 0.68f, w * 0.16f, w * 0.5f, w * 0.26f)
                }
                drawPath(path, color, style = st)
            }
            "docs" -> { // كرة أرضية
                drawCircle(color, w * 0.38f, p(0.5f, 0.5f), style = st)
                drawOval(color, p(0.32f, 0.12f), Size(w * 0.36f, w * 0.76f), style = st)
                drawLine(color, p(0.13f, 0.5f), p(0.87f, 0.5f), sw)
            }
            "music" -> {
                drawCircle(color, w * 0.12f, p(0.3f, 0.74f))
                drawCircle(color, w * 0.12f, p(0.72f, 0.66f))
                val path = Path().apply {
                    moveTo(w * 0.41f, w * 0.74f); lineTo(w * 0.41f, w * 0.2f); lineTo(w * 0.83f, w * 0.12f); lineTo(w * 0.83f, w * 0.66f)
                }
                drawPath(path, color, style = st)
            }
            "heart" -> {
                val path = Path()
                path.moveTo(w * 0.5f, w * 0.82f)
                path.cubicTo(w * 0.05f, w * 0.52f, w * 0.12f, w * 0.12f, w * 0.5f, w * 0.32f)
                path.cubicTo(w * 0.88f, w * 0.12f, w * 0.95f, w * 0.52f, w * 0.5f, w * 0.82f)
                path.close()
                drawPath(path, color, style = st)
            }
            "guide" -> { // جدول: صندوق فيه أسطر
                drawRoundRect(color, p(0.12f, 0.18f), Size(w * 0.76f, w * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = st)
                drawLine(color, p(0.12f, 0.38f), p(0.88f, 0.38f), sw)
                drawLine(color, p(0.38f, 0.38f), p(0.38f, 0.82f), sw)
                drawLine(color, p(0.48f, 0.55f), p(0.78f, 0.55f), sw)
                drawLine(color, p(0.48f, 0.68f), p(0.7f, 0.68f), sw)
            }
            "folder" -> {
                val path = Path()
                path.moveTo(w * 0.1f, w * 0.28f); path.lineTo(w * 0.4f, w * 0.28f); path.lineTo(w * 0.48f, w * 0.38f)
                path.lineTo(w * 0.9f, w * 0.38f); path.lineTo(w * 0.9f, w * 0.8f); path.lineTo(w * 0.1f, w * 0.8f); path.close()
                drawPath(path, color, style = st)
            }
            "profile" -> {
                drawCircle(color, w * 0.17f, p(0.5f, 0.36f), style = st)
                val path = Path()
                path.moveTo(w * 0.18f, w * 0.86f)
                path.cubicTo(w * 0.22f, w * 0.6f, w * 0.78f, w * 0.6f, w * 0.82f, w * 0.86f)
                drawPath(path, color, style = st)
            }
            "star" -> {
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) w * 0.4f else w * 0.17f
                    val a = Math.toRadians(-90.0 + i * 36)
                    val x = w * 0.5f + (kotlin.math.cos(a) * r).toFloat()
                    val y = w * 0.53f + (kotlin.math.sin(a) * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color, style = st)
            }
            else -> { // grid
                val s = w * 0.3f
                for ((x, y) in listOf(0.14f to 0.14f, 0.56f to 0.14f, 0.14f to 0.56f, 0.56f to 0.56f)) {
                    drawRoundRect(color, p(x, y), Size(s, s), androidx.compose.ui.geometry.CornerRadius(w * 0.05f), style = st)
                }
            }
        }
    }
}


/** مكان البوسترات وقت التحميل (بدل كلمة "يجيب…") */
@Composable
internal fun PosterSkeleton(columns: Int = 6, rows: Int = 2) {
    Column(Modifier.padding(start = 16.dp, end = PAGE_PAD, top = 52.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(columns) {
                    Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(CardRadius)).background(Surface2.copy(alpha = 0.6f)))
                }
            }
        }
    }
}
