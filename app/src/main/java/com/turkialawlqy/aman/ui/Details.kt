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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.data.CastMember
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.episodeLabel
import com.turkialawlqy.aman.data.episodeNumber
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.player.PlayerActivity
import com.turkialawlqy.aman.player.ResumeStore

/*
 * صفحة تفاصيل المسلسل أو الفيلم (مثل شاهد):
 * الوصف والسنة والتصنيف + الخلفية، ثم الحلقات، ثم طاقم العمل (ضغطة على ممثل = صفحته).
 * تصميم مسطّح: ألوان سادة، بدون تدرّجات.
 */

private val PAD = 44.dp

/** معلومات مختصرة: "2024 · دراما، كوميدي · 30 حلقة" */
internal fun metaLine(item: ContentItem, episodes: Int? = null): String =
    listOfNotNull(
        item.year?.toString(),
        item.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString("، "),
        episodes?.let { "$it حلقة" },
        if (item.educational) "تعليمي" else null,
    ).joinToString("  ·  ")

@Composable
fun DetailsScreen(entry: ContentItem, children: List<ContentItem>, onOpenActor: (CastMember) -> Unit) {
    val context = LocalContext.current
    val resume by ResumeStore.progress.collectAsState()
    val savedIds by OfflineStore.completeIds.collectAsState()
    val dl by OfflineDownloader.downloading.collectAsState()
    val q by OfflineDownloader.queued.collectAsState()
    val playFocus = remember { FocusRequester() }
    val isSeries = entry.kind == "mix"
    val episodes = if (isSeries) children else emptyList()

    fun playFrom(i: Int) {
        if (isSeries) {
            if (episodes.isEmpty()) return
            PlayerActivity.start(context, urls = episodes.map { it.url }, index = i.coerceIn(0, episodes.size - 1),
                sourceItemId = entry.id, itemIds = episodes.map { it.id })
        } else {
            PlayerActivity.start(context, urls = listOf(entry.url), index = 0, itemIds = listOf(entry.id))
        }
    }
    // أول حلقة ما خلصت
    val k = episodes.indexOfFirst { (resume[it.youtubeId] ?: 0f) in 0.02f..0.95f }
        .takeIf { it >= 0 } ?: episodes.indexOfFirst { (resume[it.youtubeId] ?: 0f) < 0.95f }.coerceAtLeast(0)
    val started = if (isSeries) episodes.any { (resume[it.youtubeId] ?: 0f) > 0.02f } else (resume[entry.youtubeId] ?: 0f) > 0.02f
    val primary = when {
        isSeries && episodes.isEmpty() -> null
        isSeries && started -> "تابع ${episodes.episodeLabel(k)}"
        isSeries -> "شاهد الحلقة 1"
        started -> "استئناف"
        else -> "تشغيل"
    }
    // المواسم: تبويب لكل موسم (يبدأ على موسم الحلقة اللي بتكملها)
    val seasons = episodes.map { it.seasonNo }.distinct().sorted()
    var season by remember(entry.id) { mutableStateOf(episodes.getOrNull(k)?.seasonNo ?: seasons.firstOrNull() ?: 1) }
    if (seasons.isNotEmpty() && season !in seasons) season = seasons.first()
    val shown = if (seasons.size > 1) episodes.filter { it.seasonNo == season } else episodes
    val image = entry.backdropUrl ?: entry.thumbnailUrl ?: episodes.firstOrNull()?.thumbnailUrl

    LazyColumn(
        Modifier.fillMaxSize().background(Background),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item("head") {
            Row(
                Modifier.fillMaxWidth().height(330.dp).padding(start = PAD, end = PAD, top = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(0.46f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(
                        entry.title, color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, lineHeight = 44.sp, overflow = TextOverflow.Ellipsis,
                    )
                    val meta = metaLine(entry, if (isSeries) episodes.size else null)
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(meta, color = TextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    if (entry.tags.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            entry.tags.take(4).forEach { InfoChip(it) }
                        }
                    }
                    if (!entry.description.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            entry.description, color = TextMain.copy(alpha = 0.88f), fontSize = 16.sp, lineHeight = 25.sp,
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (primary != null) {
                            FocusButton(primary, { playFrom(if (started) k else 0) }, Modifier.focusRequester(playFocus), fontSize = 17)
                        }
                        // مسلسل من مكتبة الاشتراك: "تابعه" — الحلقات الجديدة تتحمّل لحالها (كله على التلفزيون)
                        if (isSeries && entry.id.startsWith("xs:")) {
                            val followed by com.turkialawlqy.aman.control.SeriesFollow.keys.collectAsState()
                            val on = entry.id in followed
                            FocusButton(if (on) "تتابعه ✓" else "تابع المسلسل", {
                                val rest = entry.id.removePrefix("xs:")
                                val srcId = rest.substringBeforeLast(':')
                                com.turkialawlqy.aman.control.SeriesFollow.toggle(entry, episodes, srcId, rest.substringAfterLast(':'))
                            }, fontSize = 17, selected = on)
                        }
                    }
                }
                Spacer(Modifier.width(32.dp))
                Box(Modifier.weight(0.54f).fillMaxHeight().clip(RoundedCornerShape(CardRadius)).background(Surface)) {
                    coil3.compose.AsyncImage(
                        model = image, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (episodes.isNotEmpty()) item("episodes") {
            Column(Modifier.padding(top = 22.dp)) {
                if (seasons.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAD),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(seasons, key = { _, sn -> "s$sn" }) { _, sn ->
                            FocusButton("الموسم $sn", { season = sn }, selected = sn == season, fontSize = 15)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                } else {
                    SectionTitle("الحلقات", count = episodes.size, horizontalPadding = PAD, compact = true)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAD, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(shown, key = { _, v -> v.id }) { _, v ->
                        val idx = episodes.indexOf(v)
                        FocusTile(
                            title = v.title,
                            imageUrl = v.thumbnailUrl ?: entry.backdropUrl ?: entry.thumbnailUrl,
                            width = 240.dp,
                            subtitle = "${episodes.episodeNumber(idx)}" + if ((resume[v.youtubeId] ?: 0f) >= 0.95f) " · شاهدتها" else "",
                            offlineBadge = if (v.youtubeId in savedIds) OFFLINE_BADGE else null,
                            progress = resume[v.youtubeId],
                            downloadPercent = dl[v.youtubeId],
                            queued = v.youtubeId in q,
                            onClick = { playFrom(idx) },
                        )
                    }
                }
            }
        }
        if (entry.castList.isNotEmpty()) item("cast") {
            Column(Modifier.padding(top = 18.dp)) {
                SectionTitle("طاقم العمل", horizontalPadding = PAD, compact = true)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAD, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(entry.castList, key = { i, c -> "${c.id ?: c.name}-$i" }) { _, c ->
                        CastTile(c) { onOpenActor(c) }
                    }
                }
            }
        }
    }
    LaunchedEffect(entry.id) { runCatching { playFocus.requestFocus() } }
}

/** صورة الممثل دائرية + اسمه + دوره */
@Composable
private fun CastTile(c: CastMember, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "cast")
    Column(
        Modifier.width(120.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Surface2)
                .then(if (focused) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (c.photo.isNullOrBlank()) {
                Text(c.name.take(1), color = TextDim, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            } else {
                coil3.compose.AsyncImage(
                    model = c.photo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(c.name, color = if (focused) TextMain else TextDim, fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!c.character.isNullOrBlank()) {
            Text(c.character, color = TextDim.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** صفحة الممثل: صورته واسمه وكل اللي عندك له */
@Composable
fun ActorScreen(actor: CastMember, items: List<ContentItem>, onOpen: (ContentItem) -> Unit) {
    val works = items.filter { it.parentId == null && it.castList.any { c -> c.same(actor) } }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Background).padding(top = 40.dp)) {
        Row(Modifier.padding(horizontal = PAD), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                if (actor.photo.isNullOrBlank()) {
                    Text(actor.name.take(1), color = TextDim, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                } else {
                    coil3.compose.AsyncImage(
                        model = actor.photo, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text(actor.name, color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("${works.size} ${if (works.size in 3..10) "أعمال" else "عمل"} عندك", color = TextDim, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionTitle("أعماله", horizontalPadding = PAD, compact = true)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAD, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(works, key = { _, w -> w.id }) { i, w ->
                FocusTile(
                    title = w.title,
                    imageUrl = w.thumbnailUrl ?: w.backdropUrl,
                    width = 260.dp,
                    subtitle = w.year?.toString(),
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { onOpen(w) },
                )
            }
        }
    }
    LaunchedEffect(actor) { runCatching { first.requestFocus() } }
}
