package com.turkialawlqy.aman.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.produceState
import com.turkialawlqy.aman.control.ParentalRules
import com.turkialawlqy.aman.control.Notifier
import com.turkialawlqy.aman.control.ProfileStore
import com.turkialawlqy.aman.control.WatchTracker
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // كل ما انفتح التطبيق من جديد: الطفل يختار صورته
        if (savedInstanceState == null) ProfileStore.clear()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Surface), typography = AppTypography) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.turkialawlqy.aman.control.AppUpdater.lastInteraction = System.currentTimeMillis()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // تحديث احتياطي عند الرجوع للتطبيق
        TvRepository.refresh()
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val state by TvRepository.state.collectAsState()
    var openItem by remember { mutableStateOf<ContentItem?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsPin by remember { mutableStateOf<String?>(null) } // null = لم يُدخل الرمز بعد
    var searchOpen by remember { mutableStateOf(false) }
    var actor by remember { mutableStateOf<com.turkialawlqy.aman.data.CastMember?>(null) } // صفحة ممثل
    var pinFor by remember { mutableStateOf<com.turkialawlqy.aman.data.ChildProfile?>(null) } // طفل عليه رمز
    val used by WatchTracker.usedSec.collectAsState()
    val edu by WatchTracker.eduSec.collectAsState()
    val tvMessage by com.turkialawlqy.aman.control.TvMessages.current.collectAsState()
    val profileId by ProfileStore.current.collectAsState()
    // قواعد وقت المشاهدة — نعيد الحساب كل 15 ثانية (الساعة تمشي)
    val rules by produceState<ParentalRules.Status?>(null, state, used, edu, profileId) {
        while (true) {
            value = (state as? TvState.Ready)?.let { ParentalRules.current(it) }
            kotlinx.coroutines.delay(15_000)
        }
    }
    // إشعار الأب لما يخلص الوقت (مرة باليوم)
    LaunchedEffect(rules?.reason, profileId) {
        if (rules?.reason == "limit") Notifier.limitReached(profileId, WatchTracker.usedTodaySec())
    }
    // المحتوى المخصص للطفل الحالي
    val allVisible = (state as? TvState.Ready)?.visibleItems(profileId).orEmpty()
    // خلص وقت الترفيه؟ التعليمي يبقى متاح 🎓
    val eduTop = allVisible.filter { it.parentId == null && it.educational }.map { it.id }.toSet()
    val eduOnly = rules?.blocked == true && rules?.reason == "limit" && eduTop.isNotEmpty()
    val visible = if (eduOnly) allVisible.filter { it.id in eduTop || it.parentId in eduTop } else allVisible

    // لو الأب حذف القائمة/القناة المفتوحة أو قفل الجهاز → نرجع فوراً
    LaunchedEffect(state) {
        val s = state
        val current = openItem
        if (current != null && (s !is TvState.Ready || s.device.locked || visible.none { it.id == current.id })) {
            openItem = null
        } else if (current != null && s is TvState.Ready) {
            // تحديث العنوان لو الأب عدّله
            visible.firstOrNull { it.id == current.id }?.let { if (it != current) openItem = it }
        }
    }
    LaunchedEffect(profileId) { openItem = null; searchOpen = false; actor = null }

    fun closeSettings() {
        settingsOpen = false
        settingsPin = null
    }

    // رجوع من الصفحة الرئيسية: أول مرة يطلع من ملف الطفل، وبعدها يسأل "تطلع من التطبيق؟"
    // (مسجّل قبل الباقي عشان الشاشات المفتوحة — قائمة/بحث/إعدادات — تاخذ الأولوية)
    var exitAsk by remember { mutableStateOf(false) }
    val homeLevel = !settingsOpen && openItem == null && !searchOpen && pinFor == null && !exitAsk && actor == null
    BackHandler(enabled = homeLevel) {
        val r = state as? TvState.Ready
        if (r != null && r.profiles.isNotEmpty() && r.profile(profileId) != null) ProfileStore.clear()
        else exitAsk = true
    }

    BackHandler(enabled = settingsOpen) { closeSettings() }
    BackHandler(enabled = !settingsOpen && openItem != null && actor == null) { openItem = null }
    BackHandler(enabled = !settingsOpen && actor != null) { actor = null }
    BackHandler(enabled = !settingsOpen && openItem == null && searchOpen) { searchOpen = false }
    BackHandler(enabled = pinFor != null) { pinFor = null }

    // الثيم: ثيم الطفل الحالي ← ثيم الجهاز (يتغير فوراً لو الأب غيّره من اللوحة)
    val readyState = state as? TvState.Ready
    val activeTheme = readyState?.let { r -> r.profile(profileId)?.theme ?: r.device.theme }
    LaunchedEffect(activeTheme, profileId, readyState != null) {
        if (readyState != null && (readyState.profile(profileId) != null || readyState.profiles.isEmpty())) AppThemes.apply(activeTheme)
    }

    when (val s = state) {
        is TvState.Loading -> LoadingScreen()
        is TvState.Error -> MessageScreen("حدث خطأ", s.message, "إعادة المحاولة") { TvRepository.retry() }
        is TvState.Pairing -> PairingScreen(s.deviceCode, s.code, s.expiresAtMs)
        is TvState.Ready -> when {
            settingsOpen && settingsPin == null ->
                PinScreen(s.device, onSuccess = { settingsPin = it }, onBack = { closeSettings() })
            settingsOpen -> SettingsScreen(settingsPin!!, s.device, onBack = { closeSettings() })
            s.device.locked -> MessageScreen("التلفزيون مقفل", "ولي الأمر أوقف المشاهدة مؤقتاً.")
            s.profiles.isNotEmpty() && s.profile(profileId) == null && pinFor != null ->
                ProfilePinScreen(
                    pinFor!!,
                    onSuccess = { ProfileStore.select(pinFor!!.id); pinFor = null },
                    onBack = { pinFor = null },
                )
            s.profiles.isNotEmpty() && s.profile(profileId) == null ->
                ProfilePickerScreen(s.device.name, s.profiles, defaultTheme = s.device.theme) { p ->
                    if (p.pin.isNullOrBlank()) ProfileStore.select(p.id) else pinFor = p
                }
            rules?.blocked == true && !eduOnly -> MessageScreen(
                rules!!.title, rules!!.message,
                actionLabel = "ولي الأمر", onAction = { settingsOpen = true },
            )
            actor != null -> ActorScreen(actor!!, visible) { openItem = it; actor = null }
            openItem != null -> CollectionScreen(
                item = openItem!!,
                children = visible.filter { it.parentId == openItem!!.id }.sortedWith(com.turkialawlqy.aman.data.EpisodeOrder),
                onOpenActor = { actor = it },
            )
            searchOpen -> SearchScreen(
                items = visible,
                onOpenCollection = { openItem = it },
                onBack = { searchOpen = false },
            )
            else -> HomeScreen(
                device = s.device,
                items = visible,
                profile = s.profile(profileId),
                onSwitchProfile = if (s.profiles.size > 1) ({ ProfileStore.clear() }) else null,
                onSearch = if (s.device.searchEnabled) ({ searchOpen = true }) else null,
                banner = when {
                    eduOnly -> "خلص وقت الترفيه اليوم — المحتوى التعليمي متاح"
                    WatchTracker.earnedMinutes(s.device) > 0 ->
                        "كسبت ${WatchTracker.earnedMinutes(s.device)} دقيقة ترفيه من التعلّم اليوم"
                    else -> com.turkialawlqy.aman.control.NewEpisodes.message()
                },
                offline = s.offline,
                remainingLabel = ParentalRules.remainingLabel(rules?.remainingSec),
                onOpenSettings = { settingsOpen = true },
                onOpenCollection = { openItem = it },
                categories = s.categories,
                // القنوات المباشرة (تختفي لو خلص وقت الترفيه)
                channels = if (eduOnly) emptyList() else s.visibleChannels(profileId),
                libraries = if (eduOnly) emptyList() else s.libraries(profileId),
            )
        }
    }

    // رسالة من الأب
    tvMessage?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.turkialawlqy.aman.control.TvMessages.dismiss() },
            containerColor = Surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius),
            tonalElevation = androidx.compose.ui.unit.Dp(0f),
            titleContentColor = TextMain,
            textContentColor = TextMain,
            confirmButton = {
                FocusButton("حسناً", { com.turkialawlqy.aman.control.TvMessages.dismiss() }, fontSize = 22)
            },
            title = { androidx.compose.material3.Text("رسالة من ${m.from ?: "ولي الأمر"}", fontSize = 26.sp) },
            text = { androidx.compose.material3.Text(m.text, fontSize = 32.sp) },
        )
    }

    // تأكيد الخروج من التطبيق ("إلغاء" هو الافتراضي عشان ضغطة بالغلط ما تطلّعه)
    if (exitAsk) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        BackHandler { exitAsk = false } // رجوع = "لا"
        ExitScreen(onYes = { exitAsk = false; activity?.finish() }, onNo = { exitAsk = false })
    }

    // تحديث جديد جاهز: يسأل (ثبّت الآن / لاحقاً) — ما يثبّت بدون موافقة
    val updatePrompt by com.turkialawlqy.aman.control.AppUpdater.prompt.collectAsState()
    updatePrompt?.let { UpdatePrompt(it) }
    // التثبيت ما تم → نوضح السبب (بدل ما تختفي الرسالة بدون شي)
    val updateError by com.turkialawlqy.aman.control.AppUpdater.installError.collectAsState()
    updateError?.let { UpdateError(it) }
}

