package com.turkialawlqy.aman.offline

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import com.turkialawlqy.aman.App
import com.turkialawlqy.aman.data.ContentItem
import com.turkialawlqy.aman.data.TvRepository
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * فيديوهات من فلاشة/هارديسك موصول بالتلفزيون.
 * - تنسخ للتلفزيون (تقدر تشيل الفلاشة) أو تشتغل من الفلاشة مباشرة.
 * - تنضاف للمحتوى في الخادم (قائمة جديدة / موجودة / الرئيسية) وتبان في لوحة الأهل.
 * معرّفها: lc_<hex>  ورابطها: local:<hex>  — الملف نفسه موجود على هذا التلفزيون فقط.
 */
object LocalMedia {
    const val PREFIX = "local:"
    const val ID_PREFIX = "lc_"
    private const val TAG = "LocalMedia"
    private val EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts")

    fun isLocal(url: String?) = url?.startsWith(PREFIX) == true
    fun isLocalId(videoId: String?) = videoId?.startsWith(ID_PREFIX) == true
    fun urlFor(videoId: String) = PREFIX + videoId.removePrefix(ID_PREFIX)
    fun videoIdOf(url: String): String? = if (isLocal(url)) ID_PREFIX + url.removePrefix(PREFIX) else null

    // ------------------------------------------------------------------ الفهرس (أي ملف لكل فيديو)
    @Serializable
    private data class Entry(
        val source: String,      // مسار الملف المنسوخ أو رابط الفلاشة (content://… أو file://…)
        val copied: Boolean,
        val origin: String,      // رابط الملف الأصلي (عشان ما نستورده مرتين)
        val title: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs get() = App.instance.getSharedPreferences("localmedia", Context.MODE_PRIVATE)

    @Synchronized
    private fun index(): MutableMap<String, Entry> =
        prefs.all.mapNotNull { (k, v) ->
            runCatching { k to json.decodeFromString<Entry>(v as String) }.getOrNull()
        }.toMap().toMutableMap()

    @Synchronized
    private fun putEntry(id: String, e: Entry) = prefs.edit().putString(id, json.encodeToString(Entry.serializer(), e)).apply()

    @Synchronized
    private fun remove(id: String) = prefs.edit().remove(id).apply()

    /**
     * مكان النسخ والصور المصغّرة: نفس مكان تحميلات التطبيق (لو التحميل على الفلاشة → تنسخ داخل
     * مجلد التطبيق على الفلاشة نفسها، مع التحميلات). مخفية عن المعرض ومشغلات الفيديو الثانية.
     */
    private fun dir(): File {
        val base = OfflineStore.storageDir()
            ?: File(App.instance.getExternalFilesDir(null) ?: App.instance.filesDir, OfflineStore.DIR_NAME)
        return File(base, "local").apply {
            mkdirs()
            runCatching { File(this, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
        }
    }

    /** اسم المكان اللي بتنسخ له (للواجهة) */
    fun targetLabel(): String = OfflineStore.activeLabel()

    fun freeBytes(): Long = dir().usableSpace

    /** الملف اللي يشغّله المشغّل (null = مو موجود، مثلاً الفلاشة مفصولة) */
    fun sourceFor(videoId: String): Uri? {
        val id = videoId.removePrefix(ID_PREFIX)
        val e = index()[id] ?: return null
        return if (e.copied) File(e.source).takeIf { it.exists() }?.let { Uri.fromFile(it) } else Uri.parse(e.source)
    }

    fun titleFor(videoId: String): String = index()[videoId.removePrefix(ID_PREFIX)]?.title.orEmpty()

    // ------------------------------------------------------------------ البحث في الفلاشة
    data class Found(
        val uri: Uri,
        val name: String,
        val size: Long,
        val durationMs: Long,
        val folderKey: String,
        val folderName: String,
        val volumeLabel: String,
        val removable: Boolean,
    )

    data class Folder(val key: String, val name: String, val volumeLabel: String, val removable: Boolean, val videos: List<Found>) {
        val totalBytes: Long get() = videos.sumOf { it.size }
    }

    /** كل مجلدات الفيديو (الفلاشة والهارديسك أول) — يحتاج صلاحية قراءة الفيديوهات */
    fun scan(context: Context): List<Folder> {
        val found = mutableListOf<Found>()
        runCatching { found += scanMediaStore(context) }.onFailure { Log.w(TAG, "mediastore scan failed", it) }
        if (found.isEmpty()) runCatching { found += scanFiles(context) }.onFailure { Log.w(TAG, "file scan failed", it) }
        return found
            .distinctBy { it.uri.toString() }
            .groupBy { it.folderKey }
            .map { (k, v) ->
                val f = v.first()
                Folder(k, f.folderName, f.volumeLabel, f.removable, v.sortedWith { a, b -> naturalCompare(a.name, b.name) })
            }
            .sortedWith(compareByDescending<Folder> { it.removable }.thenBy { it.volumeLabel }.thenBy { it.name })
    }

    private fun scanMediaStore(context: Context): List<Found> {
        val out = mutableListOf<Found>()
        val sm = context.getSystemService(StorageManager::class.java)
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).toList() else listOf("external")
        for (vol in volumes) {
            val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(vol) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            var label = if (vol == "external_primary" || vol == "external") "ذاكرة التلفزيون" else "فلاشة / هارديسك"
            var removable = !(vol == "external_primary" || vol == "external")
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                sm?.getStorageVolume(base)?.let { sv ->
                    label = sv.getDescription(context) ?: label
                    removable = sv.isRemovable || !sv.isPrimary
                }
            }
            @Suppress("DEPRECATION")
            val proj = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
            )
            context.contentResolver.query(base, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) continue
                    val data = c.getString(6)
                    val bucket = c.getString(5) ?: data?.let { File(it).parentFile?.name } ?: "فيديوهات"
                    if (!removable && data != null && data.contains("/Android/")) continue // ملفات التطبيقات (منها نسخنا نحن)
                    out += Found(
                        uri = ContentUris.withAppendedId(base, c.getLong(0)),
                        name = name,
                        size = c.getLong(2),
                        durationMs = c.getLong(3),
                        folderKey = "$vol/${c.getString(4) ?: bucket}",
                        folderName = bucket,
                        volumeLabel = label,
                        removable = removable,
                    )
                }
            }
        }
        return out
    }

    /** بديل لو فهرس الوسائط ما شاف الفلاشة: نمشي على المجلدات بنفسنا */
    private fun scanFiles(context: Context): List<Found> {
        val roots = (context.getExternalFilesDirs(null).filterNotNull().map { File(it.absolutePath.substringBefore("/Android/")) } +
            (File("/storage").listFiles()?.toList().orEmpty()) + listOfNotNull(File("/mnt/media_rw").takeIf { it.canRead() }))
            .filter { it.canRead() && it.name != "self" && it.name != "emulated" }
            .distinctBy { it.absolutePath }
        val out = mutableListOf<Found>()
        fun walk(dir: File, depth: Int, label: String, removable: Boolean) {
            if (depth > 6 || out.size > 5000) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".") || f.name == "Android" || f.name == "LOST.DIR") continue
                if (f.isDirectory) walk(f, depth + 1, label, removable)
                else if (f.extension.lowercase() in EXTENSIONS && f.length() > 0) {
                    out += Found(Uri.fromFile(f), f.name, f.length(), 0, f.parentFile!!.absolutePath, f.parentFile!!.name, label, removable)
                }
            }
        }
        for (r in roots) {
            val primary = r.absolutePath.startsWith("/storage/emulated")
            walk(r, 0, if (primary) "ذاكرة التلفزيون" else "فلاشة / هارديسك (${r.name})", !primary)
        }
        return out
    }

    /** ترتيب طبيعي: حلقة 2 قبل حلقة 10 */
    fun naturalCompare(a: String, b: String): Int {
        val re = Regex("(\\d+)|(\\D+)")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size - pb.size
    }

    fun alreadyImported(uri: Uri): Boolean = index().values.any { it.origin == uri.toString() }

    // ------------------------------------------------------------------ الاستيراد
    sealed interface Dest {
        data class NewList(val title: String, val kids: List<String>) : Dest
        data class Existing(val parentId: String) : Dest
        data class Home(val kids: List<String>) : Dest
    }

    data class Progress(
        val done: Int,
        val total: Int,
        val current: String = "",
        val percent: Int = 0,
        val finished: Boolean = false,
        val error: String? = null,
        val skipped: Int = 0,
        val linked: Int = 0, // حلقات اشتراك ارتبطت بحلقاتها الموجودة (ما انضافت من جديد)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    fun cancel() { cancelled = true }
    fun clearProgress() { if (!running) _progress.value = null }

    fun start(context: Context, pin: String, videos: List<Found>, dest: Dest, copy: Boolean) {
        if (running) return
        running = true
        cancelled = false
        val app = context.applicationContext
        scope.launch {
            // حلقات الاشتراك اللي نزلت من الكمبيوتر ([xt_…] في الاسم): ترتبط بالحلقة الموجودة بدل ما تنضاف من جديد
            val items = (TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready)?.items.orEmpty()
            val linkTo = videos.associateWith { XtLocal.targetFor(it.name, items) }
            var linked = 0
            var noPlace = 0
            val todo = videos.filterNot { alreadyImported(it.uri) || XtLocal.keyOf(it.name) != null }
            val xtFiles = videos.filter { XtLocal.keyOf(it.name) != null }
            val skipped = videos.size - todo.size - xtFiles.size
            var parentId: String? = (dest as? Dest.Existing)?.parentId
            var done = 0
            _progress.value = Progress(0, todo.size, skipped = skipped)
            try {
                if (copy) {
                    val need = todo.sumOf { it.size } + xtFiles.filter { linkTo[it] != null }.sumOf { it.size }
                    if (need + 300L * 1024 * 1024 > freeBytes()) {
                        throw IllegalStateException("المساحة ما تكفي للنسخ — اختر «شغّل من الفلاشة» أو فضّي مساحة")
                    }
                }
                for (v in xtFiles) {
                    if (cancelled) break
                    val target = linkTo[v]
                    if (target == null) { noPlace++; continue }
                    if (copy) {
                        _progress.value = Progress(done, todo.size, target.title, 0, skipped = skipped)
                        val out = File(dir(), "${target.youtubeId}.dat")
                        val tmp = File(dir(), "${target.youtubeId}.part")
                        try {
                            app.contentResolver.openInputStream(v.uri)!!.use { input -> tmp.outputStream().use { o -> input.copyTo(o, 256 * 1024) } }
                            if (!tmp.renameTo(out)) throw IllegalStateException("تعذر حفظ الملف")
                        } catch (e: Exception) { tmp.delete(); throw e }
                        XtLocal.registerCopy(target.youtubeId, out)
                    }
                    linked++
                }
                if (xtFiles.isNotEmpty()) XtLocal.refresh(app, force = true)
                for (v in todo) {
                    if (cancelled) break
                    val id = UUID.randomUUID().toString().replace("-", "").take(20)
                    // "Ahla.Naseeb.S01E03" → "Ahla Naseeb S01E03"
                    val title = v.name.substringBeforeLast('.').replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
                    _progress.value = Progress(done, todo.size, title, 0, skipped = skipped)

                    val source: String = if (copy) {
                        // بدون امتداد فيديو — ما تطلع في معرض الجهاز (المشغّل يتعرف على النوع من المحتوى)
                        val out = File(dir(), "$id.dat")
                        val tmp = File(dir(), "$id.part")
                        try {
                            app.contentResolver.openInputStream(v.uri)!!.use { input ->
                                tmp.outputStream().use { o ->
                                    val buf = ByteArray(256 * 1024)
                                    var copied = 0L
                                    var lastPct = -1
                                    while (true) {
                                        if (cancelled) throw InterruptedException()
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        o.write(buf, 0, n)
                                        copied += n
                                        val pct = if (v.size > 0) (copied * 100 / v.size).toInt() else 0
                                        if (pct != lastPct) { lastPct = pct; _progress.value = Progress(done, todo.size, title, pct, skipped = skipped) }
                                    }
                                }
                            }
                            if (!tmp.renameTo(out)) throw IllegalStateException("تعذر حفظ الملف")
                        } catch (e: Exception) {
                            tmp.delete()
                            throw e
                        }
                        out.absolutePath
                    } else v.uri.toString()

                    val thumb = makeThumb(app, if (copy) Uri.fromFile(File(source)) else v.uri, id, v.durationMs)
                    putEntry(id, Entry(source, copy, v.uri.toString(), title))

                    // نضيفه في الخادم (يبان للطفل وفي لوحة الأهل)
                    try {
                        val res = TvRepository.supabase.postgrest.rpc("device_import_local", buildJsonObject {
                            put("p_pin", pin)
                            put("p_parent", parentId)
                            put("p_new_title", if (parentId == null) (dest as? Dest.NewList)?.title else null)
                            put("p_kids", buildJsonArray {
                                val kids = (dest as? Dest.NewList)?.kids ?: (dest as? Dest.Home)?.kids ?: emptyList()
                                kids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                            put("p_items", buildJsonArray {
                                add(buildJsonObject {
                                    put("key", ID_PREFIX + id)
                                    put("title", title)
                                    put("thumb", thumb)
                                })
                            })
                        }).decodeAs<JsonObject>()
                        if (dest !is Dest.Home) parentId = res["parent_id"]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: parentId
                    } catch (e: Exception) {
                        // ما انضاف → نشيل الملف عشان ما يبقى بدون فايدة
                        deleteFiles(id)
                        throw IllegalStateException(e.message?.lineSequence()?.firstOrNull { l -> l.any { it in '؀'..'ۿ' } }
                            ?: "تعذر الإضافة — تأكد من الإنترنت", e)
                    }
                    done++
                    _progress.value = Progress(done, todo.size, title, 100, skipped = skipped)
                }
                _progress.value = Progress(done, todo.size, finished = true, skipped = skipped, linked = linked,
                    error = if (cancelled) "انلغى — انضاف $done"
                    else if (noPlace > 0) "$noPlace ملف من الاشتراك ما لقينا حلقته في المحتوى — حدّث المسلسل من اللوحة («جيب الحلقات الجديدة») وأعد الاستيراد" else null)
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)
                _progress.value = Progress(done, todo.size, finished = true, skipped = skipped,
                    error = if (e is InterruptedException) "انلغى — انضاف $done" else (e.message ?: "تعذر الاستيراد"))
            } finally {
                running = false
                TvRepository.refresh()
            }
        }
    }

    private fun makeThumb(context: Context, uri: Uri, id: String, durationMs: Long): String? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = (minOf(10_000L, dur / 10)).coerceAtLeast(1000L) * 1000
            val bmp = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val w = 480
            val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
            val f = File(dir(), "$id.jpg")
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            Uri.fromFile(f).toString()
        } finally {
            runCatching { r.release() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "thumb failed", e)
        null
    }

    private fun deleteFiles(id: String) {
        val e = index()[id]
        if (e?.copied == true) File(e.source).delete()
        dir().listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        remove(id)
    }

    // ------------------------------------------------------------------ استقبال من الكمبيوتر (LanShare)

    /** ملف مؤقت يستقبل فيه فيديو من الشبكة (بنفس مكان التحميل) */
    fun receiveTarget(): Pair<String, File> {
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        return id to File(dir(), "$id.part")
    }

    /**
     * فيديو وصل كامل من الكمبيوتر → نثبّته ونضيفه للمحتوى.
     * parentId = قائمة موجودة، newTitle = قائمة جديدة بهالاسم، والاثنين فاضيين = الرئيسية.
     * يرجّع رقم القائمة (عشان باقي ملفات نفس المجلد تنضاف لنفس القائمة).
     */
    suspend fun registerReceived(context: Context, pin: String, id: String, part: File, fileName: String,
                                 parentId: String?, newTitle: String?): String? {
        val out = File(dir(), "$id.dat")
        if (!part.renameTo(out)) throw IllegalStateException("تعذر حفظ الملف")
        val title = fileName.substringBeforeLast('.').replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
            .ifBlank { "فيديو" }
        val thumb = makeThumb(context.applicationContext, Uri.fromFile(out), id, 0)
        putEntry(id, Entry(out.absolutePath, true, "lan:$id", title))
        try {
            val res = TvRepository.supabase.postgrest.rpc("device_import_local", buildJsonObject {
                put("p_pin", pin)
                put("p_parent", parentId)
                put("p_new_title", if (parentId == null) newTitle else null)
                put("p_kids", buildJsonArray { })
                put("p_items", buildJsonArray {
                    add(buildJsonObject {
                        put("key", ID_PREFIX + id)
                        put("title", title)
                        put("thumb", thumb)
                    })
                })
            }).decodeAs<JsonObject>()
            TvRepository.refresh()
            if (parentId == null && newTitle == null) return null
            return res["parent_id"]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: parentId
        } catch (e: Exception) {
            deleteFiles(id)
            throw IllegalStateException(e.message?.lineSequence()?.firstOrNull { l -> l.any { it in '؀'..'ۿ' } }
                ?: "تعذر الإضافة — تأكد من الإنترنت", e)
        }
    }

    /** المساحة المتاحة بمكان الاستقبال */
    fun receiveFreeBytes(): Long = freeBytes()

    /** الأب حذف الفيديو من اللوحة → نحذف نسخته من التلفزيون */
    fun cleanup(items: List<ContentItem>) {
        if (running) return
        val keep = items.filter { isLocalId(it.youtubeId) }.map { it.youtubeId.removePrefix(ID_PREFIX) }.toSet()
        val now = System.currentTimeMillis()
        index().forEach { (id, e) ->
            if (id !in keep && now - e.at > 10 * 60 * 1000L) {
                Log.i(TAG, "removing local video $id (deleted from dashboard)")
                deleteFiles(id)
            }
        }
    }
}
