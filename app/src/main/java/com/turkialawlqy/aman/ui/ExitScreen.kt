package com.turkialawlqy.aman.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * شاشة "الخروج من التطبيق" (مثل منصات البث): خلفية كحلية كاملة، أيقونة تشغيل،
 * وزرين كبسولة "نعم" و"لا" — التحديد يبدأ على "لا" عشان الطفل ما يطلع بالغلط.
 */
@Composable
fun ExitScreen(onYes: () -> Unit, onNo: () -> Unit) {
    val noFocus = remember { FocusRequester() }
    val yesFocus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color(0xF22B3048)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PowerIcon()
            Spacer(Modifier.height(22.dp))
            Text("الخروج من التطبيق", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text("هل تريد إغلاق التطبيق؟", color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // التنقل محصور بين الزرين (ما يروح للشاشة اللي ورا)
                ExitPill("نعم", onYes, Modifier.focusRequester(yesFocus).focusProperties {
                    up = yesFocus; down = noFocus; left = yesFocus; right = yesFocus
                })
                ExitPill("لا", onNo, Modifier.focusRequester(noFocus).focusProperties {
                    up = yesFocus; down = noFocus; left = noFocus; right = noFocus
                })
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
}

/** أيقونة التشغيل/الإيقاف: قوس دائري + خط فيروزي فوق */
@Composable
private fun PowerIcon() {
    Canvas(Modifier.size(64.dp)) {
        val w = 4.dp.toPx()
        val inset = w / 2 + 2.dp.toPx()
        drawArc(
            color = Color.White, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        drawLine(
            Color(0xFF36D1C4), Offset(size.width / 2, 0f + w), Offset(size.width / 2, size.height * 0.45f),
            strokeWidth = w, cap = StrokeCap.Round,
        )
    }
}

/** كبسولة عريضة: المحددة بيضاء بنص غامق، الثانية بنفسجية فاتحة شفافة بإطار */
@Composable
private fun ExitPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "exitPill")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .width(300.dp)
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color(0x998F98D8), shape)
            .border(1.5.dp, if (focused) Color.White else Color.White.copy(alpha = 0.85f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color(0xFF2B3048) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
