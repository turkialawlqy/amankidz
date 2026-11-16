import React, { useEffect, useRef, useState, useCallback, Suspense, lazy } from 'react';
import type {
  Device,
  ContentItem,
  ChildProfile,
  Category,
  LiveChannel,
  IptvSource,
  TvStateType,
} from './types';
import {
  bootstrapAuth,
  registerDevice,
  fetchDevice,
  fetchPairingCode,
  fetchContentItems,
  getEffectiveLibraryId,
  fetchProfiles,
  fetchCategories,
  fetchChannels,
  fetchSources,
  sendHeartbeat,
  subscribeToDeviceChanges,
  supabase,
} from './services/supabase';
import {
  getXtreamLatest,
  getXtreamLiveChannels,
  getXtreamSeriesDetails,
  streamToContentItem,
} from './services/xtream';
import { PairingScreen } from './components/PairingScreen';
import { HomeScreen } from './components/HomeScreen';
import { BrandMark } from './components/BrandMark';
import { useRemoteKeys } from './hooks/useRemoteKeys';
import { soundEffects } from './services/sound';
import { ResumePrompt } from './components/ResumePrompt';
import { AtvSpinner } from './components/AtvSpinner';
import { getContinueWatchingList, type ProgressItem } from './services/resume';

// تحميل كسول (Code Splitting) للمكونات الثقيلة غير المطلوبة في أول رسم للشاشة
// (المشغّل خصوصاً يجر مكتبة hls.js كاملة معه) — يقلّل من حجم الحزمة الأولى
// وزمن بدء التشغيل على معالج التلفزيون الضعيف نسبياً
const PlayerView = lazy(() => import('./components/PlayerView').then((m) => ({ default: m.PlayerView })));
const DetailsModal = lazy(() => import('./components/DetailsModal').then((m) => ({ default: m.DetailsModal })));
const CollectionScreen = lazy(() =>
  import('./components/CollectionScreen').then((m) => ({ default: m.CollectionScreen }))
);
const ProfileModal = lazy(() => import('./components/ProfileModal').then((m) => ({ default: m.ProfileModal })));
const ExitModal = lazy(() => import('./components/ExitModal').then((m) => ({ default: m.ExitModal })));
const SettingsModal = lazy(() => import('./components/SettingsModal').then((m) => ({ default: m.SettingsModal })));

