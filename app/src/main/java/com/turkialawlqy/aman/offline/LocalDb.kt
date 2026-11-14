package com.turkialawlqy.aman.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.turkialawlqy.aman.App

/**
 * قاعدة بيانات التطبيق المحلية (على التلفزيون نفسه):
 * - api_cache: كل ردود سيرفر الاشتراك (التصنيفات، الأفلام، المسلسلات، الحلقات، الدليل) — تفتح فوراً
 *   وتشتغل حتى لو السيرفر أو النت وقف، وما نطلب السيرفر إلا لما تقدم البيانات.
 * - follows: المسلسلات اللي تتابعها من مكتبة الاشتراك (وحلقاتها المعروفة).
 * ما فيها شي يمر على Supabase.
 */
object LocalDb : SQLiteOpenHelper(App.instance, "aman_local.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE api_cache (k TEXT PRIMARY KEY, body TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE follows (series_key TEXT PRIMARY KEY, source_id TEXT NOT NULL, series_id TEXT NOT NULL, " +
                "title TEXT NOT NULL, cover TEXT, known TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    // ------------------------------------------------------------------ ردود الاشتراك
    /** (النص، وقت الحفظ) */
    fun cached(key: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT body, at FROM api_cache WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }

    fun putCache(key: String, body: String) {
        writableDatabase.insertWithOnConflict("api_cache", null, ContentValues().apply {
            put("k", key); put("body", body); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** ننظف القديم (أكثر من 30 يوم) عشان ما تكبر */
    fun trim() = runCatching {
        writableDatabase.delete("api_cache", "at < ?", arrayOf((System.currentTimeMillis() - 30L * 24 * 3600_000).toString()))
    }

    fun clearSource(sourceId: String) = runCatching { writableDatabase.delete("api_cache", "k LIKE ?", arrayOf("$sourceId|%")) }

    // ------------------------------------------------------------------ المتابعة
    data class Follow(val seriesKey: String, val sourceId: String, val seriesId: String, val title: String, val cover: String?, val known: Set<String>, val at: Long)

    fun follows(): List<Follow> =
        readableDatabase.rawQuery("SELECT series_key, source_id, series_id, title, cover, known, at FROM follows ORDER BY at DESC", null).use { c ->
            val out = mutableListOf<Follow>()
            while (c.moveToNext()) out += Follow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5).split(',').filter { it.isNotBlank() }.toSet(), c.getLong(6))
            out
        }

    fun isFollowed(seriesKey: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM follows WHERE series_key = ?", arrayOf(seriesKey)).use { it.moveToFirst() }

    fun follow(f: Follow) {
        writableDatabase.insertWithOnConflict("follows", null, ContentValues().apply {
            put("series_key", f.seriesKey); put("source_id", f.sourceId); put("series_id", f.seriesId)
            put("title", f.title); put("cover", f.cover); put("known", f.known.joinToString(",")); put("at", f.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unfollow(seriesKey: String) { writableDatabase.delete("follows", "series_key = ?", arrayOf(seriesKey)) }

    fun setKnown(seriesKey: String, known: Set<String>) {
        writableDatabase.update("follows", ContentValues().apply { put("known", known.joinToString(",")) }, "series_key = ?", arrayOf(seriesKey))
    }
}
