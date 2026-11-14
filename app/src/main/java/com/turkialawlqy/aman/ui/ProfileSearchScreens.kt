package com.turkialawlqy.aman.ui

import androidx.compose.foundation.shape.RoundedCornerShape



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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.data.ChildProfile
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.player.PlayerActivity
import com.turkialawlqy.aman.yt.CollectionCache
import com.turkialawlqy.aman.yt.VideoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ اختيار الطفل

@Composable
fun ProfilePickerScreen(deviceName: String, profiles: List<ChildProfile>, defaultTheme: String? = null, onPick: (ChildProfile) -> Unit) {
    val lastId = remember { com.turkialawlqy.aman.control.ProfileStore.lastId() }
    val focusIndex = profiles.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().screenBackground().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(56.dp)
        Spacer(Modifier.height(18.dp))
        Text("مين يشاهد؟", color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(deviceName, color = TextDim, fontSize = 18.sp)
        Spacer(Modifier.height(40.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                ProfileTile(
                    p, theme = AppThemes.byId(p.theme) ?: AppThemes.byId(defaultTheme) ?: AppThemes.shahid,
                    onClick = { onPick(p) },
                    modifier = if (i == focusIndex) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(profiles.size) { runCatching { focus.requestFocus() } }
}

@Composable
private fun ProfileTile(p: ChildProfile, theme: AppTheme, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "profileScale")
    val shape = if (theme.pillButtons) CircleShape else RoundedCornerShape(theme.radius)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                // معاينة ثيم الطفل وقت التنقل بين الصور
                if (it.isFocused) AppThemes.apply(theme.id)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(160.dp)
                .background(theme.surface2, shape)
                .then(if (focused) Modifier.border(4.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.avatar, fontSize = 86.sp)
            if (!p.pin.isNullOrBlank()) {
                Text(
                    "برمز", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            p.name,
            color = if (focused) TextMain else TextDim,
            fontSize = 22.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// ------------------------------------------------------------------ رمز أمان الطفل

/** الطفل اللي عليه رمز: يدخله قبل ما يفتح ملفه (5 محاولات ثم انتظار دقيقة) */
@Composable
fun ProfilePinScreen(p: ChildProfile, onSuccess: () -> Unit, onBack: () -> Unit) {
    var pin by remember(p.id) { mutableStateOf("") }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var fails by remember(p.id) { mutableStateOf(0) }
    var lockedUntil by remember(p.id) { mutableStateOf(0L) }
    val firstKey = remember { FocusRequester() }

    fun press(d: Char) {
        if (System.currentTimeMillis() < lockedUntil) {
            error = "محاولات كثيرة — انتظر دقيقة"; return
        }
        if (pin.length >= 4) return
        pin += d
        if (pin.length == 4) {
            if (pin == p.pin) {
                onSuccess()
            } else {
                fails++
                pin = ""
                if (fails >= 5) {
                    fails = 0
                    lockedUntil = System.currentTimeMillis() + 60_000
                    error = "محاولات كثيرة — انتظر دقيقة"
                } else error = "الرمز غير صحيح"
            }
        }
    }

    Row(
        Modifier.fillMaxSize().screenBackground().padding(horizontal = 64.dp, vertical = 32.dp)
            .onPreviewKeyEvent { ev ->
                // أزرار الأرقام في الريموت (بدون ما يبان الرمز على الشاشة)
                val d = remoteDigit(ev)
                when {
                    d != null -> { press(d); true }
                    remoteDelete(ev) -> { pin = pin.dropLast(1); true }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
    ) {
        // يمين: الطفل والنقاط
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarBubble(p.avatar, 136.dp)
            Spacer(Modifier.height(16.dp))
            Text("رمز ${p.name}", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            PinDots(pin.length, error = error != null)
            Spacer(Modifier.height(14.dp))
            Text(error ?: "اكتب الرمز بأزرار الأرقام في الريموت", color = if (error != null) Danger else TextDim, fontSize = 17.sp)
        }
        // يسار: لوحة الأرقام (ترتيبها يتغير كل مرة)
        PinKeypad(
            onDigit = { press(it) }, onDelete = { pin = pin.dropLast(1) }, onBack = onBack,
            firstKey = firstKey, shuffleKey = p.id,
        )
    }
    LaunchedEffect(p.id) { runCatching { firstKey.requestFocus() } }
}

// ------------------------------------------------------------------ البحث الآمن (داخل المحتوى المسموح فقط)

private data class Hit(
    val key: String,
    val title: String,
    val thumb: String?,
    val subtitle: String?,
    val circle: Boolean = false,
    val open: ContentItem? = null, // قائمة/قناة تنفتح
    val url: String? = null,       // فيديو يشتغل
    val sourceId: String? = null,
    val videoId: String? = null,
) {
    val norm = normalizeArabic("$title ${subtitle ?: ""}")
}

/** يوحّد الكتابة: بدون تشكيل، أ/إ/آ = ا، ة = ه، ى = ي */
fun normalizeArabic(s: String): String = s.lowercase()
    .replace(Regex("[ً-ْـ]"), "")
    .replace(Regex("[أإآٱ]"), "ا")
    .replace('ة', 'ه')
    .replace('ى', 'ي')
    .replace('ؤ', 'و')
    .replace('ئ', 'ي')

@Composable
fun SearchScreen(items: List<ContentItem>, onOpenCollection: (ContentItem) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<Hit>>(emptyList()) }
    val savedIds by OfflineStore.completeIds.collectAsState()
    val field = remember { FocusRequester() }
    val dl by com.turkialawlqy.aman.offline.OfflineDownloader.downloading.collectAsState()

    // كل ما يقدر الطفل يشوفه: الفيديوهات، القوائم، القنوات، وفيديوهات القوائم المحفوظة على الجهاز
    LaunchedEffect(items) {
        all = withContext(Dispatchers.IO) {
            val byId = items.associateBy { it.id }
            val hits = mutableListOf<Hit>()
            val seen = HashSet<String>()
            for (item in items) {
                when (item.kind) {
                    "video" -> if (seen.add(item.youtubeId)) hits += Hit(
                        "v:${item.id}", item.title, item.thumbnailUrl,
                        item.parentId?.let { p -> byId[p]?.title } ?: "فيديو",
                        url = item.url, sourceId = item.id, videoId = item.youtubeId,
                    )
                    else -> hits += Hit(
                        "c:${item.id}", item.title, item.thumbnailUrl,
                        when (item.kind) { "channel" -> "قناة"; "mix" -> "قائمتي"; else -> "قائمة تشغيل" },
                        circle = item.kind == "channel", open = item,
                    )
                }
            }
            for (item in items.filter { it.kind == "playlist" || it.kind == "channel" }) {
                val cached = CollectionCache.get(item.id)?.items ?: continue
                for (e in cached) {
                    val vid = VideoExtractor.videoIdOf(e.url) ?: continue
                    if (!item.allows(vid) || !seen.add(vid)) continue
                    val ready = com.turkialawlqy.aman.data.TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready
                    if (ready?.titleBlocked(e.title) == true) continue // فلتر الكلمات
                    hits += Hit("x:${item.id}:$vid", e.title, e.thumbnail, item.title, url = e.url, sourceId = item.id, videoId = vid)
                }
            }
            hits
        }
    }

    val words = remember(query) { normalizeArabic(query).split(Regex("\\s+")).filter { it.isNotBlank() } }
    val results = remember(words, all) {
        if (words.isEmpty()) emptyList() else all.filter { h -> words.all { it in h.norm } }.take(80)
    }

    Column(Modifier.fillMaxSize().screenBackground().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavIcon("search", TextMain, 30.dp)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                singleLine = true,
                placeholder = { Text("اكتب اسم الفيديو أو القناة…", color = TextDim) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent, unfocusedBorderColor = Surface,
                    cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f).focusRequester(field),
            )
            Spacer(Modifier.width(16.dp))
            FocusButton("رجوع", onBack)
        }
        Spacer(Modifier.height(20.dp))
        when {
            words.isEmpty() -> Text(
                "البحث يشمل المحتوى اللي اختاره لك ولي الأمر فقط",
                color = TextDim, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            results.isEmpty() -> Text(
                "ما لقينا شي باسم \"$query\"",
                color = TextDim, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(results, key = { it.key }) { h ->
                    FocusTile(
                        title = h.title,
                        imageUrl = h.thumb,
                        width = 230.dp,
                        circle = h.circle,
                        subtitle = h.subtitle,
                        offlineBadge = if (h.videoId != null && h.videoId in savedIds) OFFLINE_BADGE else null,
                        downloadPercent = h.videoId?.let { dl[it] },
                        onClick = {
                            val open = h.open
                            if (open != null) onOpenCollection(open)
                            else if (h.url != null) playWithEpisodes(context, items, h.url, h.videoId, h.sourceId)
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}
