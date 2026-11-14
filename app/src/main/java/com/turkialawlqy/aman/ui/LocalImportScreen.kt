package com.turkialawlqy.aman.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.offline.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * استيراد فيديوهات من فلاشة/هارديسك (بعد رمز الإعدادات):
 * 1) نختار المجلد  2) وين تنضاف (قائمة جديدة / موجودة / الرئيسية) ولمين  3) نسخ أو تشغيل من الفلاشة
 */
@Composable
fun LocalImportScreen(pin: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    var folders by remember { mutableStateOf<List<LocalMedia.Folder>?>(null) }
    var scanKey by remember { mutableStateOf(0) }
    var folder by remember { mutableStateOf<LocalMedia.Folder?>(null) }
    val progress by LocalMedia.progress.collectAsState()
    var started by remember { mutableStateOf(LocalMedia.running) }

    BackHandler {
        when {
            started && LocalMedia.running -> Unit // ينتظر يخلص أو "إلغاء"
            started -> { LocalMedia.clearProgress(); started = false; folder = null; scanKey++ }
            folder != null -> folder = null
            else -> onBack()
        }
    }

    LaunchedEffect(granted, scanKey) {
        if (!granted) return@LaunchedEffect
        folders = null
        folders = withContext(Dispatchers.IO) { LocalMedia.scan(context) }
    }

    when {
        !granted -> ImportMessage(
            "استيراد من فلاشة / هارديسك",
            if (denied) "ما انعطى التطبيق صلاحية قراءة الفيديوهات. اضغط «سماح» واختر «السماح»."
            else "وصّل الفلاشة أو الهارديسك بالتلفزيون، وبعدها اسمح للتطبيق يقرأ الفيديوهات.",
            "سماح", { launcher.launch(perm) }, onBack,
        )
        started || (progress != null && LocalMedia.running) -> ImportProgress(progress) {
            if (LocalMedia.running) LocalMedia.cancel()
            else { LocalMedia.clearProgress(); started = false; folder = null; scanKey++ }
        }
        folder != null -> ImportDestination(folder!!, onStart = { dest, copy ->
            LocalMedia.start(context, pin, folder!!.videos, dest, copy)
            started = true
        }, onBack = { folder = null })
        folders == null -> LoadingScreen("يدوّر على الفيديوهات…")
        folders!!.isEmpty() -> ImportMessage(
            "ما لقيت فيديوهات",
            "تأكد إن الفلاشة/الهارديسك موصول وفيه ملفات mp4 أو mkv.\nبعض الأجهزة تاخذ دقيقة تقرأ الفلاشة بعد ما توصلها.",
            "دوّر مرة ثانية", { scanKey++ }, onBack,
        )
        else -> FolderList(folders!!, onPick = { folder = it }, onRescan = { scanKey++ }, onBack = onBack)
    }
}