export const App: React.FC = () => {
  const [state, setState] = useState<TvStateType>({ type: 'loading', text: 'جاري الاتصال بالخادم…' });

  // تفاصيل المحتوى والملفات
  const [device, setDevice] = useState<Device | null>(null);
  const [items, setItems] = useState<ContentItem[]>([]);
  const [profiles, setProfiles] = useState<ChildProfile[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [channels, setChannels] = useState<LiveChannel[]>([]);
  const [iptv, setIptv] = useState<IptvSource[]>([]);

  // الحالة النشطة
  const [activeProfile, setActiveProfile] = useState<ChildProfile | null>(null);
  const [selectedItem, setSelectedItem] = useState<ContentItem | null>(null);
  const [openCollection, setOpenCollection] = useState<ContentItem | null>(null);
  const [playingItem, setPlayingItem] = useState<ContentItem | null>(null);
  const [currentPlaylist, setCurrentPlaylist] = useState<ContentItem[]>([]);
  const [showProfileModal, setShowProfileModal] = useState(false);
  const [showExitModal, setShowExitModal] = useState(false);
  const [showSettingsModal, setShowSettingsModal] = useState(false);
  const [tvMessage, setTvMessage] = useState<string | null>(null);
  // نافذة "استئناف المتابعة" تظهر مرة واحدة عند فتح التطبيق
  const [resumeOffer, setResumeOffer] = useState<ProgressItem | null>(null);
  const resumeOfferShownRef = useRef(false);

  // تحديث البيانات من الخادم
  const loadData = useCallback(async (dev: Device) => {
    try {
      const libraryId = await getEffectiveLibraryId(dev);
      console.log('Using Library Device ID:', libraryId);

      const [fetchedItems, fetchedProfiles, fetchedCats, fetchedChans, fetchedSrcs] =
        await Promise.all([
          fetchContentItems(dev),
          fetchProfiles(),
          fetchCategories(),
          fetchChannels(),
          fetchSources(),
        ]);

      let combinedItems = [...fetchedItems];
      let combinedChannels = [...fetchedChans];

      // تحميل محتوى اشتراك الـ IPTV (Xtream) إذا وجد
      if (fetchedSrcs.length > 0) {
        console.log('Loading IPTV Xtream sources:', fetchedSrcs);
        try {
          const [xtLiveChannels, xtMovies, xtSeries] = await Promise.all([
            getXtreamLiveChannels(fetchedSrcs, 30),
            getXtreamLatest(fetchedSrcs, 'vod', 20),
            getXtreamLatest(fetchedSrcs, 'series', 20),
          ]);

          if (xtLiveChannels.length > 0 && combinedChannels.length === 0) {
            combinedChannels = xtLiveChannels;
          }

          const primarySrc = fetchedSrcs[0];
          const movieItems = xtMovies.map((m) => streamToContentItem(primarySrc, m, 'vod'));
          const seriesItems = xtSeries.map((s) => streamToContentItem(primarySrc, s, 'series'));

          combinedItems = [...combinedItems, ...movieItems, ...seriesItems];
        } catch (xtErr) {
          console.warn('Error loading Xtream data:', xtErr);
        }
      }

      setItems(combinedItems);
      setProfiles(fetchedProfiles);
      setCategories(fetchedCats);
      setChannels(combinedChannels);
      setIptv(fetchedSrcs);

      setState({
        type: 'ready',
        device: dev,
        items: combinedItems,
        profiles: fetchedProfiles,
        categories: fetchedCats,
        channels: combinedChannels,
        iptv: fetchedSrcs,
      });
    } catch (err) {
      console.error('Error loading data:', err);
    }
  }, []);

  // بداية تشغيل التطبيق وتوليد كود الربط أو الدخول المباشر
  const initApp = useCallback(async () => {
    try {
      setState({ type: 'loading', text: 'جاري تسجيل الجهاز…' });
      await bootstrapAuth();
      const dev = await registerDevice();
      setDevice(dev);

      if (!dev.parent_id) {
        // غير مربوط بحساب الأهل بعد -> توليد كود الربط
        setState({ type: 'loading', text: 'جاري توليد كود الربط…' });
        const codeData = await fetchPairingCode();
        setState({
          type: 'pairing',
          deviceCode: dev.device_code,
          code: codeData.code,
          expiresAtMs: Date.now() + 10 * 60 * 1000 - 15000,
        });
      } else {
        // مربوط بحساب الأهل -> تحميل المحتوى
        await loadData(dev);
      }

      // اشتراك في التحديثات اللحظية Realtime
      subscribeToDeviceChanges(
        dev.id,
        dev.library_id || dev.id,
        async () => {
          const freshDev = await fetchDevice(dev.id);
          if (freshDev) {
            setDevice(freshDev);
            if (!freshDev.parent_id) {
              const codeData = await fetchPairingCode();
              setState({
                type: 'pairing',
                deviceCode: freshDev.device_code,
                code: codeData.code,
                expiresAtMs: Date.now() + 10 * 60 * 1000 - 15000,
              });
            } else {
              loadData(freshDev);
            }
          }
        },
        (updatedDevice) => {
          setDevice((prev) => {
            if (
              !prev ||
              prev.library_id !== updatedDevice.library_id ||
              prev.parent_id !== updatedDevice.parent_id
            ) {
              loadData(updatedDevice);
            }
            return updatedDevice;
          });
          if (updatedDevice.tv_message?.text) {
            setTvMessage(updatedDevice.tv_message.text);
            soundEffects.playParentMessageChime();
          }
          if (updatedDevice.remote_cmd?.cmd) {
            const cmd = updatedDevice.remote_cmd.cmd.toLowerCase();
            if (cmd === 'stop') {
              setPlayingItem(null);
              setSelectedItem(null);
              setOpenCollection(null);
            } else if (cmd === 'reload') {
              window.location.reload();
            }
          }
        }
      );
    } catch (err: any) {
      console.error('Bootstrap error:', err);
      setState({
        type: 'error',
        message: err?.message || 'تعذر الاتصال بالخادم. تأكد من اتصال الإنترنت.',
      });
    }
  }, [loadData]);

  useEffect(() => {
    initApp();

    // إرسال نبضات الاتصال (Heartbeat) كل 3 دقائق
    const heartbeatInterval = setInterval(() => {
      sendHeartbeat();
    }, 3 * 60 * 1000);

    return () => clearInterval(heartbeatInterval);
  }, [initApp]);

  // إعادة الاتصال التلقائي للتطبيق بمجرد عودة الإنترنت
  useEffect(() => {
    const handleOnline = () => {
      console.log('App detected internet reconnection!');
      if (state.type === 'error') {
        initApp();
      } else if (device) {
        loadData(device);
      }
    };

    window.addEventListener('online', handleOnline);
    return () => window.removeEventListener('online', handleOnline);
  }, [state.type, device, initApp, loadData]);

  useEffect(() => {
    if (resumeOfferShownRef.current || state.type !== 'ready' || !device || device.locked) return;
    resumeOfferShownRef.current = true;
    const last = getContinueWatchingList().find((e) => !!e.item);
    if (last) setResumeOffer(last);
  }, [state.type, device]);

  // إغلاق النوافذ عند الضغط على زر الرجوع في ريموت LG
  const handleRemoteBack = useCallback(() => {
    if (resumeOffer) {
      setResumeOffer(null);
    } else if (playingItem) {
      setPlayingItem(null);
    } else if (openCollection) {
      setOpenCollection(null);
    } else if (selectedItem) {
      setSelectedItem(null);
    } else if (showSettingsModal) {
      setShowSettingsModal(false);
    } else if (showProfileModal) {
      setShowProfileModal(false);
    } else if (showExitModal) {
      setShowExitModal(false);
    } else {
      setShowExitModal(true);
    }
  }, [resumeOffer, playingItem, openCollection, selectedItem, showSettingsModal, showProfileModal, showExitModal]);

  useRemoteKeys({
    onBack: handleRemoteBack,
    enabled: !playingItem,
  });

  // الخروج من التطبيق عبر webOS API
  const handleExitApp = () => {
    if ((window as any).webOSSystem?.platformBack) {
      (window as any).webOSSystem.platformBack();
    } else {
      window.close();
    }
  };

  // فك ارتباط الجهاز وإعادة ضبطه
  const handleResetDevice = async () => {
    try {
      await supabase.auth.signOut();
      localStorage.removeItem('aman_webos_auth');
      localStorage.removeItem('amankidz_resume_map');
      setShowSettingsModal(false);
      setDevice(null);
      initApp();
    } catch (err) {
      console.error('Reset device error:', err);
    }
  };

  // شاشة التحميل
  if (state.type === 'loading') {
    return (
      <div className="w-full h-full flex flex-col items-center justify-center bg-[#0B0B0D] text-white">
        <BrandMark size={84} className="mb-10 atv-rise" />
        <AtvSpinner size={40} className="mb-5" />
        <p className="text-white/55 text-base font-medium">{state.text}</p>
      </div>
    );
  }

  // شاشة الخطأ
  if (state.type === 'error') {
    return (
      <div className="w-full h-full flex flex-col items-center justify-center bg-[#0B0B0D] text-white p-12 text-center">
        <div className="text-6xl mb-6">⚠️</div>
        <h2 className="text-3xl font-bold mb-4">خطأ في الاتصال</h2>
        <p className="text-[#A1A1A6] text-xl mb-8 max-w-lg">{state.message}</p>
        <button
          tabIndex={0}
          onClick={initApp}
          className="tv-focusable bg-[#FFFFFF] text-[#0B0B0D] font-bold text-xl px-10 py-4 rounded-2xl shadow-xl focus:ring-4 focus:ring-white"
        >
          إعادة المحاولة
        </button>
      </div>
    );
  }

  // شاشة الربط (Pairing)
  if (state.type === 'pairing') {
    return (
      <PairingScreen
        deviceCode={state.deviceCode}
        code={state.code}
        expiresAtMs={state.expiresAtMs}
      />
    );
  }

  // شاشة قفل التلفزيون بطلب الأهل
  if (device?.locked) {
    return (
      <div className="w-full h-full flex flex-col items-center justify-center bg-[#0B0B0D] text-white p-12 text-center select-none">
        <div className="w-28 h-28 rounded-3xl bg-red-500/20 text-red-400 flex items-center justify-center text-6xl mb-6 border border-red-500/30">
          🔒
        </div>
        <h1 className="text-4xl font-extrabold mb-4">التلفزيون مقفل</h1>
        <p className="text-[#A1A1A6] text-xl max-w-md">
          تم قفل التلفزيون من قبل ولي الأمر. يمكنك فتحه مجدداً من لوحة التحكم.
        </p>
      </div>
    );
  }

  const handleSelectItem = async (item: ContentItem) => {
    if (item.parent_id) {
      // هذا العنصر حلقة من مسلسل أو قائمة تجميعية!
      let eps = items.filter((it) => it.parent_id === item.parent_id);
      if (eps.length === 0 && item.parent_id.startsWith('xs:')) {
        // جلب حلقات المسلسل من Xtream إذا لم تكن محملة بعد
        const src = iptv.find((s) => item.parent_id!.includes(s.id)) || iptv[0];
        const seriesId = item.parent_id.split(':').pop()!;
        if (src && seriesId) {
          try {
            const { episodes } = await getXtreamSeriesDetails(src, seriesId);
            setItems((prev) => {
              const filtered = prev.filter((p) => p.parent_id !== item.parent_id);
              return [...filtered, ...episodes];
            });
            eps = episodes;
          } catch (e) {
            console.warn('Failed to load series sibling episodes:', e);
          }
        }
      }
      setCurrentPlaylist(eps.length > 0 ? eps : [item]);
      setPlayingItem(item);
      return;
    }
    
    if (item.id.startsWith('xs:')) {
      // جلب حلقات مسلسل من اشتراك Xtream
      const src = iptv.find((s) => item.id.includes(s.id)) || iptv[0];
      const seriesId = item.id.split(':').pop()!;
      try {
        const { episodes } = await getXtreamSeriesDetails(src, seriesId);
        setItems((prev) => {
          const filtered = prev.filter((p) => p.parent_id !== item.id);
          return [...filtered, ...episodes];
        });
        setSelectedItem(item);
      } catch (e) {
        console.warn('Failed to load series episodes:', e);
        setSelectedItem(item);
      }
    } else if (
      item.kind === 'channel' ||
      item.kind === 'playlist' ||
      (item.kind === 'mix' && !item.description)
    ) {
      setOpenCollection(item);
    } else if (
      item.kind === 'video' &&
      !item.description &&
      (!item.cast_list || item.cast_list.length === 0)
    ) {
      setPlayingItem(item);
    } else {
      setSelectedItem(item);
    }
  };

  return (
    <div className="w-full h-full relative overflow-hidden bg-[#0B0B0D]">
      {/* رسالة فورية صوتية ومرئية من ولي الأمر من الجوال */}
      {tvMessage && (
        <div className="atv-toast atv-toast-info atv-toast-lg" style={{ top: 40 }} role="alert">
          <span className="atv-toast-icon text-3xl">👨‍👩‍👧‍👦</span>
          <div className="min-w-0">
            <span className="block text-xs font-bold text-white/55 mb-0.5">رسالة من ولي الأمر</span>
            <span className="block text-lg font-bold text-white">{tvMessage}</span>
          </div>
          <button tabIndex={0} onClick={() => setTvMessage(null)} className="atv-btn-primary h-11 px-5 rounded-full text-sm font-bold mr-2">
            حسناً
          </button>
        </div>
      )}

      {resumeOffer && !playingItem && (
        <ResumePrompt
          entry={resumeOffer}
          onCancel={() => setResumeOffer(null)}
          onResume={() => {
            const it = resumeOffer.item;
            setResumeOffer(null);
            if (!it) return;
            if (it.parent_id) {
              // حلقة: نفس مسار الحلقات (يحمّل باقي الحلقات للتالي/السابق)
              handleSelectItem(it);
            } else {
              setCurrentPlaylist([it]);
              setPlayingItem(it);
            }
          }}
        />
      )}

      {/* الشاشة الرئيسية */}
      {device && (
        <HomeScreen
          device={device}
          items={items}
          profiles={profiles}
          categories={categories}
          channels={channels}
          iptv={iptv}
          activeProfile={activeProfile}
          onOpenProfileModal={() => setShowProfileModal(true)}
          onOpenExitModal={() => setShowExitModal(true)}
          onOpenSettings={() => setShowSettingsModal(true)}
          onRefresh={() => {
            if (device) loadData(device);
          }}
          onSelectItem={handleSelectItem}
          onSelectChannel={(ch) => {
            // تشغيل القناة المباشرة
            const chItem: ContentItem = {
              id: ch.id,
              device_id: ch.device_id,
              kind: 'video',
              youtube_id: '',
              url: ch.stream_url,
              title: ch.name,
              thumbnail_url: ch.logo,
              position: ch.position,
              offline: false,
              excluded_video_ids: [],
              profile_ids: ch.profile_ids,
              require_approval: false,
              approved_video_ids: [],
              educational: false,
              genres: [ch.group_name || 'بث مباشر'],
              tags: [],
              cast_list: [],
              follow: false,
            };
            setPlayingItem(chItem);
          }}
        />
      )}

      {/* صفحة عرض القناة أو قائمة التشغيل */}
      {openCollection && (
        <div className={playingItem ? 'hidden' : 'block'}>
          <Suspense fallback={null}>
            <CollectionScreen
              item={openCollection}
              videos={items.filter((it) => it.parent_id === openCollection.id)}
              onPlayVideo={(v) => {
                const list = items.filter((it) => it.parent_id === openCollection.id);
                setCurrentPlaylist(list);
                setPlayingItem(v);
              }}
              onBack={() => setOpenCollection(null)}
            />
          </Suspense>
        </div>
      )}

      {/* نافذة تفاصيل الفيلم / المسلسل */}
      {selectedItem && (
        <div className={playingItem ? 'hidden' : 'block'}>
          <Suspense fallback={null}>
            <DetailsModal
              item={selectedItem}
              episodes={items.filter((it) => it.parent_id === selectedItem.id)}
              onPlay={(targetItem, playlist) => {
                // لا نلغي selectedItem حتى يعود المستخدم لنفس المسلسل والموسم عند الرجوع من المشغل!
                setCurrentPlaylist(playlist || [targetItem]);
                setPlayingItem(targetItem);
              }}
              onClose={() => setSelectedItem(null)}
            />
          </Suspense>
        </div>
      )}

      {/* مشغل الفيديو الكامل */}
      {playingItem && device && (
        <Suspense
          fallback={
            <div className="fixed inset-0 z-50 bg-black flex items-center justify-center">
              <AtvSpinner size={52} />
            </div>
          }
        >
        <PlayerView
          item={playingItem}
          playlist={currentPlaylist}
          device={device}
          liveChannels={channels}
          activeProfileId={activeProfile?.id || null}
          onClose={() => setPlayingItem(null)}
          onSelectEpisode={(ep) => setPlayingItem(ep)}
          onSelectChannel={(ch) => {
            const chItem: ContentItem = {
              id: ch.id,
              device_id: ch.device_id,
              kind: 'video',
              youtube_id: '',
              url: ch.stream_url,
              title: ch.name,
              thumbnail_url: ch.logo,
              position: ch.position,
              offline: false,
              excluded_video_ids: [],
              profile_ids: ch.profile_ids,
              require_approval: false,
              approved_video_ids: [],
              educational: false,
              genres: [ch.group_name || 'بث مباشر'],
              tags: [],
              cast_list: [],
              follow: false,
            };
            setCurrentPlaylist([chItem]);
            setPlayingItem(chItem);
          }}
          onNext={() => {
            // الانتقال للحلقة التالية في قائمة الحلقات الحالية
            const list = currentPlaylist.length > 0 ? currentPlaylist : items;
            const idx = list.findIndex((i) => i.id === playingItem.id);
            if (idx >= 0 && idx < list.length - 1) {
              setPlayingItem(list[idx + 1]);
            }
          }}
          onPrevious={() => {
            // الانتقال للحلقة السابقة
            const list = currentPlaylist.length > 0 ? currentPlaylist : items;
            const idx = list.findIndex((i) => i.id === playingItem.id);
            if (idx > 0) {
              setPlayingItem(list[idx - 1]);
            }
          }}
        />
        </Suspense>
      )}

      {/* نافذة اختيار ملف الطفل الشخصي */}
      {showProfileModal && (
        <Suspense fallback={null}>
          <ProfileModal
            profiles={profiles}
            currentProfileId={activeProfile?.id || null}
            onSelectProfile={(p) => setActiveProfile(p)}
            onClose={() => setShowProfileModal(false)}
          />
        </Suspense>
      )}

      {/* نافذة الخروج برمز الأمان */}
      {showExitModal && (
        <Suspense fallback={null}>
          <ExitModal
            hasPin={!!device?.settings_pin}
            onExit={handleExitApp}
            onClose={() => setShowExitModal(false)}
          />
        </Suspense>
      )}

      {/* نافذة إعدادات التلفزيون الشاملة */}
      {showSettingsModal && device && (
        <Suspense fallback={null}>
          <SettingsModal
            device={device}
            itemsCount={items.length}
            channelsCount={channels.length}
            iptvSources={iptv}
            onRefresh={() => loadData(device)}
            onResetDevice={handleResetDevice}
            onExitApp={handleExitApp}
            onClose={() => setShowSettingsModal(false)}
          />
        </Suspense>
      )}
    </div>
  );
};