@androidx.compose.runtime.Composable
private fun UpdateError(message: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var saved by remember { mutableStateOf<String?>(null) }
    val hasFile = remember { com.turkialawlqy.aman.control.AppUpdater.pendingFile() != null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { com.turkialawlqy.aman.control.AppUpdater.clearInstallError() },
        containerColor = Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius),
        tonalElevation = androidx.compose.ui.unit.Dp(0f),
        confirmButton = {
            if (hasFile && saved == null) FocusButton("احفظ التحديث في التنزيلات", {
                saved = com.turkialawlqy.aman.control.AppUpdater.exportToDownloads(context) ?: "تعذر الحفظ"
            }, androidx.compose.ui.Modifier.focusRequester(focus), fontSize = 18, primary = true)
        },
        dismissButton = {
            FocusButton("حسناً", { com.turkialawlqy.aman.control.AppUpdater.clearInstallError() },
                if (!hasFile || saved != null) androidx.compose.ui.Modifier.focusRequester(focus) else androidx.compose.ui.Modifier, fontSize = 18)
        },
        title = { androidx.compose.material3.Text("ما تم التحديث", color = TextMain, fontSize = 24.sp) },
        text = {
            androidx.compose.material3.Text(
                message + (saved?.let { "\n\nانحفظ الملف في: $it\nافتحه من مدير الملفات (مثل File Commander أو X-plore) وثبّته." } ?: ""),
                color = TextDim, fontSize = 18.sp,
            )
        },
    )
    LaunchedEffect(saved) { runCatching { focus.requestFocus() } }
}