@Composable
private fun ImportMessage(title: String, text: String, action: String, onAction: () -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().screenBackground().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(text, color = TextDim, fontSize = 20.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusButton(action, onAction, Modifier.focusRequester(focus), primary = true)
            FocusButton("رجوع", onBack)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun FolderList(folders: List<LocalMedia.Folder>, onPick: (LocalMedia.Folder) -> Unit, onRescan: () -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LazyColumn(
        Modifier.fillMaxSize().screenBackground(),
        contentPadding = PaddingValues(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("اختر المجلد", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("كل مجلد = مسلسل أو مجموعة فيديوهات", color = TextDim, fontSize = 16.sp)
                }
                FocusButton("تحديث", onRescan)
                Spacer(Modifier.width(10.dp))
                FocusButton("رجوع", onBack)
            }
        }
        folders.forEachIndexed { i, f ->
            item(f.key) {
                val already = f.videos.count { LocalMedia.alreadyImported(it.uri) }
                FocusButton(
                    "${f.name} — ${f.videos.size} فيديو · ${formatBytes(f.totalBytes)} · ${f.volumeLabel}" +
                        if (already > 0) " (مستورد منها $already)" else "",
                    { onPick(f) },
                    (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ImportDestination(
    folder: LocalMedia.Folder,
    onStart: (LocalMedia.Dest, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state by TvRepository.state.collectAsState()
    val ready = state as? TvState.Ready
    val mixes = ready?.items.orEmpty().filter { it.kind == "mix" && it.parentId == null }.sortedBy { it.position }
    val profiles = ready?.profiles.orEmpty()
    // "new" = قائمة جديدة، "home" = الرئيسية، غير كذا = معرّف قائمة موجودة
    var target by remember { mutableStateOf("new") }
    var kids by remember { mutableStateOf(setOf<String>()) } // فاضي = كل الأطفال
    val free = LocalMedia.freeBytes()
    val need = folder.totalBytes
    val canCopy = need + 300L * 1024 * 1024 <= free
    var copy by remember { mutableStateOf(canCopy) }
    val first = remember { FocusRequester() }

    LazyColumn(
        Modifier.fillMaxSize().screenBackground(),
        contentPadding = PaddingValues(horizontal = 64.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("${folder.name}", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("${folder.videos.size} فيديو · ${formatBytes(need)} · ${folder.volumeLabel}", color = TextDim, fontSize = 16.sp)
        }
        item { SectionTitle("وين تبيها تنضاف؟") }
        item {
            FocusButton("قائمة جديدة باسم «${folder.name}»", { target = "new" },
                Modifier.focusRequester(first).fillMaxWidth(), selected = target == "new")
        }
        items(mixes, key = { it.id }) { m ->
            FocusButton("أضفها لقائمة «${m.title}»", { target = m.id },
                Modifier.fillMaxWidth(), selected = target == m.id)
        }
        item {
            FocusButton("الصفحة الرئيسية (فيديوهات منفصلة)", { target = "home" },
                Modifier.fillMaxWidth(), selected = target == "home")
        }
        if (profiles.isNotEmpty() && (target == "new" || target == "home")) {
            item { SectionTitle("لمين تظهر؟") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { FocusButton("الكل", { kids = emptySet() }, selected = kids.isEmpty()) }
                    items(profiles, key = { it.id }) { p ->
                        FocusButton("${p.avatar} ${p.name}", {
                            kids = if (p.id in kids) kids - p.id else kids + p.id
                        }, selected = p.id in kids)
                    }
                }
            }
        }
        item { SectionTitle("طريقة الحفظ") }
        item {
            FocusButton(
                "انسخها لمكان التحميل (${LocalMedia.targetLabel()}) — تحتاج ${formatBytes(need)} · متاح ${formatBytes(free)}",
                { if (canCopy) copy = true }, Modifier.fillMaxWidth(), selected = copy,
            )
        }
        if (!canCopy) item { Text("المساحة في مكان التحميل ما تكفي للنسخ", color = Danger, fontSize = 16.sp) }
        item {
            FocusButton("شغّلها من الفلاشة مباشرة — لازم تبقى موصولة", { copy = false },
                Modifier.fillMaxWidth(), selected = !copy)
        }
        item {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton("ابدأ الاستيراد", {
                    val dest = when (target) {
                        "new" -> LocalMedia.Dest.NewList(folder.name, kids.toList())
                        "home" -> LocalMedia.Dest.Home(kids.toList())
                        else -> LocalMedia.Dest.Existing(target)
                    }
                    onStart(dest, copy && canCopy)
                }, primary = true)
                FocusButton("رجوع", onBack)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun ImportProgress(p: LocalMedia.Progress?, onButton: () -> Unit) {
    val focus = remember { FocusRequester() }
    val finished = p?.finished == true
    Column(
        Modifier.fillMaxSize().screenBackground().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                p == null -> "يجهّز…"
                finished && p.error == null -> "تم استيراد ${p.done} فيديو"
                finished -> "${p.error}"
                else -> "يستورد ${p.done + 1} من ${p.total}"
            },
            color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        if (p != null && !finished) {
            Text("${p.current} · ${p.percent}%", color = TextDim, fontSize = 18.sp)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { if (p.total > 0) ((p.done + p.percent / 100f) / p.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.width(520.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = Accent, trackColor = Surface2,
            )
        }
        if (p != null && p.linked > 0) {
            Text("${p.linked} حلقة من الاشتراك ارتبطت بحلقاتها الموجودة — ما تكررت", color = TextDim, fontSize = 16.sp)
        }
        if (p != null && p.skipped > 0) {
            Spacer(Modifier.height(10.dp))
            Text("${p.skipped} فيديو مستورد قبل — ما تكرر", color = TextDim, fontSize = 16.sp)
        }
        if (finished && p?.error == null) {
            Spacer(Modifier.height(10.dp))
            Text("تلقاها الحين في التطبيق وفي لوحة التحكم", color = TextDim, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
        FocusButton(if (finished) "تم" else "إلغاء", onButton, Modifier.focusRequester(focus), primary = finished)
    }
    LaunchedEffect(finished) { runCatching { focus.requestFocus() } }
}
