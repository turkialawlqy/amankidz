package com.turkialawlqy.aman.offline

import android.net.Uri
import com.turkialawlqy.aman.yt.UpMedia
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSourceException
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.io.IOException

/**
 * يقرأ فيديو مقسوم أجزاء في الاستضافة (upmedia://<id>) كأنه ملف واحد:
 * الموضع العام → الجزء الصحيح + الموضع داخله، ويكمل للجزء اللي بعده تلقائياً.
 */
class MultiPartDataSource(private val upstream: DataSource) : DataSource {
    private var uri: Uri? = null
    private var parts: List<UpMedia.Part> = emptyList()
    private var partIndex = 0
    private var partOffset = 0L
    private var remaining = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val id = dataSpec.uri.host ?: dataSpec.uri.schemeSpecificPart.removePrefix("//")
        parts = UpMedia.partsFor(id) ?: throw IOException("upload parts not resolved: $id")
        val total = parts.sumOf { it.size }
        var pos = dataSpec.position
        if (pos >= total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        partIndex = 0
        while (pos >= parts[partIndex].size) {
            pos -= parts[partIndex].size
            partIndex++
        }
        partOffset = pos
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - dataSpec.position
        openPart()
        return remaining
    }

    private fun openPart() {
        val p = parts[partIndex]
        val len = minOf(p.size - partOffset, remaining)
        upstream.open(
            DataSpec.Builder()
                .setUri(Uri.parse(p.url))
                .setPosition(partOffset)
                .setLength(len)
                .build()
        )
        upstreamOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        while (true) {
            val n = upstream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                upstream.close()
                upstreamOpen = false
                if (partIndex + 1 >= parts.size) return C.RESULT_END_OF_INPUT
                partIndex++
                partOffset = 0
                openPart()
                continue
            }
            remaining -= n
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }
}

/** يوجّه upmedia:// للأجزاء، وكل شي ثاني للمصدر العادي */
class RoutingDataSource(
    private val normal: DataSource,
    private val multi: MultiPartDataSource,
    private val xt: DataSource = com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory()
        .setUserAgent(com.turkialawlqy.aman.yt.XtMedia.USER_AGENTS[0])
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
        .createDataSource(),
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        multi.addTransferListener(transferListener)
        xt.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = when {
            dataSpec.uri.scheme == UpMedia.SCHEME -> multi
            com.turkialawlqy.aman.yt.XtMedia.isStreamUri(dataSpec.uri) -> xt // أفلام/حلقات الاشتراك
            else -> normal
        }
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(upstream.createDataSource(), MultiPartDataSource(upstream.createDataSource()))
    }
}
