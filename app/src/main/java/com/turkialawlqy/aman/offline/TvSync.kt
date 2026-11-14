package com.turkialawlqy.aman.offline

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.cache.CacheWriter
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.data.TvState
import com.turkialawlqy.aman.player.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * نقل المحفوظ بين تلفزيونين على نفس الشبكة (بدون ما يتحمّل من الإنترنت مرة ثانية):
 * - المرسل: يفتح سيرفر صغير ويعلن عن نفسه بالشبكة، ويعرض رمز من 4 أرقام.
 * - المستقبل: يلقى المرسلين لحاله، تكتب الرمز، وينسخ الناقص عنده بس.
 * الملفات تنكتب في ذاكرة التحميل نفسها، فتشتغل بدون إنترنت مثل أي فيديو محفوظ.
 */
object TvSync {
    private const val TAG = "TvSync"
    private const val SERVICE = "_amankids._tcp."
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------ المرسل
    data class SendStatus(val running: Boolean = false, val address: String? = null, val code: String = "",
                          val served: Int = 0, val current: String? = null, val error: String? = null)

    private val _send = MutableStateFlow(SendStatus())
    val sendStatus: StateFlow<SendStatus> = _send.asStateFlow()
    private var server: ServerSocket? = null
    private var sendScope: CoroutineScope? = null
    private var nsdReg: NsdManager.RegistrationListener? = null