@androidx.compose.runtime.Composable
private fun UpdatePrompt(r: com.turkialawlqy.aman.control.AppUpdater.Release) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canInstall = com.turkialawlqy.aman.control.AppUpdater.canInstall(context)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { com.turkialawlqy.aman.control.AppUpdater.snooze() },
        containerColor = Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius),
        tonalElevation = androidx.compose.ui.unit.Dp(0f),
        confirmButton = {
            FocusButton(
                if (canInstall) "ثبّت الآن" else "اسمح بالتحديث",
                { com.turkialawlqy.aman.control.AppUpdater.installFromPrompt(context) },
                androidx.compose.ui.Modifier.focusRequester(focus), fontSize = 20, primary = true,
            )
        },
        dismissButton = { FocusButton("لاحقاً", { com.turkialawlqy.aman.control.AppUpdater.snooze() }, fontSize = 18) },
        title = { androidx.compose.material3.Text("تحديث جديد ${r.versionName}", color = TextMain, fontSize = 26.sp) },
        text = {
            androidx.compose.material3.Text(
                (if (r.notes.isNotBlank()) r.notes + "\n\n" else "") +
                    if (canInstall) "اضغط \"ثبّت الآن\" ووافق على التثبيت — التطبيق بيتقفل دقيقة ويرجع بالنسخة الجديدة."
                    else "أول مرة بس: لازم تسمح للتطبيق يثبّت تحديثاته. اضغط الزر وفعّل \"السماح من هذا المصدر\".",
                color = TextDim, fontSize = 20.sp,
            )
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
