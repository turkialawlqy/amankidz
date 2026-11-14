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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turkialawlqy.aman.offline.LanShare

/**
 * "استقبال من الكمبيوتر": التلفزيون يفتح سيرفر على الشبكة المحلية، وتنقل الفيديوهات
 * من المتصفح أو من Explorer (قرص شبكة) بدون ما تطلع الفلاشة. يشتغل بس والشاشة مفتوحة.
 */
@Composable
fun LanShareScreen(pin: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val st by LanShare.status.collectAsState()
    val focus = remember { FocusRequester() }
    BackHandler { onBack() }
    DisposableEffect(Unit) {
        LanShare.start(context, pin)
        view.keepScreenOn = true // ما ينطفي التلفزيون وقت النقل
        onDispose {
            LanShare.stop()
            view.keepScreenOn = false
        }
    }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("استقبال من الكمبيوتر", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("انقل فيديوهات للتلفزيون على نفس الشبكة بدون ما تطلع الفلاشة. خل هذي الشاشة مفتوحة لين يخلص النقل.",
            color = TextDim, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        val url = st.url
        if (url == null) {
            Text(st.error ?: "يجهّز…", color = Danger, fontSize = 18.sp)
        } else {
            Box(Modifier.background(Surface, RoundedCornerShape(CardRadius)).padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text(url, color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("من المتصفح (كمبيوتر أو جوال)", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("افتح الرابط فوق، اختر القائمة، واسحب الفيديوهات.", color = TextDim, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("من Explorer (ويندوز)", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("هذا الكمبيوتر ← إضافة موقع شبكة ← الصق الرابط. كل مجلد تسويه = قائمة بنفس اسمه، والملفات برا المجلدات = الرئيسية.",
                        color = TextDim, fontSize = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("الملفات", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st.files.isEmpty()) item {
                Text("بانتظار الملفات…", color = TextDim, fontSize = 15.sp)
            }
            items(st.files) { f ->
                val pct = if (f.size > 0) (f.done * 100 / f.size).toInt() else 0
                Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(CardRadius)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = TextMain, fontSize = 15.sp, maxLines = 1)
                        Text(listOfNotNull(f.folder?.let { "قائمة: $it" }, formatBytes(f.size)).joinToString("  ·  "),
                            color = TextDim, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        when (f.status) {
                            "يستقبل" -> "يستقبل $pct%"
                            "تعذر" -> "تعذر" + (f.error?.let { ": $it" } ?: "")
                            else -> f.status
                        },
                        color = when (f.status) { "تم" -> Accent; "تعذر" -> Danger; else -> TextMain },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FocusButton("إيقاف ورجوع", onBack, Modifier.focusRequester(focus), fontSize = 17)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
