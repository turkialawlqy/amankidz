package com.turkialawlqy.aman.offline

import android.content.Context
import android.util.Log
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * استقبال فيديوهات من الكمبيوتر أو الجوال على نفس الشبكة (بدون ما تطلع الفلاشة):
 *  - صفحة بالمتصفح: http://<ip>:<port>/<رمز>/  — تسحب الملفات وتختار القائمة
 *  - قرص شبكة في Explorer (WebDAV): نفس الرابط — كل مجلد = قائمة بنفس الاسم، والملفات برا المجلدات = الرئيسية
 * الملفات تنحفظ بمكان التحميل (مخفية عن المعرض) وتنضاف للمحتوى مباشرة.
 * السيرفر يشتغل بس وشاشة "استقبال من الكمبيوتر" مفتوحة، والرابط فيه رمز عشوائي.
 */
object LanShare {
    private const val TAG = "LanShare"
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "3gp", "mpg", "mpeg", "wmv", "flv")

    data class Received(val name: String, val folder: String?, val size: Long, val done: Long, val status: String, val error: String? = null)
    data class Status(val running: Boolean = false, val url: String? = null, val error: String? = null,
                      val files: List<Received> = emptyList())

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var token = ""
    private var pin = ""
    private lateinit var app: Context

    /** مجلدات أنشأها Explorer في هالجلسة (اسم → رقم القائمة بعد أول ملف) */
    private val folders = ConcurrentHashMap<String, String>()
    /** ملفات استلمناها (للـ PROPFIND اللي يسويه Windows بعد النسخ) */
    private val files = ConcurrentHashMap<String, Long>()
    private val received = java.util.Collections.synchronizedList(mutableListOf<Received>())
    private val importLock = kotlinx.coroutines.sync.Mutex()

    fun start(context: Context, settingsPin: String) {
        if (server != null) return
        app = context.applicationContext
        pin = settingsPin
        token = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        folders.clear(); files.clear(); received.clear()
        val s = (8080..8090).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (s == null) { _status.value = Status(error = "ما قدرت أفتح منفذ على الشبكة"); return }
        server = s
        val ip = localIp()
        _status.value = Status(running = true, url = if (ip != null) "http://$ip:${s.localPort}/$token/" else null,
            error = if (ip == null) "التلفزيون مو متصل بشبكة واي فاي أو كيبل" else null)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            while (true) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                sc.launch { runCatching { handle(sock) }.onFailure { Log.w(TAG, "conn", it) }; runCatching { sock.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel(); scope = null
        _status.value = Status()
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun publish() { _status.value = _status.value.copy(files = received.toList().takeLast(30).reversed()) }

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream) {
        val length: Long get() = headers["content-length"]?.toLongOrNull() ?: -1L
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IllegalStateException("header too long")
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        val input = sock.getInputStream().buffered(256 * 1024)
        val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        // keep-alive: Windows يرسل طلبات كثيرة ورا بعض على نفس الاتصال
        while (true) {
            val line = try { readLine(input) } catch (e: SocketTimeoutException) { null } ?: return
            if (line.isBlank()) continue
            val parts = line.split(' ')
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val rawPath = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            // Windows يرسل "Expect: 100-continue" قبل الملفات الكبيرة وينتظر الموافقة
            if (headers["expect"].equals("100-continue", true)) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
            }
            val req = Req(parts[0].uppercase(), path, query, headers, input)
            val keep = route(req, out)
            out.flush()
            if (!keep || headers["connection"].equals("close", true)) return
        }
    }

    private fun send(out: OutputStream, code: Int, text: String, type: String = "text/plain; charset=utf-8",
                     extra: Map<String, String> = emptyMap(), body: ByteArray? = text.toByteArray()) {
        val reason = when (code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 207 -> "Multi-Status"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"; 507 -> "Insufficient Storage"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\n")
        sb.append("DAV: 1,2\r\nMS-Author-Via: DAV\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (body != null) out.write(body)
    }

    /** يقرا جسم الطلب ويرميه (لو ما نحتاجه) */
    private fun drain(req: Req) {
        var n = req.length
        val buf = ByteArray(64 * 1024)
        while (n > 0) { val r = req.body.read(buf, 0, minOf(buf.size.toLong(), n).toInt()); if (r < 0) break; n -= r }
    }

    private fun route(req: Req, out: OutputStream): Boolean {
        val base = "/$token"
        if (req.method == "OPTIONS") {
            drain(req)
            send(out, 200, "", extra = mapOf("Allow" to "OPTIONS, GET, HEAD, PUT, PROPFIND, PROPPATCH, MKCOL, DELETE, MOVE, LOCK, UNLOCK"))
            return true
        }
        if (req.path != base && !req.path.startsWith("$base/")) { drain(req); send(out, 404, "Not found"); return true }
        val rel = req.path.removePrefix(base).trim('/') // "" | "folder" | "file.mp4" | "folder/file.mp4" | "api/lists" | "up/name"
        return when (req.method) {
            "GET", "HEAD" -> {
                when {
                    rel.isEmpty() -> send(out, 200, uploadPage(), "text/html; charset=utf-8",
                        body = if (req.method == "HEAD") null else uploadPage().toByteArray())
                    rel == "api/lists" -> send(out, 200, listsJson(), "application/json; charset=utf-8")
                    else -> send(out, 404, "Not found")
                }
                true
            }
            "PUT" -> { put(req, rel, out); true }
            "MKCOL" -> {
                drain(req)
                val name = rel.substringAfterLast('/').trim()
                if (name.isEmpty() || rel.contains('/')) send(out, 403, "") else { folders.putIfAbsent(name, ""); send(out, 201, "") }
                true
            }
            "PROPFIND" -> { drain(req); propfind(req, rel, out); true }
            "PROPPATCH" -> {
                drain(req)
                val href = req.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                send(out, 207, """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>$href</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
                    "application/xml; charset=utf-8")
                true
            }
            "LOCK" -> {
                drain(req)
                val t = "opaquelocktoken:" + java.util.UUID.randomUUID()
                send(out, 200, """<?xml version="1.0" encoding="utf-8"?><D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth><D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$t</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>""",
                    "application/xml; charset=utf-8", mapOf("Lock-Token" to "<$t>"))
                true
            }
            "UNLOCK", "DELETE" -> { drain(req); files.remove(rel); send(out, 204, "", body = null); true }
            "MOVE" -> {
                drain(req)
                // إعادة تسمية "مجلد جديد" → اسم المسلسل
                val dest = req.headers["destination"]?.let { URLDecoder.decode(java.net.URI(it).rawPath.replace("+", "%2B"), "UTF-8") }
                    ?.removePrefix(base)?.trim('/')
                if (dest != null && !rel.contains('/') && !dest.contains('/') && folders.containsKey(rel)) {
                    folders[dest] = folders.remove(rel) ?: ""
                    send(out, 201, "")
                } else send(out, 403, "")
                true
            }
            else -> { drain(req); send(out, 405, ""); true }
        }
    }

    // ------------------------------------------------------------------ استقبال ملف

    private fun put(req: Req, rel: String, out: OutputStream) {
        // من الصفحة: up/<اسم>?dest=home|list:<id>|new:<اسم>   — من Explorer: <ملف> أو <مجلد>/<ملف>
        val fromPage = rel.startsWith("up/")
        val name = rel.substringAfterLast('/')
        val folder = if (fromPage) null else rel.substringBeforeLast('/', "").ifBlank { null }
        val len = req.length
        if (len < 0) { send(out, 411, "Content-Length required"); return }
        val ext = name.substringAfterLast('.', "").lowercase()
        // ملفات Windows الجانبية (desktop.ini…) أو الملف الفاضي اللي يسويه Explorer أول → نقبلها بدون ما نحفظ
        if (ext !in VIDEO_EXT || len == 0L) {
            drain(req); files[rel] = len.coerceAtLeast(0); send(out, 201, ""); return
        }
        if (len + 200L * 1024 * 1024 > LocalMedia.receiveFreeBytes()) {
            drain(req); send(out, 507, "المساحة ما تكفي"); return
        }
        val (id, part) = LocalMedia.receiveTarget()
        val dq = req.query["dest"]
        var item = Received(name, folder ?: dq?.takeIf { it.startsWith("new:") }?.removePrefix("new:"), len, 0, "يستقبل")
        received.add(item); publish()
        val idx = received.size - 1
        fun update(r: Received) { item = r; runCatching { received[idx] = r }; publish() }
        try {
            part.outputStream().buffered(256 * 1024).use { o ->
                val buf = ByteArray(256 * 1024)
                var left = len
                var got = 0L
                var lastPub = 0L
                while (left > 0) {
                    val r = req.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw IllegalStateException("انقطع الاتصال")
                    o.write(buf, 0, r)
                    left -= r; got += r
                    if (got - lastPub > 4L * 1024 * 1024) { lastPub = got; update(item.copy(done = got)) }
                }
            }
            update(item.copy(done = len, status = "يضيف للمحتوى"))
            // الإضافة للمحتوى بالترتيب (عشان حلقات نفس المجلد تنضاف لنفس القائمة)
            runBlocking {
                importLock.lock()
                try {
                    val (parentId, newTitle) = destination(folder, dq)
                    val pid = LocalMedia.registerReceived(app, pin, id, part, name, parentId, newTitle)
                    if (pid != null) {
                        if (folder != null) folders[folder] = pid
                        else if (dq != null && dq.startsWith("new:")) folders[" new:" + dq.removePrefix("new:").trim()] = pid
                    }
                } finally { importLock.unlock() }
            }
            files[rel] = len
            update(item.copy(status = "تم"))
            send(out, 201, "")
        } catch (e: Exception) {
            Log.w(TAG, "put failed", e)
            part.delete()
            update(item.copy(status = "تعذر", error = e.message))
            runCatching { send(out, 500, e.message ?: "error") }
        }
    }

    /** وين ينضاف الملف: قائمة موجودة / قائمة جديدة / الرئيسية */
    private fun destination(folder: String?, dest: String?): Pair<String?, String?> {
        val mixes = myMixes()
        if (folder != null) {
            folders[folder]?.takeIf { it.isNotBlank() }?.let { return it to null }
            mixes.firstOrNull { it.second.trim() == folder.trim() }?.let { return it.first to null }
            return null to folder
        }
        return when {
            dest == null || dest == "home" -> null to null
            dest.startsWith("list:") -> dest.removePrefix("list:") to null
            dest.startsWith("new:") -> {
                val t = dest.removePrefix("new:").trim()
                // باقي ملفات نفس الدفعة تنضاف لنفس القائمة الجديدة
                folders[" new:$t"]?.takeIf { it.isNotBlank() }?.let { return it to null }
                mixes.firstOrNull { it.second.trim() == t }?.let { return it.first to null }
                null to t
            }
            else -> null to null
        }
    }

    /** قوائم هذا الجهاز (رقم، اسم) */
    private fun myMixes(): List<Pair<String, String>> {
        val s = TvRepository.state.value as? TvState.Ready ?: return emptyList()
        return s.items.filter { it.kind == "mix" && it.parentId == null && it.deviceId == s.device.id }.map { it.id to it.title }
    }

    // ------------------------------------------------------------------ WebDAV: عرض المجلدات

    private fun propfind(req: Req, rel: String, out: OutputStream) {
        val depth = req.headers["depth"] ?: "1"
        val base = "/$token/"
        fun enc(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        fun col(href: String, name: String) =
            "<D:response><D:href>${enc(href)}</D:href><D:propstat><D:prop><D:displayname>${xml(name)}</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        fun file(href: String, name: String, size: Long) =
            "<D:response><D:href>${enc(href)}</D:href><D:propstat><D:prop><D:displayname>${xml(name)}</D:displayname><D:resourcetype/><D:getcontentlength>$size</D:getcontentlength><D:getcontenttype>video/mp4</D:getcontenttype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        val sb = StringBuilder("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        val allFolders = (myMixes().map { it.second } + folders.keys.filter { !it.startsWith(" ") }).map { it.replace('/', '-') }.distinct()
        when {
            rel.isEmpty() -> {
                sb.append(col(base, "أمان"))
                if (depth != "0") {
                    allFolders.forEach { sb.append(col("$base$it/", it)) }
                    files.filterKeys { !it.contains('/') }.forEach { (k, v) -> sb.append(file(base + k, k, v)) }
                }
            }
            !rel.contains('/') && rel in allFolders -> {
                sb.append(col("$base$rel/", rel))
                if (depth != "0") files.filterKeys { it.startsWith("$rel/") }.forEach { (k, v) -> sb.append(file(base + k, k.substringAfter('/'), v)) }
            }
            files.containsKey(rel) -> sb.append(file(base + rel, rel.substringAfterLast('/'), files[rel] ?: 0))
            else -> { send(out, 404, "Not found"); return }
        }
        sb.append("</D:multistatus>")
        send(out, 207, sb.toString(), "application/xml; charset=utf-8")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun listsJson(): String =
        "[" + myMixes().joinToString(",") { (id, t) -> "{\"id\":\"$id\",\"title\":\"${t.replace("\\", "\\\\").replace("\"", "\\\"")}\"}" } + "]"

    // ------------------------------------------------------------------ صفحة الرفع بالمتصفح

    private fun uploadPage(): String = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>أمان — إرسال فيديوهات للتلفزيون</title>
<style>
:root{--bg:#12151f;--card:#1a1e2b;--line:#2a3045;--text:#e9ecf6;--muted:#9aa2bb;--a:#2fd3bf}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}
main{max-width:760px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}p{color:var(--muted);margin:0 0 16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:16px;margin-bottom:14px}
label{display:block;margin-bottom:8px;font-weight:600}select,input{width:100%;padding:10px;border-radius:6px;border:1px solid var(--line);background:#20253a;color:var(--text);font:inherit}
#drop{border:2px dashed var(--line);border-radius:8px;padding:36px 16px;text-align:center;color:var(--muted);cursor:pointer}
#drop.on{border-color:var(--a);color:var(--text)}
ul{list-style:none;margin:0;padding:0}li{padding:10px 0;border-bottom:1px solid var(--line)}li:last-child{border:0}
.bar{height:4px;background:var(--line);border-radius:2px;margin-top:6px}.bar span{display:block;height:100%;background:var(--a);border-radius:2px;width:0}
.s{color:var(--muted);font-size:13px}.ok{color:var(--a)}.err{color:#ff7272}
</style></head><body><main>
<h1>إرسال فيديوهات للتلفزيون</h1><p>الفيديوهات تنحفظ على التلفزيون (مكان التحميل) وتنضاف للمحتوى. خل شاشة "استقبال من الكمبيوتر" مفتوحة على التلفزيون لين يخلص.</p>
<div class="card"><label>وين تنضاف؟</label>
<select id="dest"><option value="home">الصفحة الرئيسية (فيديوهات منفصلة)</option><option value="new">قائمة جديدة…</option></select>
<input id="newName" placeholder="اسم القائمة الجديدة (مثلاً اسم المسلسل)" style="margin-top:8px;display:none"></div>
<div class="card"><div id="drop">اسحب الفيديوهات هنا أو اضغط للاختيار<br><span class="s">mp4 · mkv · avi · mov</span></div>
<input type="file" id="pick" accept="video/*,.mkv,.avi,.ts" multiple style="display:none"></div>
<div class="card"><ul id="list"><li class="s">ما فيه ملفات بعد</li></ul></div>
</main><script>
const dest=document.getElementById('dest'),newName=document.getElementById('newName'),list=document.getElementById('list');
fetch('api/lists').then(r=>r.json()).then(ls=>{for(const l of ls){const o=document.createElement('option');o.value='list:'+l.id;o.textContent='قائمة: '+l.title;dest.appendChild(o)}});
dest.onchange=()=>{newName.style.display=dest.value==='new'?'block':'none'};
const drop=document.getElementById('drop'),pick=document.getElementById('pick');
drop.onclick=()=>pick.click();pick.onchange=()=>add([...pick.files]);
drop.ondragover=e=>{e.preventDefault();drop.classList.add('on')};drop.ondragleave=()=>drop.classList.remove('on');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('on');add([...e.dataTransfer.files])};
const queue=[];let busy=false,first=true;
function add(fs){
  let d=dest.value;if(d==='new'){const n=newName.value.trim();if(!n){alert('اكتب اسم القائمة');return}d='new:'+n}
  if(first){list.innerHTML='';first=false}
  for(const f of fs){const li=document.createElement('li');li.innerHTML='<div></div><div class="s">بالانتظار</div><div class="bar"><span></span></div>';li.firstChild.textContent=f.name;list.appendChild(li);queue.push({f,d,li})}
  next();
}
function next(){
  if(busy||!queue.length)return;busy=true;const {f,d,li}=queue.shift();const s=li.children[1],bar=li.querySelector('.bar span');
  const x=new XMLHttpRequest();x.open('PUT','up/'+encodeURIComponent(f.name)+'?dest='+encodeURIComponent(d));
  x.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded/e.total*100);bar.style.width=p+'%';s.textContent='يرسل '+p+'% ('+Math.round(e.loaded/1048576)+' من '+Math.round(e.total/1048576)+' ميجا)'}};
  x.onload=()=>{if(x.status<300){s.textContent='تم — انضاف للتلفزيون';s.className='s ok'}else{s.textContent='تعذر: '+(x.responseText||x.status);s.className='s err'}busy=false;next()};
  x.onerror=()=>{s.textContent='انقطع الاتصال — تأكد إن الشاشة مفتوحة على التلفزيون';s.className='s err';busy=false;next()};
  x.send(f);
}
</script></body></html>"""
}