    fun startSending(context: Context, deviceName: String) {
        if (server != null) return
        val code = (1000..9999).random().toString()
        val s = (8095..8105).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (s == null) { _send.value = SendStatus(error = "ما قدرنا نفتح منفذ على الشبكة"); return }
        server = s
        val ip = localIp()
        _send.value = SendStatus(running = true, address = ip?.let { "$it:${s.localPort}" }, code = code,
            error = if (ip == null) "التلفزيون مو متصل بشبكة محلية" else null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sendScope = scope
        scope.launch {
            while (isActive) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { sock.use { handle(it, code) } }.onFailure { Log.w(TAG, "request failed", it) } }
            }
        }
        // نعلن عن نفسنا بالشبكة عشان التلفزيون الثاني يلقانا بدون ما تكتب IP
        runCatching {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "أمان كيدز - $deviceName".take(60)
                serviceType = SERVICE
                port = s.localPort
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "nsd register failed $e") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdReg = l
        }
    }

    fun stopSending(context: Context) {
        runCatching { nsdReg?.let { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } }
        nsdReg = null
        runCatching { server?.close() }
        server = null
        sendScope?.cancel(); sendScope = null
        _send.value = SendStatus()
    }

    private fun readLine(i: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(sock: Socket, code: String) {
        sock.soTimeout = 60_000
        val input = sock.getInputStream().buffered()
        val out = BufferedOutputStream(sock.getOutputStream(), 256 * 1024)
        val line = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val target = line.split(' ').getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val q = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        if (q["code"] != code) { reply(out, 403, "text/plain", "رمز غلط".toByteArray()); return }
        when (path) {
            "/list" -> {
                val list = OfflineStore.entries().filter { it.complete }
                reply(out, 200, "application/json; charset=utf-8",
                    json.encodeToString(ListSerializer(OfflineEntry.serializer()), list).toByteArray())
            }
            "/file" -> serveFile(out, q["v"].orEmpty(), q["itag"]?.toIntOrNull() ?: 0, headers["range"])
            else -> reply(out, 404, "text/plain", "Not found".toByteArray())
        }
        out.flush()
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Error" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    /** يقرا الفيديو من الذاكرة ويرسله (يدعم الاستكمال: Range) */
    private fun serveFile(out: OutputStream, videoId: String, itag: Int, range: String?) {
        val e = OfflineStore.entry(videoId)?.takeIf { it.complete }
        val st = e?.media?.streams?.firstOrNull { it.itag == itag }
        val ds = OfflineStore.offlineFactory()?.createDataSource()
        if (e == null || st == null || ds == null || st.contentLength <= 0) { reply(out, 404, "text/plain", "ما هو محفوظ".toByteArray()); return }
        val total = st.contentLength
        val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0, total) ?: 0L
        val end = m?.groupValues?.get(2)?.toLongOrNull()?.coerceIn(start, total - 1) ?: (total - 1)
        val len = end - start + 1
        _send.value = _send.value.copy(current = e.title)
        val head = if (m != null)
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/$total\r\n"
        else "HTTP/1.1 200 OK\r\n"
        out.write("${head}Content-Type: application/octet-stream\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
        try {
            ds.open(DataSpec.Builder().setUri(Uri.parse(st.url)).setKey(StreamResolver.cacheKey(videoId, itag))
                .setPosition(start).setLength(len).build())
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            out.flush()
            if (end >= total - 1) _send.value = _send.value.copy(served = _send.value.served + 1, current = null)
        } finally {
            runCatching { ds.close() }
        }
    }

    // ------------------------------------------------------------------ المستقبل
    data class Peer(val name: String, val host: String, val port: Int)
    data class Offer(val entry: OfflineEntry, val inMyContent: Boolean, val haveIt: Boolean)
    data class RecvStatus(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String? = null,
                          val percent: Int = 0, val finished: Boolean = false, val error: String? = null)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _recv = MutableStateFlow(RecvStatus())
    val recvStatus: StateFlow<RecvStatus> = _recv.asStateFlow()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var recvJob: Job? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    fun startDiscovery(context: Context) {
        if (discovery != null) return
        _peers.value = emptyList()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "discovery failed $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) { _peers.value = _peers.value.filterNot { it.name == i.serviceName } }
            override fun onServiceFound(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(x: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(x: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = x.host?.hostAddress ?: return
                        if (host == localIp()) return // هذا أنا
                        val p = Peer(x.serviceName, host, x.port)
                        _peers.value = (_peers.value.filterNot { it.name == p.name } + p)
                    }
                })
            }
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l); discovery = l }
    }

    fun stopDiscovery(context: Context) {
        runCatching { discovery?.let { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } }
        discovery = null
    }

    /** وش عند التلفزيون الثاني، ووش ناقص هنا */
    suspend fun offers(peer: Peer, code: String): List<Offer> = withContext(Dispatchers.IO) {
        val r = http.newCall(Request.Builder().url("http://${peer.host}:${peer.port}/list?code=$code").build()).execute()
        r.use {
            if (it.code == 403) throw IllegalStateException("الرمز غلط — شوفه على شاشة التلفزيون الثاني")
            if (!it.isSuccessful) throw IllegalStateException("التلفزيون الثاني رد ${it.code}")
            val list = json.decodeFromString(ListSerializer(OfflineEntry.serializer()), it.body.string())
            val ready = TvRepository.state.value as? TvState.Ready
            val items = ready?.items.orEmpty()
            val ids = items.map { x -> x.id }.toSet()
            val vids = items.map { x -> x.youtubeId }.toSet()
            list.map { e ->
                Offer(e, inMyContent = e.videoId in vids || e.sources.any { s -> s in ids },
                    haveIt = OfflineStore.entry(e.videoId)?.complete == true)
            }
        }
    }

    fun receive(peer: Peer, code: String, entries: List<OfflineEntry>) {
        if (recvJob?.isActive == true) return
        recvJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            _recv.value = RecvStatus(running = true, total = entries.size)
            var done = 0
            try {
                for (e in entries) {
                    if (!isActive) break
                    if (OfflineStore.freeBytes() < e.media.streams.sumOf { it.contentLength } + 300L * 1024 * 1024) {
                        throw IllegalStateException("المساحة ما تكفي — انتقل $done")
                    }
                    val ds = OfflineStore.lanWriteSource() ?: throw IllegalStateException("مكان التحميل مو جاهز (الفلاشة مفصولة؟)")
                    _recv.value = _recv.value.copy(current = e.title, percent = 0)
                    val total = e.media.streams.sumOf { it.contentLength }.coerceAtLeast(1)
                    var before = 0L
                    for (st in e.media.streams) {
                        val uri = Uri.parse("http://${peer.host}:${peer.port}/file?code=$code&v=${Uri.encode(e.videoId)}&itag=${st.itag}")
                        val spec = DataSpec.Builder().setUri(uri).setKey(StreamResolver.cacheKey(e.videoId, st.itag)).build()
                        val base = before
                        CacheWriter(ds, spec, null) { _, cached, _ ->
                            _recv.value = _recv.value.copy(percent = ((base + cached) * 100 / total).toInt().coerceIn(0, 99))
                            if (!isActive) throw java.io.InterruptedIOException()
                        }.cache()
                        before += st.contentLength
                    }
                    OfflineStore.upsert(e.copy(downloaded = true))
                    if (OfflineStore.entry(e.videoId)?.complete == true) done++
                    _recv.value = _recv.value.copy(done = done, percent = 100)
                }
                _recv.value = _recv.value.copy(running = false, finished = true, current = null)
                OfflineDownloader.reportSoon()
            } catch (x: Exception) {
                Log.w(TAG, "receive failed", x)
                _recv.value = _recv.value.copy(running = false, finished = true, current = null,
                    error = (x.message ?: "انقطع النقل") + " — تقدر تعيد، يكمل من مكانه")
            }
        }
    }

    fun cancelReceive() { recvJob?.cancel() }
    fun clearReceive() { if (recvJob?.isActive != true) _recv.value = RecvStatus() }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
