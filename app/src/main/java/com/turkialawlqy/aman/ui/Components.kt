package com.turkialawlqy.aman.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// ------------------------------------------------------------------ الألوان (من الثيم الحالي)
// كلها تقرأ من AppThemes.current → تغيير الثيم من لوحة الأب يعيد التلوين فوراً

val Background: Color get() = AppThemes.current.background
val BackgroundTop: Color get() = AppThemes.current.backgroundTop
val Surface: Color get() = AppThemes.current.surface
val Surface2: Color get() = AppThemes.current.surface2
val Accent: Color get() = AppThemes.current.accent
val Accent2: Color get() = AppThemes.current.accent2
val OnAccent: Color get() = AppThemes.current.onAccent
val TextMain: Color get() = AppThemes.current.textMain
val TextDim: Color get() = AppThemes.current.textDim
val OfflineGreen = Color(0xFF1FB86E)
val Danger = Color(0xFFFF6B7A)
val Glass: Color get() = AppThemes.current.surface2
val GlassBorder: Color get() = Color.Transparent

/** زوايا البطاقات والأزرار حسب الثيم */
val CardRadius: Dp get() = AppThemes.current.radius
fun buttonShape(): androidx.compose.ui.graphics.Shape =
    if (AppThemes.current.pillButtons) RoundedCornerShape(50) else RoundedCornerShape(AppThemes.current.radius)

/** لون الثيم كفرشاة (سادة — بدون تدرّج) */
fun accentBrush(): Brush = androidx.compose.ui.graphics.SolidColor(Accent)

const val OFFLINE_BADGE = "محفوظ"

/** خلفية الشاشات: تدرّج الثيم + زخرفته (نجوم/فقاعات/…) */
fun Modifier.screenBackground(): Modifier = drawBehind { drawThemeBackdrop(AppThemes.current) }

/** لوح سادة (للبطاقات والأشرطة) */
fun Modifier.glass(radius: Dp = 24.dp): Modifier =
    background(Surface, RoundedCornerShape(minOf(radius, CardRadius * 2)))

// ------------------------------------------------------------------ عناصر مشتركة

/** شعار التطبيق: كلمة "أمان" بلون الثيم */
@Composable
fun BrandMark(size: Dp = 44.dp) {
    Text("أمان", color = Accent, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Black, maxLines = 1)
}

