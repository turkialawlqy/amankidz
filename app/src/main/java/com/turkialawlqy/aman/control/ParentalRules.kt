package com.turkialawlqy.aman.control

import com.turkialawlqy.aman.data.ChildProfile
import com.turkialawlqy.aman.data.Device
import com.turkialawlqy.aman.data.TvState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * قواعد وقت المشاهدة اللي يحددها الأب:
 * مؤقت النوم ← الساعات المسموحة ← الحد اليومي (+ المكافأة).
 */
object ParentalRules {
    private const val SLEEP_HOLD_MS = 10 * 60 * 60 * 1000L // مؤقت النوم يقفل 10 ساعات أو لين يلغيه الأب

    data class Status(
        val blocked: Boolean,
        val title: String = "",
        val message: String = "",
        /** كم ثانية باقية قبل القفل (null = بدون حد) */
        val remainingSec: Long? = null,
        /** sleep | hours | limit */
        val reason: String? = null,
    )

    /** حالة الطفل الحالي على هذا الجهاز */
    fun current(state: TvState.Ready): Status =
        evaluate(
            state.device, WatchTracker.usedTodaySec(), state.profile(ProfileStore.current.value),
            earnedMin = WatchTracker.earnedMinutes(state.device),
        )

    fun today(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    fun evaluate(
        d: Device,
        usedSec: Long,
        profile: ChildProfile? = null,
        now: Long = System.currentTimeMillis(),
        earnedMin: Int = 0, // مكافأة التعلّم
    ): Status {
        val remaining = mutableListOf<Long>()

        // 1) مؤقت النوم
        val sleepAt = d.sleepAt?.let { parseIsoMillis(it) }
        if (sleepAt != null) {
            if (now >= sleepAt && now < sleepAt + SLEEP_HOLD_MS) {
                return Status(true, "وقت النوم", "تصبح على خير! نكمل بكرة إن شاء الله.", reason = "sleep")
            }
            if (sleepAt > now) remaining += (sleepAt - now) / 1000
        }

        // أيام الإجازة لها حدود وساعات خاصة (لو حددها الأب)
        val weekend = isWeekend(d, now)
        val useWeekendHours = weekend && minutesOf(d.weekendFrom) != null && minutesOf(d.weekendTo) != null
        val fromStr = if (useWeekendHours) d.weekendFrom else d.allowedFrom
        val toStr = if (useWeekendHours) d.weekendTo else d.allowedTo

        // 2) الساعات المسموحة
        val from = minutesOf(fromStr)
        val to = minutesOf(toStr)
        if (from != null && to != null && from != to) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
            val nowMin = nowSec / 60
            val inside = if (from < to) nowMin in from until to else nowMin >= from || nowMin < to
            if (!inside) {
                return Status(
                    true, "خارج وقت المشاهدة",
                    "المشاهدة مسموحة من ${pretty(fromStr!!)} إلى ${pretty(toStr!!)}",
                    reason = "hours",
                )
            }
            var untilEnd = to * 60L - nowSec
            if (untilEnd <= 0) untilEnd += 24 * 3600
            remaining += untilEnd
        }

        // 3) الحد اليومي + المكافأة
        val limit = if (weekend) {
            profile?.weekendLimitMin ?: d.weekendLimitMin ?: profile?.dailyLimitMin ?: d.dailyLimitMin
        } else {
            profile?.dailyLimitMin ?: d.dailyLimitMin
        }
        if (limit != null) {
            val bonus = if (d.bonusDay == today(now)) d.bonusMinutes else 0
            val left = (limit + bonus + earnedMin) * 60L - usedSec
            if (left <= 0) {
                val name = profile?.let { " يا ${it.name}" } ?: ""
                return Status(
                    true, "خلص وقت المشاهدة اليوم",
                    "شفت ${usedSec / 60} دقيقة اليوم$name. نشوفك بكرة!",
                    reason = "limit",
                )
            }
            remaining += left
        }

        return Status(false, remainingSec = remaining.minOrNull())
    }

    /** هل اليوم إجازة؟ (0=الأحد … 6=السبت) */
    fun isWeekend(d: Device, now: Long = System.currentTimeMillis()): Boolean {
        val dow = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1
        return dow in d.weekendDays
    }

    /** نص قصير للرئيسية: "باقي 35 دقيقة" */
    fun remainingLabel(sec: Long?): String? {
        if (sec == null || sec > 3 * 3600) return null
        val min = (sec + 59) / 60
        return when {
            min >= 60 -> "باقي ${min / 60} ساعة${if (min % 60 > 0)" و${min % 60} دقيقة" else ""}"
            else -> "باقي $min دقيقة"
        }
    }

    /** "HH:MM" → دقائق من بداية اليوم */
    fun minutesOf(hhmm: String?): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(hhmm?.trim() ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    /** هل الوقت الحالي داخل نافذة (من، إلى)؟ لو وحدة منها فاضية = مسموح دائماً */
    fun inWindow(fromStr: String?, toStr: String?, now: Long = System.currentTimeMillis()): Boolean {
        val from = minutesOf(fromStr) ?: return true
        val to = minutesOf(toStr) ?: return true
        if (from == to) return true
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val n = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (from < to) n in from until to else n >= from || n < to
    }

    /** "20:30" → "8:30 م" */
    fun pretty(hhmm: String): String {
        val total = minutesOf(hhmm) ?: return hhmm
        val h = total / 60
        val m = total % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "ص" else "م"}"
    }

    /** يقرأ وقت ISO من Supabase (مثل 2026-10-08T10:00:00.123+00:00) — يشتغل على أندرويد 7 */
    fun parseIsoMillis(s: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?\s*(Z|[+-]\d{2}(?::?\d{2})?)?""")
            .find(s) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        var ms = cal.timeInMillis
        if (g[7].isNotEmpty()) ms += g[7].padEnd(3, '0').take(3).toLong()
        val off = g[8]
        if (off.isNotEmpty() && off != "Z") {
            val sign = if (off[0] == '-') -1 else 1
            val digits = off.substring(1).replace(":", "")
            val oh = digits.take(2).toInt()
            val om = digits.drop(2).take(2).toIntOrNull() ?: 0
            ms -= sign * (oh * 60 + om) * 60_000L
        }
        return ms
    }
}
