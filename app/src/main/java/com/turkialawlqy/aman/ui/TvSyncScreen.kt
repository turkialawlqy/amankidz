package com.turkialawlqy.aman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.offline.TvSync
import kotlinx.coroutines.launch

/**
 * نقل المحفوظ بين تلفزيونين على نفس الشبكة.
 * التلفزيون اللي عنده المحفوظ: "أرسل" ويطلع رمز. الثاني: "استقبل" ويختاره ويكتب الرمز.
 */
@Composable
fun TvSyncScreen(deviceName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var mode by remember { mutableStateOf<String?>(null) } // send | receive
    val first = remember { FocusRequester() }
    BackHandler { if (mode != null) mode = null else onBack() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            TvSync.stopSending(context)
            TvSync.stopDiscovery(context)
            view.keepScreenOn = false
        }
    }
    LaunchedEffect(mode) { if (mode == null) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("نقل المحفوظ بين تلفزيونين", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("الفيديوهات المحفوظة تنتقل على شبكة البيت مباشرة — بدون ما تتحمّل من الإنترنت مرة ثانية. لازم التلفزيونين على نفس الواي فاي.",
            color = TextDim, fontSize = 16.sp)
        Spacer(Modifier.height(22.dp))
        when (mode) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FocusButton("أرسل من هذا التلفزيون", { mode = "send" }, Modifier.focusRequester(first))
                FocusButton("استقبل لهذا التلفزيون", { mode = "receive" })
            }
            "send" -> SendPane(deviceName)
            else -> ReceivePane()
        }
    }
}

@Composable
private fun SendPane(deviceName: String) {
    val context = LocalContext.current
    val st by TvSync.sendStatus.collectAsState()
    DisposableEffect(Unit) {
        TvSync.startSending(context, deviceName)
        onDispose { TvSync.stopSending(context) }
    }
    Text("على التلفزيون الثاني: الإعدادات ← نقل المحفوظ ← استقبل، واختر هذا التلفزيون واكتب الرمز:", color = TextDim, fontSize = 18.sp)
    Spacer(Modifier.height(16.dp))
    Box(Modifier.background(Surface, RoundedCornerShape(CardRadius)).padding(horizontal = 36.dp, vertical = 18.dp)) {
        Text(st.code.ifBlank { "…" }, color = Accent, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr))
    }
    Spacer(Modifier.height(12.dp))
    st.address?.let { Text("العنوان: $it", color = TextDim, fontSize = 15.sp) }
    st.error?.let { Text(it, color = Danger, fontSize = 16.sp) }
    Spacer(Modifier.height(12.dp))
    Text(
        st.current?.let { "يرسل الحين: $it" } ?: if (st.served > 0) "انرسل ${st.served} فيديو — خل الشاشة مفتوحة لين يخلص الثاني" else "ينتظر التلفزيون الثاني…",
        color = TextMain, fontSize = 18.sp,
    )
}

@Composable
private fun ReceivePane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by TvSync.peers.collectAsState()
    val recv by TvSync.recvStatus.collectAsState()
    var peer by remember { mutableStateOf<TvSync.Peer?>(null) }
    var code by remember { mutableStateOf("") }
    var offers by remember { mutableStateOf<List<TvSync.Offer>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        TvSync.clearReceive()
        TvSync.startDiscovery(context)
        onDispose { TvSync.stopDiscovery(context) }
    }

    val p = peer
    if (recv.running || recv.finished) {
        Text(if (recv.running) "ينقل ${recv.done + 1} من ${recv.total}" else "خلص — انتقل ${recv.done} من ${recv.total}",
            color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        recv.current?.let { Text("$it · ${recv.percent}%", color = TextDim, fontSize = 17.sp) }
        recv.error?.let { Text(it, color = Danger, fontSize = 16.sp) }
        Spacer(Modifier.height(16.dp))
        if (recv.running) FocusButton("إيقاف", { TvSync.cancelReceive() })
        else FocusButton("تمام", { TvSync.clearReceive(); offers = null; peer = null })
        return
    }
    if (p == null) {
        Text("اختر التلفزيون اللي فيه المحفوظ (لازم يكون فاتح «أرسل»):", color = TextDim, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        if (peers.isEmpty()) Text("يدوّر على الشبكة…", color = TextDim, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            peers.forEach { x -> FocusButton(x.name, { peer = x; code = "" }) }
        }
        Spacer(Modifier.height(20.dp))
        Text("ما طلع؟ اكتب العنوان اللي على شاشته (مثل 192.168.1.20:8095):", color = TextDim, fontSize = 15.sp)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = manual, onValueChange = { manual = it.take(30) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent, unfocusedBorderColor = Surface2, cursorColor = Accent),
                modifier = Modifier.width(320.dp),
            )
            Spacer(Modifier.width(12.dp))
            FocusButton("اتصل", {
                val host = manual.substringBefore(':').trim()
                val port = manual.substringAfter(':', "8095").trim().toIntOrNull() ?: 8095
                if (host.isNotBlank()) { peer = TvSync.Peer(host, host, port); code = "" }
            })
        }
        return
    }
    val list = offers
    if (list == null) {
        LaunchedEffect(p) { runCatching { codeFocus.requestFocus() } }
        Text("اكتب الرمز اللي على شاشة «${p.name}»:", color = TextDim, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(4) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent, unfocusedBorderColor = Surface2, cursorColor = Accent),
                modifier = Modifier.width(200.dp).focusRequester(codeFocus),
            )
            Spacer(Modifier.width(12.dp))
            FocusButton(if (busy) "يتصل…" else "اتصل", {
                if (code.length != 4 || busy) return@FocusButton
                busy = true; err = null
                scope.launch {
                    try { offers = TvSync.offers(p, code) } catch (e: Exception) { err = e.message ?: "ما قدر يتصل" }
                    busy = false
                }
            })
            Spacer(Modifier.width(12.dp))
            FocusButton("رجوع", { peer = null })
        }
        err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Danger, fontSize = 16.sp) }
        return
    }
    val missing = list.filter { it.inMyContent && !it.haveIt }
    val notMine = list.count { !it.inMyContent }
    val gb = missing.sumOf { o -> o.entry.media.streams.sumOf { it.contentLength } } / 1_073_741_824.0
    Text("عنده ${list.size} فيديو محفوظ · ناقص عندك ${missing.size} (${String.format(java.util.Locale.US, "%.1f", gb)} جيجا)",
        color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (notMine > 0) Text("$notMine فيديو مو موجود في محتوى هذا التلفزيون — ما ينتقل", color = TextDim, fontSize = 15.sp)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (missing.isNotEmpty()) FocusButton("انقل الناقص", { TvSync.receive(p, code, missing.map { it.entry }) })
        else Text("كل شي عندك أصلاً", color = TextDim, fontSize = 18.sp)
        FocusButton("رجوع", { offers = null; peer = null })
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth()) {
        missing.take(12).forEach { Text("• ${it.entry.title}", color = TextDim, fontSize = 15.sp, maxLines = 1) }
        if (missing.size > 12) Text("و ${missing.size - 12} غيرها…", color = TextDim, fontSize = 15.sp)
    }
}
