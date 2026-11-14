package com.turkialawlqy.aman.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.data.Device
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.offline.OfflineDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.offline.StorageOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ شاشة الرمز

@Composable
fun PinScreen(device: Device, onSuccess: (String) -> Unit, onBack: () -> Unit) {
    if (!device.hasPin) {
        MessageScreen(
            "الإعدادات",
            "لم يتم تحديد رمز الإعدادات بعد.\nحدد رمزاً من 4 أرقام من لوحة الأهل (صفحة الجهاز الإعدادات).",
            "رجوع", onBack,
        )
        return
    }

    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    fun press(d: Char) {
        if (checking || pin.length >= 4) return
        pin += d
        error = null
        if (pin.length == 4) {
            checking = true
            val entered = pin
            scope.launch {
                val err = TvRepository.checkPin(entered)
                checking = false
                if (err == null) onSuccess(entered) else {
                    error = err
                    pin = ""
                }
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .padding(horizontal = 64.dp, vertical = 32.dp)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("رمز ولي الأمر", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            PinDots(pin.length, error = error != null)
            Spacer(Modifier.height(14.dp))
            Text(
                error ?: if (checking) "جاري التحقق…" else "اكتب الرمز بأزرار الأرقام في الريموت",
                color = if (error != null) Danger else TextDim, fontSize = 17.sp,
            )
        }
        PinKeypad(onDigit = { press(it) }, onDelete = { pin = pin.dropLast(1) }, onBack = onBack, firstKey = firstKey)
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
}

// ------------------------------------------------------------------ شاشة الإعدادات

@Composable
fun SettingsScreen(pin: String, device: Device, onBack: () -> Unit) {
    // استيراد من فلاشة/هارديسك (شاشة كاملة فوق الإعدادات)
    var importOpen by remember { mutableStateOf(com.turkialawlqy.aman.offline.LocalMedia.running) }
    if (importOpen) {
        LocalImportScreen(pin, onBack = { importOpen = false })
        return
    }
    var lanOpen by remember { mutableStateOf(false) }
    if (lanOpen) {
        LanShareScreen(pin, onBack = { lanOpen = false })
        return
    }
    var syncOpen by remember { mutableStateOf(false) }
    if (syncOpen) {
        TvSyncScreen(device.name, onBack = { syncOpen = false })
        return
    }
    val scope = rememberCoroutineScope()
    var saveWatched by remember(device) { mutableStateOf(device.saveWatched) }
    var autoDownload by remember(device) { mutableStateOf(device.autoDownload) }
    var quality by remember(device) { mutableStateOf(device.downloadQuality) }
    var volumeId by remember(device) { mutableStateOf(device.storageVolume ?: OfflineStore.PRIMARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var volumes by remember { mutableStateOf<List<StorageOption>>(emptyList()) }
    var used by remember { mutableStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    val status by OfflineDownloader.status.collectAsState()
    val savedIds by OfflineStore.completeIds.collectAsState()
    val first = remember { FocusRequester() }

    // تحديث المساحات كل 5 ثواني
    LaunchedEffect(Unit) {
        while (true) {
            // الفلاشة ممكن تكون بطيئة → نقراها بالخلفية عشان الشاشة ما تعلق
            val (v, u) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OfflineStore.volumes() to OfflineStore.usedBytes() }
            volumes = v
            used = u
            delay(5000)
        }
    }

    fun save() {
        message = "جاري الحفظ…"
        scope.launch {
            val ok = TvRepository.updateSettings(pin, saveWatched, autoDownload, quality, volumeId)
            message = if (ok) "تم الحفظ" else "تعذر الحفظ. تأكد من الإنترنت."
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().screenBackground(),
        contentPadding = PaddingValues(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الإعدادات", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(message ?: "", color = Accent, fontSize = 18.sp)
            }
        }

        item {
            WatchTimeSection(pin, device) { message = it }
        }

        item {
            val update by com.turkialawlqy.aman.control.AppUpdater.status.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            val hint = when (val u = update) {
                is com.turkialawlqy.aman.control.AppUpdater.Status.Ready -> "إصدار ${u.release.versionName} جاهز للتثبيت" +
                    (if (u.release.notes.isNotBlank()) " — ${u.release.notes}" else "")
                is com.turkialawlqy.aman.control.AppUpdater.Status.Downloading -> "جاري تحميل ${u.release.versionName}… ${u.percent}%"
                is com.turkialawlqy.aman.control.AppUpdater.Status.Failed -> "تعذر تحميل ${u.release.versionName}: ${u.message}"
                else -> "عندك آخر إصدار (${com.turkialawlqy.aman.BuildConfig.VERSION_NAME})"
            }
            SettingRow("تحديث التطبيق", hint) {
                if (update is com.turkialawlqy.aman.control.AppUpdater.Status.Ready) {
                    FocusButton("تثبيت", { com.turkialawlqy.aman.control.AppUpdater.install(context) })
                } else {
                    FocusButton("تحقق الآن", { com.turkialawlqy.aman.control.AppUpdater.checkNow() })
                }
            }
        }

        item {
            SettingRow(
                "استيراد من فلاشة / هارديسك",
                "وصّل الفلاشة بالتلفزيون واختر مجلد — ينضاف كقائمة (مسلسل) أو للرئيسية، ويبان في لوحة الأهل",
            ) {
                FocusButton("استيراد", { importOpen = true })
            }
        }
        item {
            SettingRow(
                "استقبال من الكمبيوتر أو الجوال",
                "انقل فيديوهات على نفس الشبكة بدون ما تطلع الفلاشة — من المتصفح أو من Explorer",
            ) {
                FocusButton("فتح", { lanOpen = true })
            }
        }
        item {
            SettingRow(
                "نقل المحفوظ بين تلفزيونين",
                "تلفزيون فيه فيديوهات محفوظة؟ انقلها لتلفزيون ثاني على نفس الشبكة بدون ما تتحمّل من الإنترنت",
            ) {
                FocusButton("فتح", { syncOpen = true })
            }
        }
        item {
            SettingRow(
                "حفظ الفيديوهات اللي تمت مشاهدتها",
                "إذا انعاد الفيديو يشتغل من الذاكرة بدون استهلاك إنترنت",
            ) {
                FocusButton(if (saveWatched) "مفعّل" else "متوقف", {
                    saveWatched = !saveWatched; save()
                }, Modifier.focusRequester(first), selected = saveWatched)
            }
        }
        item {
            SettingRow(
                "تحميل المحتوى مسبقاً",
                "يحمّل الفيديوهات المضافة + آخر ${device.collectionDownloadLimit} من كل قائمة وقناة",
            ) {
                FocusButton(if (autoDownload) "مفعّل" else "متوقف", {
                    autoDownload = !autoDownload; save()
                }, selected = autoDownload)
            }
        }
        item {
            SettingRow("جودة الحفظ والتحميل", "كل ما قلّت الجودة وفّرت إنترنت ومساحة") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(360, 480, 720).forEach { q ->
                        FocusButton("${q}p", { quality = q; save() }, selected = quality == q)
                    }
                }
            }
        }

        item {
            Text("مكان الحفظ", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp))
            Text("تغيير المكان يحذف المحفوظ في المكان القديم", color = TextDim, fontSize = 15.sp)
        }
        volumes.forEach { v ->
            item(v.id) {
                FocusButton(
                    "${if (v.id == volumeId) "● " else "○ "}${v.label} — متاح ${formatBytes(v.free)} من ${formatBytes(v.total)}",
                    { volumeId = v.id; save() },
                    Modifier.fillMaxWidth(),
                    selected = v.id == volumeId,
                )
            }
        }

        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("المحفوظ حالياً", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${savedIds.size} فيديو — ${formatBytes(used)}", color = TextDim, fontSize = 18.sp)
                if (status != null) Text(status!!, color = Accent, fontSize = 16.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton(if (confirmClear) "اضغط مرة ثانية للتأكيد" else "حذف كل المحفوظ", {
                    if (confirmClear) {
                        OfflineDownloader.clearAll()
                        confirmClear = false
                        message = "تم حذف المحفوظ"
                    } else confirmClear = true
                })
                FocusButton("رجوع", onBack)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** وقت المشاهدة: إضافة وقت (مكافأة) ومؤقت النوم — من التلفزيون بعد الرمز */
@Composable
private fun WatchTimeSection(pin: String, device: Device, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val used by com.turkialawlqy.aman.control.WatchTracker.usedSec.collectAsState()
    val profile = com.turkialawlqy.aman.control.ProfileStore.currentProfile()
    val st = com.turkialawlqy.aman.control.ParentalRules.evaluate(device, used, profile)
    fun act(action: String, minutes: Int, done: String) {
        onMessage("جاري الحفظ…")
        scope.launch {
            val ok = TvRepository.parentAction(pin, action, minutes)
            onMessage(if (ok) done else "تعذر الحفظ. تأكد من الإنترنت.")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val limit = profile?.dailyLimitMin ?: device.dailyLimitMin
        val summary = buildString {
            if (profile != null) append("${profile.avatar} ${profile.name}: ")
            append("شاهد اليوم ${used / 60} دقيقة")
            if (limit != null) append(" من $limit")
            if (st.blocked) append(" — ${st.title}")
            else com.turkialawlqy.aman.control.ParentalRules.remainingLabel(st.remainingSec)?.let { append(" — $it") }
        }
        SettingRow("وقت المشاهدة", summary) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("+15 دقيقة", { act("bonus", 15, "تمت إضافة 15 دقيقة") })
                FocusButton("+30 دقيقة", { act("bonus", 30, "تمت إضافة 30 دقيقة") })
            }
        }
        SettingRow("مؤقت النوم", "يكمّل الفيديو الحالي ثم يقفل التلفزيون") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("الآن", { act("sleep", 0, "تم تفعيل وقت النوم") })
                FocusButton("15 د", { act("sleep", 15, "يقفل بعد 15 دقيقة") })
                FocusButton("30 د", { act("sleep", 30, "يقفل بعد 30 دقيقة") })
                if (device.sleepAt != null) FocusButton("إلغاء", { act("sleep_cancel", 0, "تم إلغاء مؤقت النوم") })
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String, control: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = TextDim, fontSize = 15.sp, textAlign = TextAlign.Start)
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}
