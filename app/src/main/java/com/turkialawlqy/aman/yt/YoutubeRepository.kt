package com.turkialawlqy.aman.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@kotlinx.serialization.Serializable
data class VideoEntry(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val durationSec: Long,
    val uploader: String?,
)

/** صفحة من فيديوهات قائمة تشغيل أو قناة، مع ما يلزم لجلب الصفحة التالية. */
class CollectionPager(private val kind: String, private val url: String) {
    private val service = ServiceList.YouTube
    private var channelTab: ListLinkHandler? = null
    private var nextPage: Page? = null
    private var started = false

    var title: String? = null
        private set

    val hasMore: Boolean get() = !started || nextPage != null

    suspend fun loadNext(): List<VideoEntry> = withContext(Dispatchers.IO) {
        if (started && nextPage == null) return@withContext emptyList()
        val items: List<InfoItem>
        if (!started) {
            when (kind) {
                "playlist" -> {
                    val info = PlaylistInfo.getInfo(service, url)
                    title = info.name
                    items = info.relatedItems
                    nextPage = info.nextPage
                }
                "channel" -> {
                    val channel = ChannelInfo.getInfo(service, url)
                    title = channel.name
                    val tab = channel.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                        ?: channel.tabs.firstOrNull()
                        ?: run { started = true; return@withContext emptyList() }
                    channelTab = tab
                    val tabInfo = ChannelTabInfo.getInfo(service, tab)
                    items = tabInfo.relatedItems
                    nextPage = tabInfo.nextPage
                }
                else -> return@withContext emptyList()
            }
            started = true // بعد النجاح فقط، عشان "إعادة المحاولة" تشتغل
        } else {
            val page = nextPage!!
            val result = when (kind) {
                "playlist" -> PlaylistInfo.getMoreItems(service, url, page)
                else -> ChannelTabInfo.getMoreItems(service, channelTab!!, page)
            }
            items = result.items
            nextPage = result.nextPage
        }
        if (!Page.isValid(nextPage)) nextPage = null
        items.filterIsInstance<StreamInfoItem>().map { it.toEntry() }
    }
}

fun StreamInfoItem.toEntry() = VideoEntry(
    url = url,
    title = name,
    thumbnail = thumbnails.bestUrl(),
    durationSec = duration,
    uploader = uploaderName,
)

fun List<Image>.bestUrl(): String? =
    filter { it.height <= 0 || it.height <= 480 }.maxByOrNull { it.height }?.url
        ?: firstOrNull()?.url
