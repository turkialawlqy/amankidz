package com.turkialawlqy.aman.ui

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.turkialawlqy.aman.R

/**
 * ثيمين بس: "شاهد" (كحلي + فيروزي، قائمة جانبية) و"نتفلكس" (أسود + أحمر، تبويبات فوق).
 * تصميم مسطّح: ألوان سادة، بدون تدرّجات ولا توهج ولا ظلال.
 * كل الألوان في التطبيق تقرأ من [AppThemes.current]، فتغيير الثيم من اللوحة يعيد الرسم فوراً.
 */
data class AppTheme(
    val id: String,
    val name: String,
    val background: Color,
    val surface: Color,       // القائمة الجانبية والبطاقات
    val surface2: Color,      // الأزرار العادية
    val accent: Color,        // اللون الرئيسي (الشعار، المحدد، شريط التقدم)
    val onAccent: Color,      // النص فوق accent
    val textMain: Color,
    val textDim: Color,
    val radius: Dp,           // زوايا البطاقات
    val pillButtons: Boolean, // أزرار كبسولة (شاهد) أو مستطيلة (نتفلكس)
    val nav: Nav,
) {
    enum class Nav { SIDE, TOP }
    enum class Decor { NONE }

    // للتوافق مع الشاشات القديمة (كلها ألوان سادة الحين)
    val backgroundTop: Color get() = background
    val glow: Color get() = accent
    val accent2: Color get() = accent
    val decor: Decor get() = Decor.NONE
}

object AppThemes {
    val shahid = AppTheme(
        id = "shahid", name = "شاهد",
        background = Color(0xFF181C2B), surface = Color(0xFF212639), surface2 = Color(0xFF2E3449),
        accent = Color(0xFF2FD3BF), onAccent = Color(0xFF0E1A24),
        textMain = Color(0xFFFFFFFF), textDim = Color(0xFFA7ADBF),
        radius = 8.dp, pillButtons = true, nav = AppTheme.Nav.SIDE,
    )
    val netflix = AppTheme(
        id = "netflix", name = "نتفلكس",
        background = Color(0xFF141414), surface = Color(0xFF1F1F1F), surface2 = Color(0xFF333333),
        accent = Color(0xFFE50914), onAccent = Color(0xFFFFFFFF),
        textMain = Color(0xFFFFFFFF), textDim = Color(0xFFB3B3B3),
        radius = 4.dp, pillButtons = false, nav = AppTheme.Nav.TOP,
    )
    val all = listOf(shahid, netflix)

    var current by mutableStateOf(shahid)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
        current = byId(prefs?.getString("id", null)) ?: shahid
    }

    /** الثيمات القديمة (night/space/stream…) تصير "شاهد" */
    fun byId(id: String?): AppTheme? = all.firstOrNull { it.id == id } ?: if (id.isNullOrBlank()) null else shahid

    /** يطبّق الثيم (ثيم الطفل ← ثيم الجهاز) ويحفظه لشاشة البداية الجاية */
    fun apply(id: String?) {
        val t = byId(id) ?: shahid
        if (t == current) return
        current = t
        prefs?.edit()?.putString("id", t.id)?.apply()
    }
}

// ------------------------------------------------------------------ الخط

val AppFont = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
    Font(R.font.tajawal_extrabold, FontWeight.Black),
)

val AppTypography: Typography = Typography().let { t ->
    fun TextStyle.f() = copy(fontFamily = AppFont)
    Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

// ------------------------------------------------------------------ الخلفية

/** خلفية سادة */
fun DrawScope.drawThemeBackdrop(t: AppTheme) {
    drawRect(t.background)
}
