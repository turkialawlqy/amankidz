package com.turkialawlqy.aman.control

import android.content.Context
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.data.ContentItem

/**
 * تنبيه "نزلت حلقة جديدة" للمسلسلات اللي تتابعها (يطلع في الرئيسية فوق).
 * نتذكر حلقات كل مسلسل؛ أي حلقة جديدة بعدها = تنبيه لمدة 12 ساعة.
 */
object NewEpisodes {
    private const val SHOW_MS = 12 * 60 * 60 * 1000L
    private val prefs get() = App.instance.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)

    @Synchronized
    fun check(items: List<ContentItem>) {
        val p = prefs
        val e = p.edit()
        for (mix in items.filter { it.kind == "mix" && it.follow }) {
            val kids = items.filter { it.parentId == mix.id }
            val ids = kids.map { it.id }.toSet()
            val known = p.getStringSet("k:${mix.id}", null)
            if (known != null) {
                val fresh = kids.filter { it.id !in known }
                if (fresh.isNotEmpty()) {
                    val msg = if (fresh.size == 1) "نزلت حلقة جديدة من «${mix.title}»" else "نزلت ${fresh.size} حلقات جديدة من «${mix.title}»"
                    e.putString("msg", msg).putLong("at", System.currentTimeMillis())
                }
            }
            if (known != ids) e.putStringSet("k:${mix.id}", ids)
        }
        e.apply()
    }

    /** تنبيه من مصدر ثاني (متابعة مسلسلات المكتبة) */
    fun notify(msg: String) { prefs.edit().putString("msg", msg).putLong("at", System.currentTimeMillis()).apply() }

    /** نص التنبيه لو لسا جديد */
    fun message(): String? {
        val p = prefs
        val at = p.getLong("at", 0)
        return if (System.currentTimeMillis() - at < SHOW_MS) p.getString("msg", null) else null
    }
}
