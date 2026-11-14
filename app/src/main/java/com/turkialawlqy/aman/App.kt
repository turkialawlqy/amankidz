package com.turkialawlqy.aman

import android.app.Application
import com.turkialawlqy.aman.data.TvRepository
import com.turkialawlqy.aman.net.NewPipeDownloader
import com.turkialawlqy.aman.offline.OfflineStore
import com.turkialawlqy.aman.potoken.PoTokenProviderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

class App : Application(), coil3.SingletonImageLoader.Factory {
    /** محمّل الصور: يعرض النسخة المحلية أول (صور الممثلين والخلفيات تظهر بدون إنترنت) */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components { add(com.turkialawlqy.aman.offline.ImageStore.LocalFirst) }
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.turkialawlqy.aman.control.CrashReporter.init(this) // أعطال وتعليق → لوحة التحكم

        // استخراج يوتيوب مباشرة من الجهاز (بدون API key وبدون إعلانات)
        NewPipe.init(NewPipeDownloader, Localization("ar", "SA"), ContentCountry("SA"))
        YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)

        com.turkialawlqy.aman.ui.AppThemes.init(this)
        OfflineStore.init(this)
        com.turkialawlqy.aman.offline.ImageStore.init(this)
        com.turkialawlqy.aman.player.ResumeStore.init(this)
        com.turkialawlqy.aman.player.FavoritesStore.init(this)
        com.turkialawlqy.aman.control.ProfileStore.init(this)
        com.turkialawlqy.aman.player.LiveStore.refresh()
        runCatching { com.turkialawlqy.aman.control.SeriesFollow.load() }
        Thread { runCatching { com.turkialawlqy.aman.offline.LocalDb.trim() } }.start()
        com.turkialawlqy.aman.control.Notifier.init(this)
        com.turkialawlqy.aman.control.WatchTracker.init(this)
        TvRepository.start(this)
        watchUsb()
        com.turkialawlqy.aman.control.AppUpdater.init(this)
        // نعرف إذا التطبيق ظاهر أو بالخلفية (عشان التحديث التلقائي ما يقاطع الطفل)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { com.turkialawlqy.aman.control.AppUpdater.visibleActivities++ }
            override fun onActivityStopped(a: android.app.Activity) {
                com.turkialawlqy.aman.control.AppUpdater.visibleActivities =
                    (com.turkialawlqy.aman.control.AppUpdater.visibleActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** فصل/تركيب الفلاشة: نقفل الذاكرة فوراً لما تنفصل، ونرجع نفتحها لما ترجع (بدون إعادة تحميل) */
    private fun watchUsb() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.action == android.content.Intent.ACTION_MEDIA_MOUNTED) {
                    val s = TvRepository.state.value as? com.turkialawlqy.aman.data.TvState.Ready ?: return
                    Thread {
                        runCatching { OfflineStore.ensure(s.device.storageVolume) }
                        runCatching { com.turkialawlqy.aman.offline.XtLocal.refresh(this@App, force = true) } // فلاشة جديدة: نشوف وش عليها
                        com.turkialawlqy.aman.offline.OfflineDownloader.schedule(s)
                    }.start()
                } else {
                    val path = i.data?.path
                    Thread { OfflineStore.onMediaGone(path) }.start()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