/** صورة الطفل داخل دائرة بإطار متدرّج */
@Composable
fun AvatarBubble(avatar: String, size: Dp = 52.dp, ring: Boolean = true) {
    Box(
        Modifier
            .size(size)
            .then(if (ring) Modifier.border(2.dp, Accent, CircleShape).padding(4.dp) else Modifier)
            .background(Surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(avatar, fontSize = (size.value * 0.48f).sp) }
}

/** عنوان قسم: "▶ أكمل المشاهدة  6" */
@Composable
fun SectionTitle(title: String, count: Int? = null, modifier: Modifier = Modifier, horizontalPadding: Dp = 56.dp, compact: Boolean = false) {
    Row(modifier.padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextMain, fontSize = if (compact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        if (count != null && count > 0) {
            Spacer(Modifier.width(10.dp))
            Text(count.toString(), color = TextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** شارة صغيرة غير قابلة للتركيز (مثلاً "باقي 20 دقيقة") */
@Composable
fun InfoChip(text: String, color: Color = TextMain, background: Color = Color.Unspecified) {
    val bg = if (background == Color.Unspecified) Surface2 else background
    Text(
        text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(CardRadius))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** بطاقة قابلة للتركيز بالريموت: تكبر ويظهر لها إطار متدرّج وتوهج وزر تشغيل */
@Composable
fun FocusTile(
    title: String,
    imageUrl: String?,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    subtitle: String? = null,
    offlineBadge: String? = null, // علامة "متاح بدون إنترنت"
    progress: Float? = null,       // نسبة المشاهدة (شريط تحت الصورة)
    dimmed: Boolean = false,       // غير متاح حالياً (مثلاً يحتاج إنترنت)
    downloadPercent: Int? = null,  // يتحمّل الآن → دائرة بالنسبة
    queued: Boolean = false,       // بالطابور → ⏳
    onFocused: (() -> Unit)? = null, // لما يتحدد (الرئيسية تغيّر الخلفية الكبيرة)
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "tileScale")
    val shape = if (circle) CircleShape else RoundedCornerShape(CardRadius)

    Column(
        modifier = modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (dimmed && !focused) 0.4f else 1f }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (circle) 1f else 16f / 9f)
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
                .clip(shape)
                .background(Surface)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (subtitle != null && !circle) {
                Text(
                    subtitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (progress != null && progress > 0.02f && !circle) {
                // شريط المشاهدة ملاصق لأسفل الصورة (مثل نتفلكس)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF5A5A5A))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(Accent)
                    )
                }
            }
            // دائرة التحميل في الركن الأيمن الأعلى
            if (downloadPercent != null || queued) {
                DownloadRing(
                    percent = downloadPercent,
                    modifier = Modifier
                        .align(androidx.compose.ui.AbsoluteAlignment.TopRight)
                        .padding(8.dp),
                )
            }
            if (offlineBadge != null && downloadPercent == null) {
                Text(
                    offlineBadge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(if (circle) Alignment.BottomCenter else Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = title,
            color = if (focused) TextMain else TextDim,
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}

fun formatDuration(sec: Long): String? {
    if (sec <= 0) return null
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** زر قابل للتركيز بالريموت (كبسولة زجاجية، تتلوّن بتدرّج الثيم عند التركيز) */
@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fontSize: Int = 18,
    primary: Boolean = false, // زر رئيسي (ملوّن حتى بدون تركيز)
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "btnScale")
    // مسطّح: المحدد أبيض بنص غامق، الرئيسي بلون الثيم، والباقي رمادي سادة
    val bg by animateColorAsState(
        when {
            focused -> Color.White
            primary -> Accent
            selected -> Surface2
            else -> Surface2
        },
        label = "btnBg",
    )
    val shape = buttonShape()
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clip(shape)
            .background(bg)
            .then(if (selected && !focused) Modifier.border(2.dp, Accent, shape) else Modifier)
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                focused -> Color(0xFF111111)
                primary -> OnAccent
                else -> TextMain
            },
            fontSize = fontSize.sp,
            fontWeight = if (focused || primary || selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 ميجا"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f جيجا".format(mb / 1024) else "%.0f ميجا".format(mb)
}

// ------------------------------------------------------------------ لوحة الرمز (مشتركة)

/** رقم من أزرار الريموت (0-9 العادية أو لوحة الأرقام) — null لو مو رقم */
fun remoteDigit(ev: androidx.compose.ui.input.key.KeyEvent): Char? {
    if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return null
    val code = ev.nativeKeyEvent.keyCode
    return when (code) {
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> '0' + (code - android.view.KeyEvent.KEYCODE_0)
        in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
        else -> null
    }
}

/** زر المسح في الريموت (لو موجود) */
fun remoteDelete(ev: androidx.compose.ui.input.key.KeyEvent): Boolean =
    ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
        ev.nativeKeyEvent.keyCode in setOf(android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_CLEAR)

/** نقاط الرمز (تتعبّى بدون ما يبان الرقم) */
@Composable
fun PinDots(length: Int, error: Boolean = false) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(22.dp)
                        .then(if (i < length) Modifier.background(accentBrush(), CircleShape) else Modifier.background(Surface2, CircleShape))
                        .border(2.dp, if (error) Danger else Color.White.copy(alpha = 0.12f), CircleShape)
                )
            }
        }
    }
}

/**
 * لوحة أرقام على الشاشة: ترتيب الأرقام يتغير كل مرة تنفتح،
 * عشان اللي يتابع حركة التحديد ما يعرف الرمز. والأفضل أزرار الأرقام في الريموت مباشرة.
 */
@Composable
fun PinKeypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    firstKey: androidx.compose.ui.focus.FocusRequester,
    shuffleKey: Any? = Unit,
) {
    val digits = remember(shuffleKey) { "0123456789".toList().shuffled() }
    val keyW = 84.dp
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
    ) {
        Column(
            Modifier.glass(28.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (r in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val d = digits[r * 3 + c]
                        FocusButton(
                            d.toString(), { onDigit(d) },
                            modifier = Modifier.width(keyW)
                                .then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                            fontSize = 24,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("⌫", onDelete, Modifier.width(keyW), fontSize = 22)
                FocusButton(digits[9].toString(), { onDigit(digits[9]) }, Modifier.width(keyW), fontSize = 24)
                FocusButton("رجوع", onBack, Modifier.width(keyW), fontSize = 16)
            }
        }
    }
}

/** دائرة نسبة التحميل (أو ⏳ لو بالطابور) */
@Composable
fun DownloadRing(percent: Int?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val target = (percent ?: 0).coerceIn(0, 100) / 100f
    val animated by animateFloatAsState(target, label = "dlRing")
    Box(
        modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (percent != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawArc(Color.White.copy(alpha = 0.22f), 0f, 360f, false, style = stroke)
                drawArc(accentBrush(), -90f, 360f * animated, false, style = stroke)
            }
            Text("$percent%", color = Color.White, fontSize = (size.value * 0.25f).sp, fontWeight = FontWeight.Bold)
        } else {
            Text("⏳", fontSize = (size.value * 0.4f).sp)
        }
    }
}
