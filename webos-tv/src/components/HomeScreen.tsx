import React, { useState, useMemo, useEffect } from 'react';
import type { Device, ContentItem, ChildProfile, Category, LiveChannel, IptvSource } from '../types';
import { IptvBrowseView } from './IptvBrowseView';
import { SearchView } from './SearchView';
import { SanjoobModal } from './SanjoobModal';
import {
  getContinueWatchingList,
  getContinueWatchingSeries,
  getContinueWatchingMovies,
  removeFromContinueWatching,
  getProgressRatio,
  type ProgressItem,
} from '../services/resume';
import { getUnlockedBadges } from '../services/badges';
import { checkCurrentRoutine } from '../services/routine';

interface HomeScreenProps {
  device: Device;
  items: ContentItem[];
  profiles: ChildProfile[];
  categories: Category[];
  channels: LiveChannel[];
  iptv: IptvSource[];
  activeProfile: ChildProfile | null;
  onOpenProfileModal: () => void;
  onOpenExitModal: () => void;
  onOpenSettings?: () => void;
  onSelectItem: (item: ContentItem) => void;
  onSelectChannel: (channel: LiveChannel) => void;
  onRefresh?: () => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  device: _device,
  items,
  iptv,
  activeProfile,
  categories: _categories,
  channels,
  onOpenProfileModal,
  onOpenExitModal,
  onOpenSettings,
  onSelectItem,
  onSelectChannel,
}) => {
  const [activeTab, setActiveTab] = useState<'home' | 'continue' | 'live' | 'movies' | 'series' | 'favorites' | 'search'>('home');
  const [continueWatching, setContinueWatching] = useState<ProgressItem[]>(getContinueWatchingList());
  const [continueFilter, setContinueFilter] = useState<'all' | 'series' | 'movies'>('all');
  const [showSanjoob, setShowSanjoob] = useState(false);
  const routineStatus = checkCurrentRoutine();

  // ساعة زخرفية في الشريط العلوي (بدون طقس حقيقي: يحتاج موقع جغرافي ومفتاح API خارجي)
  const [clockTime, setClockTime] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setClockTime(new Date()), 30 * 1000);
    return () => clearInterval(timer);
  }, []);

  // تحديث فوري لقائمة متابعة المشاهدة عند إغلاق المشغل أو تغيير التخزين
  useEffect(() => {
    const refresh = () => setContinueWatching(getContinueWatchingList());
    refresh();
    window.addEventListener('aman_continue_watching_updated', refresh);
    window.addEventListener('storage', refresh);
    window.addEventListener('focus', refresh);
    return () => {
      window.removeEventListener('aman_continue_watching_updated', refresh);
      window.removeEventListener('storage', refresh);
      window.removeEventListener('focus', refresh);
    };
  }, [items]);

  const continueSeries = useMemo(() => getContinueWatchingSeries(), [continueWatching]);
  const continueMovies = useMemo(() => getContinueWatchingMovies(), [continueWatching]);

  // تصفية المحتوى المسموح به للطفل النشط
  const visibleItems = useMemo(() => {
    if (!items || items.length === 0) return [];
    if (!activeProfile) return items;
    return items.filter(
      (item) =>
        !item.profile_ids ||
        item.profile_ids.length === 0 ||
        item.profile_ids.includes(activeProfile.id)
    );
  }, [items, activeProfile]);

  // فصل الأفلام والمسلسلات
  const movies = useMemo(() => {
    return visibleItems.filter(
      (it) => it.kind === 'video' || it.id.startsWith('xv:')
    );
  }, [visibleItems]);

  const series = useMemo(() => {
    return visibleItems.filter(
      (it) => it.kind === 'mix' || it.kind === 'playlist' || it.id.startsWith('xs:')
    );
  }, [visibleItems]);

  const favorites = useMemo(() => {
    return visibleItems.filter((it) => it.follow);
  }, [visibleItems]);

  // عناصر البنر الرئيسي المتحركة (حتى 5 عناصر مميزة، نفس أولوية الاختيار السابقة)
  const featuredItems = useMemo(() => {
    const combined = [...series, ...movies].slice(0, 5);
    return combined.length > 0 ? combined : visibleItems.slice(0, 5);
  }, [series, movies, visibleItems]);

  const [heroIndex, setHeroIndex] = useState(0);

  // دوران تلقائي للبنر كل 7 ثوانٍ، يُعاد ضبطه تلقائياً عند أي تنقّل يدوي
  useEffect(() => {
    if (featuredItems.length <= 1) return;
    const timer = setInterval(() => {
      setHeroIndex((i) => (i + 1) % featuredItems.length);
    }, 7000);
    return () => clearInterval(timer);
  }, [featuredItems.length, heroIndex]);

  // العنصر المميز الحالي في البنر العلوي (Hero)
  const safeHeroIndex = featuredItems.length > 0 ? heroIndex % featuredItems.length : 0;
  const featuredItem = featuredItems[safeHeroIndex] || null;

  const goToHero = (idx: number) => {
    if (featuredItems.length === 0) return;
    setHeroIndex(((idx % featuredItems.length) + featuredItems.length) % featuredItems.length);
  };

  // صورة البنر والخلفية
  const heroImage =
    featuredItem?.backdrop_url ||
    featuredItem?.thumbnail_url ||
    featuredItem?.poster_url ||
    '';

  return (
    <div
      className="w-screen h-screen bg-[#0A101D] text-white flex overflow-hidden select-none font-tajawal"
      dir="ltr"
    >
      {/* القائمة الجانبية الدائمة بالأيقونات (يسار الشاشة دائماً بغض النظر عن اتجاه RTL) */}
      <aside className="w-20 h-full flex-shrink-0 bg-[#0A101D] border-l border-white/10 flex flex-col items-center py-4 gap-1.5 z-30" dir="rtl">
        <span className="text-2xl mb-2" title="أمان كيدز">
          🛡️
        </span>

        <button
          tabIndex={0}
          onClick={onOpenSettings || onOpenExitModal}
          className="tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl text-[#94A3B8] hover:text-white hover:bg-white/5 focus:bg-[#38BDF8] focus:text-[#0A101D] transition"
          title="الإعدادات"
        >
          ⚙️
        </button>

        <div className="w-10 h-px bg-white/10 my-1.5" />

        <button
          tabIndex={0}
          onClick={() => setActiveTab('search')}
          className={`tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'search' ? 'bg-[#38BDF8] text-[#0A101D]' : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="بحث"
        >
          🔍
        </button>

        <button
          tabIndex={0}
          onClick={() => setActiveTab('home')}
          className={`tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'home' ? 'bg-[#38BDF8] text-[#0A101D]' : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="الرئيسية"
        >
          🏠
        </button>

        <button
          tabIndex={0}
          onClick={() => setActiveTab('live')}
          className={`tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'live' ? 'bg-[#38BDF8] text-[#0A101D]' : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="البث المباشر"
        >
          📺
        </button>

        <button
          tabIndex={0}
          onClick={() => setActiveTab('movies')}
          className={`tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'movies' || activeTab === 'series'
              ? 'bg-[#38BDF8] text-[#0A101D]'
              : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="الأفلام والمسلسلات"
        >
          🎬
        </button>

        <button
          tabIndex={0}
          onClick={() => setActiveTab('favorites')}
          className={`tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'favorites' ? 'bg-[#38BDF8] text-[#0A101D]' : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="المفضلة"
        >
          🤍
        </button>

        <button
          tabIndex={0}
          onClick={() => setActiveTab('continue')}
          className={`tv-focusable relative w-12 h-12 rounded-2xl flex items-center justify-center text-xl transition ${
            activeTab === 'continue' ? 'bg-[#38BDF8] text-[#0A101D]' : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
          }`}
          title="متابعة المشاهدة"
        >
          ⏱️
          {continueWatching.length > 0 && (
            <span className="absolute -top-1 -left-1 bg-red-500 text-white text-[10px] font-black w-4 h-4 rounded-full flex items-center justify-center">
              {continueWatching.length}
            </span>
          )}
        </button>

        <button
          tabIndex={0}
          onClick={() => setShowSanjoob(true)}
          className="tv-focusable w-12 h-12 rounded-2xl flex items-center justify-center text-xl text-amber-300 hover:bg-amber-500/10 focus:bg-amber-400 focus:text-[#0A101D] transition"
          title="اسأل سنجوب"
        >
          🐿️
        </button>

        <div className="flex-1" />

        <button
          tabIndex={0}
          onClick={onOpenProfileModal}
          className="tv-focusable relative w-12 h-12 rounded-2xl flex items-center justify-center text-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] transition"
          title={activeProfile ? activeProfile.name : 'الحساب'}
        >
          {activeProfile ? activeProfile.avatar : '🧒'}
          <span className="absolute -bottom-1 -left-1 bg-amber-500 text-[#0A101D] text-[9px] font-black w-4 h-4 rounded-full flex items-center justify-center">
            {getUnlockedBadges().length}
          </span>
        </button>
      </aside>

      {/* عمود المحتوى: شريط علوي رفيع (تبويبات + ساعة) ثم جسم الصفحة */}
      <div className="flex-1 h-full flex flex-col overflow-hidden" dir="rtl">
        <header className="w-full h-16 px-6 flex items-center justify-between border-b border-white/10 bg-[#0A101D]/90 backdrop-blur-md z-20 flex-shrink-0">
          <nav className="flex items-center gap-2">
            <button
              tabIndex={0}
              onClick={() => setActiveTab('live')}
              className={`tv-focusable px-4 py-2 rounded-xl text-base font-bold transition-all ${
                activeTab === 'live'
                  ? 'bg-[#1E293B] text-[#38BDF8] border-b-2 border-[#38BDF8] shadow-sm'
                  : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
              }`}
            >
              البث المباشر
            </button>
            <button
              tabIndex={0}
              onClick={() => setActiveTab('movies')}
              className={`tv-focusable px-4 py-2 rounded-xl text-base font-bold transition-all ${
                activeTab === 'movies'
                  ? 'bg-[#1E293B] text-[#38BDF8] border-b-2 border-[#38BDF8] shadow-sm'
                  : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
              }`}
            >
              الأفلام
            </button>
            <button
              tabIndex={0}
              onClick={() => setActiveTab('series')}
              className={`tv-focusable px-4 py-2 rounded-xl text-base font-bold transition-all ${
                activeTab === 'series'
                  ? 'bg-[#1E293B] text-[#38BDF8] border-b-2 border-[#38BDF8] shadow-sm'
                  : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
              }`}
            >
              المسلسلات
            </button>
            <button
              tabIndex={0}
              onClick={() => setActiveTab('home')}
              className={`tv-focusable px-4 py-2 rounded-xl text-base font-bold transition-all ${
                activeTab === 'home'
                  ? 'bg-[#1E293B] text-[#38BDF8] border-b-2 border-[#38BDF8] shadow-sm'
                  : 'text-[#94A3B8] hover:text-white hover:bg-white/5'
              }`}
            >
              الرئيسية
            </button>
          </nav>

          {/* ساعة زخرفية (بدون طقس حقيقي — يحتاج موقعاً جغرافياً ومفتاح API خارجي لم يُطلب) */}
          <div className="flex items-center gap-2 text-sm text-[#94A3B8] font-bold font-mono" dir="ltr" title="الساعة (زخرفية)">
            <span>🕐</span>
            <span>{clockTime.toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit' })}</span>
          </div>
        </header>

        {/* 2. جسم الصفحة بحسب التبويب النشط */}
      {activeTab === 'continue' ? (
        <main className="flex-1 overflow-y-auto px-8 py-6 space-y-6">
          <div className="flex items-center justify-between border-b border-white/10 pb-4">
            <div>
              <h1 className="text-3xl font-black text-white flex items-center gap-3">
                <span className="text-[#38BDF8]">⏱️</span>
                <span>متابعة المشاهدة</span>
              </h1>
              <p className="text-sm text-[#94A3B8] mt-1">
                استكمل مشاهدة مسلسلاتك وأفلامك من حيث توقفت بالضبط
              </p>
            </div>

            {/* أزرار التصفية: الكل / المسلسلات / الأفلام */}
            <div className="flex items-center gap-2 bg-[#1E293B]/70 p-1.5 rounded-2xl border border-white/10">
              <button
                tabIndex={0}
                onClick={() => setContinueFilter('all')}
                className={`tv-focusable px-4 py-2 rounded-xl text-sm font-bold transition ${
                  continueFilter === 'all'
                    ? 'bg-[#38BDF8] text-[#0A101D]'
                    : 'text-white hover:bg-white/10'
                }`}
              >
                الكل ({continueWatching.length})
              </button>
              <button
                tabIndex={0}
                onClick={() => setContinueFilter('series')}
                className={`tv-focusable px-4 py-2 rounded-xl text-sm font-bold transition ${
                  continueFilter === 'series'
                    ? 'bg-[#38BDF8] text-[#0A101D]'
                    : 'text-white hover:bg-white/10'
                }`}
              >
                📺 المسلسلات ({continueSeries.length})
              </button>
              <button
                tabIndex={0}
                onClick={() => setContinueFilter('movies')}
                className={`tv-focusable px-4 py-2 rounded-xl text-sm font-bold transition ${
                  continueFilter === 'movies'
                    ? 'bg-[#38BDF8] text-[#0A101D]'
                    : 'text-white hover:bg-white/10'
                }`}
              >
                🎬 الأفلام ({continueMovies.length})
              </button>
            </div>
          </div>

          {/* محتوى قائمة المتابعة */}
          {(() => {
            const list =
              continueFilter === 'series'
                ? continueSeries
                : continueFilter === 'movies'
                ? continueMovies
                : continueWatching;

            if (list.length === 0) {
              return (
                <div className="h-80 flex flex-col items-center justify-center text-center text-[#94A3B8] space-y-4">
                  <div className="w-24 h-24 rounded-3xl bg-white/5 flex items-center justify-center text-5xl border border-white/10">
                    🍿
                  </div>
                  <div>
                    <h3 className="text-2xl font-bold text-white mb-1">
                      {continueFilter === 'series'
                        ? 'لا توجد مسلسلات قيد المتابعة حالياً'
                        : continueFilter === 'movies'
                        ? 'لا توجد أفلام قيد المتابعة حالياً'
                        : 'لم تبدأ مشاهدة أي فيلم أو مسلسل بعد'}
                    </h3>
                    <p className="text-base text-[#64748B] max-w-md mx-auto">
                      ابدأ بمشاهدة أي حلقة أو فيلم وستظهر هنا تلقائياً لتعود إليها في أي وقت بضغطة زر واحدة!
                    </p>
                  </div>
                  <button
                    tabIndex={0}
                    onClick={() => setActiveTab('home')}
                    className="tv-focusable bg-[#38BDF8] text-[#0A101D] font-bold text-base px-6 py-2.5 rounded-xl shadow-lg hover:scale-105 transition"
                  >
                    تصفح المكتبة الآن
                  </button>
                </div>
              );
            }

            return (
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-5 pb-12">
                {list.map((entry) => {
                  const ratio = getProgressRatio(entry.id);
                  const isSeriesItem =
                    !!entry.item?.parent_id ||
                    entry.item?.kind === 'mix' ||
                    entry.item?.kind === 'playlist' ||
                    entry.id.startsWith('xs:') ||
                    (entry.title && (entry.title.includes('حلقة') || entry.title.includes('الحلقة')));
                  const remainingSec = Math.max(0, entry.dur - entry.pos);
                  const remMin = Math.max(1, Math.round(remainingSec / 60));

                  return (
                    <div
                      key={entry.id}
                      tabIndex={0}
                      role="button"
                      onClick={() => {
                        if (entry.item) onSelectItem(entry.item);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && entry.item) {
                          onSelectItem(entry.item);
                        }
                      }}
                      className="group flex flex-col cursor-pointer focus:outline-none relative"
                    >
                      <div className="relative aspect-[16/10] w-full rounded-2xl overflow-hidden bg-[#1E293B] border-2 border-transparent group-focus:border-[#38BDF8] group-focus:scale-105 group-focus:shadow-2xl group-focus:shadow-[#38BDF8]/40 transition-all duration-200 shadow-lg">
                        {entry.thumb ? (
                          <img src={entry.thumb} alt={entry.title} className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full flex flex-col items-center justify-center bg-[#141A29]">
                            <span className="text-4xl">{isSeriesItem ? '📺' : '🎬'}</span>
                          </div>
                        )}

                        {/* بادج النوع */}
                        <div className="absolute top-2.5 right-2.5 bg-black/80 backdrop-blur-sm px-2.5 py-1 rounded-lg text-[11px] font-bold text-white flex items-center gap-1.5 border border-white/10">
                          <span>{isSeriesItem ? '📺 مسلسل' : '🎬 فيلم'}</span>
                        </div>

                        {/* زر حذف من المتابعة */}
                        <button
                          tabIndex={-1}
                          onClick={(e) => {
                            e.stopPropagation();
                            removeFromContinueWatching(entry.id);
                          }}
                          className="absolute top-2.5 left-2.5 bg-black/60 hover:bg-red-600/90 text-white w-7 h-7 rounded-lg flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 group-focus:opacity-100 transition"
                          title="إزالة من القائمة"
                        >
                          ✕
                        </button>

                        {/* زر الاستئناف في المنتصف عند التركيز */}
                        <div className="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-focus:opacity-100 group-hover:opacity-100 transition">
                          <div className="w-12 h-12 rounded-full bg-[#38BDF8] text-[#0A101D] flex items-center justify-center text-2xl font-black shadow-xl">
                            ▶
                          </div>
                        </div>

                        {/* شريط التقدم السفلي */}
                        <div className="absolute inset-x-0 bottom-0 h-2 bg-black/80">
                          <div
                            className="h-full bg-[#38BDF8] transition-all"
                            style={{ width: `${Math.max(6, Math.min(100, Math.round(ratio * 100)))}%` }}
                          />
                        </div>
                      </div>

                      {/* معلومات العنوان والوقت المتبقي */}
                      <div className="mt-2.5 flex flex-col gap-0.5 text-right">
                        <span className="text-sm font-bold text-white truncate w-full group-focus:text-[#38BDF8] transition-colors">
                          {entry.title}
                        </span>
                        <div className="flex items-center justify-between text-xs text-[#94A3B8]">
                          <span>متبقي: {remMin} دقيقة</span>
                          <span className="font-mono text-[#38BDF8]">{Math.round(ratio * 100)}%</span>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            );
          })()}
        </main>
      ) : activeTab === 'live' && iptv && iptv.length > 0 ? (
        <div className="flex-1 h-full overflow-hidden">
          <IptvBrowseView
            sources={iptv}
            type="live"
            onSelectChannel={onSelectChannel}
            onSelectItem={onSelectItem}
          />
        </div>
      ) : activeTab === 'movies' && iptv && iptv.length > 0 ? (
        <div className="flex-1 h-full overflow-hidden">
          <IptvBrowseView
            sources={iptv}
            type="vod"
            onSelectChannel={onSelectChannel}
            onSelectItem={onSelectItem}
          />
        </div>
      ) : activeTab === 'series' && iptv && iptv.length > 0 ? (
        <div className="flex-1 h-full overflow-hidden">
          <IptvBrowseView
            sources={iptv}
            type="series"
            onSelectChannel={onSelectChannel}
            onSelectItem={onSelectItem}
          />
        </div>
      ) : activeTab === 'search' ? (
        <div className="flex-1 h-full overflow-hidden">
          <SearchView
            sources={iptv}
            onSelectItem={onSelectItem}
            onSelectChannel={onSelectChannel}
          />
        </div>
      ) : (
        /* التبويب الرئيسي الشامل المطابق لصورة المستخدم */
        <main className="flex-1 overflow-y-auto px-8 py-5 space-y-7">
          {/* إشعار الروتين اليومي وجدول المذاكرة والصلاة (إذا حان الوقت) */}
          {routineStatus.isLocked && (
            <div className="w-full bg-gradient-to-r from-amber-600/90 to-orange-600/90 border border-amber-300/40 rounded-2xl p-4 shadow-xl flex items-center justify-between">
              <div className="flex items-center gap-4">
                <span className="text-3xl">{routineStatus.icon}</span>
                <div>
                  <h3 className="text-lg font-black text-white">{routineStatus.message}</h3>
                  <p className="text-sm text-amber-100">
                    {routineStatus.remainingMinutes ? `متبقي على انتهاء الفترة: ${routineStatus.remainingMinutes} دقيقة` : 'خصص وقتاً لإنجاز واجباتك لتكون بطلاً متفوقاً!'}
                  </p>
                </div>
              </div>
              <span className="bg-black/30 text-white font-bold text-sm px-4 py-1.5 rounded-xl border border-white/20">
                جدول الروتين الذكي ⏰
              </span>
            </div>
          )}

          {/* بنر العرض الرئيسي الكبير (Hero Banner Card) */}
          {featuredItem && (
            <div className="relative w-full h-[42vh] rounded-3xl overflow-hidden shadow-2xl border border-white/10 bg-[#11192C]">
              {/* صورة الخلفية الكبيرة */}
              {heroImage ? (
                <img
                  src={heroImage}
                  alt={featuredItem.title}
                  className="w-full h-full object-cover object-center"
                />
              ) : (
                <div className="w-full h-full bg-gradient-to-r from-[#1E293B] to-[#0F172A]" />
              )}
              {/* التدرج اللوني فوق الصورة */}
              <div className="absolute inset-0 bg-gradient-to-r from-[#0A101D] via-[#0A101D]/75 to-transparent" />
              <div className="absolute inset-0 bg-gradient-to-t from-[#0A101D]/90 via-transparent to-transparent" />

              {/* محتوى البنر في الجانب الأيمن */}
              <div className="absolute inset-y-0 right-8 left-8 flex flex-col justify-center max-w-2xl z-10 text-right">
                <div className="flex items-center gap-2.5 mb-2.5">
                  <span className="text-xs font-black bg-[#2563EB] text-white px-2.5 py-0.5 rounded">
                    مميز
                  </span>
                  {featuredItem.year && (
                    <span className="text-xs font-bold text-[#94A3B8] bg-black/40 px-2 py-0.5 rounded">
                      {featuredItem.year}
                    </span>
                  )}
                  <span className="text-xs font-bold text-emerald-400 bg-emerald-500/20 px-2.5 py-0.5 rounded border border-emerald-500/30">
                    آمن للأطفال ✓
                  </span>
                </div>

                <h1 className="text-3xl lg:text-4xl font-extrabold text-white mb-2.5 uppercase tracking-wide drop-shadow-lg leading-tight font-sans">
                  {featuredItem.title}
                </h1>

                {featuredItem.description && (
                  <p className="text-sm lg:text-base text-[#CBD5E1] line-clamp-2 mb-5 leading-relaxed max-w-xl drop-shadow">
                    {featuredItem.description}
                  </p>
                )}

                {/* أزرار الإجراء السريع (Watch Now / Details) */}
                <div className="flex items-center gap-3.5">
                  <button
                    tabIndex={0}
                    onClick={() => onSelectItem(featuredItem)}
                    className="tv-focusable bg-[#2563EB] hover:bg-[#1D4ED8] focus:bg-[#38BDF8] focus:text-[#0A101D] text-white font-black text-base px-7 py-3 rounded-xl shadow-xl flex items-center gap-2 transition"
                  >
                    <span>شاهد الآن</span>
                    <span className="text-lg">▶</span>
                  </button>

                  <button
                    tabIndex={0}
                    onClick={() => onSelectItem(featuredItem)}
                    className="tv-focusable bg-white/10 hover:bg-white/20 focus:bg-white focus:text-[#0A101D] text-white font-bold text-sm px-5 py-3 rounded-xl border border-white/15 backdrop-blur-sm transition"
                  >
                    المزيد من التفاصيل ℹ
                  </button>
                </div>
              </div>

              {/* أسهم التنقل اليدوي بين الأعمال المميزة (Carousel فعلي) */}
              {featuredItems.length > 1 && (
                <>
                  <button
                    tabIndex={0}
                    onClick={() => goToHero(safeHeroIndex - 1)}
                    className="tv-focusable absolute top-1/2 -translate-y-1/2 right-4 z-10 w-11 h-11 rounded-full bg-black/50 hover:bg-black/70 focus:bg-[#38BDF8] focus:text-[#0A101D] text-white text-xl flex items-center justify-center border border-white/15 backdrop-blur-sm transition"
                    title="العمل السابق"
                  >
                    ‹
                  </button>
                  <button
                    tabIndex={0}
                    onClick={() => goToHero(safeHeroIndex + 1)}
                    className="tv-focusable absolute top-1/2 -translate-y-1/2 left-4 z-10 w-11 h-11 rounded-full bg-black/50 hover:bg-black/70 focus:bg-[#38BDF8] focus:text-[#0A101D] text-white text-xl flex items-center justify-center border border-white/15 backdrop-blur-sm transition"
                    title="العمل التالي"
                  >
                    ›
                  </button>
                </>
              )}

              {/* مؤشر النقاط الفعلي أسفل البنر (كل نقطة تقفز لعملها) */}
              {featuredItems.length > 1 && (
                <div className="absolute bottom-3 left-1/2 -translate-x-1/2 flex items-center gap-1.5 z-10" dir="ltr">
                  {featuredItems.map((it, idx) => (
                    <button
                      key={it.id}
                      tabIndex={0}
                      onClick={() => goToHero(idx)}
                      title={it.title}
                      className={`tv-focusable rounded-full transition-all ${
                        idx === safeHeroIndex ? 'w-6 h-1.5 bg-[#38BDF8]' : 'w-1.5 h-1.5 bg-white/40 hover:bg-white/60'
                      }`}
                    />
                  ))}
                </div>
              )}
            </div>
          )}

          {/* صف متابعة المشاهدة (الأفلام والمسلسلات) */}
          {continueWatching.length > 0 && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <button
                  tabIndex={0}
                  onClick={() => setActiveTab('continue')}
                  className="tv-focusable text-xs text-[#38BDF8] hover:underline font-bold flex items-center gap-1.5 bg-white/5 hover:bg-white/10 px-3 py-1.5 rounded-xl border border-white/10"
                >
                  <span>عرض الكل ({continueWatching.length})</span>
                  <span>←</span>
                </button>
                <h2 className="text-2xl font-black text-white flex items-center gap-2">
                  <span className="text-[#38BDF8]">▶</span>
                  <span>متابعة المشاهدة</span>
                </h2>
              </div>

              <div className="flex gap-4 overflow-x-auto pb-3 pt-1">
                {continueWatching.map((entry) => {
                  const ratio = getProgressRatio(entry.id);
                  const isSeriesItem =
                    !!entry.item?.parent_id ||
                    entry.item?.kind === 'mix' ||
                    entry.item?.kind === 'playlist' ||
                    entry.id.startsWith('xs:') ||
                    (entry.title && (entry.title.includes('حلقة') || entry.title.includes('الحلقة')));
                  const remainingSec = Math.max(0, entry.dur - entry.pos);
                  const remMin = Math.max(1, Math.round(remainingSec / 60));

                  return (
                    <div
                      key={entry.id}
                      tabIndex={0}
                      role="button"
                      onClick={() => {
                        if (entry.item) onSelectItem(entry.item);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && entry.item) {
                          onSelectItem(entry.item);
                        }
                      }}
                      className="tv-focusable flex-shrink-0 w-52 cursor-pointer focus:outline-none flex flex-col group"
                    >
                      <div className="relative overflow-hidden bg-[#1E293B] border-2 border-transparent group-focus:border-[#38BDF8] rounded-2xl aspect-[16/10] shadow-lg group-focus:scale-105 transition">
                        {entry.thumb ? (
                          <img src={entry.thumb} alt={entry.title} className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full flex items-center justify-center text-3xl">
                            {isSeriesItem ? '📺' : '🎬'}
                          </div>
                        )}

                        {/* شارة المسلسل أو الفيلم */}
                        <div className="absolute top-2 right-2 bg-black/80 backdrop-blur-sm px-2 py-0.5 rounded-md text-[10px] font-bold text-white border border-white/10">
                          {isSeriesItem ? '📺 مسلسل' : '🎬 فيلم'}
                        </div>

                        {/* الوقت المتبقي */}
                        <div className="absolute top-2 left-2 bg-black/80 backdrop-blur-sm px-2 py-0.5 rounded-md text-[10px] font-bold text-[#38BDF8] border border-white/10">
                          متبقي {remMin} د
                        </div>

                        {/* أيقونة استئناف المشاهدة عند التركيز */}
                        <div className="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-focus:opacity-100 group-hover:opacity-100 transition">
                          <div className="w-10 h-10 rounded-full bg-[#38BDF8] text-[#0A101D] flex items-center justify-center text-xl font-black shadow-xl">
                            ▶
                          </div>
                        </div>

                        <div className="absolute inset-x-0 bottom-0 h-2 bg-black/80">
                          <div
                            className="h-full bg-[#38BDF8]"
                            style={{ width: `${Math.max(8, Math.round(ratio * 100))}%` }}
                          />
                        </div>
                      </div>
                      <span className="text-xs font-bold text-white mt-2 truncate w-full group-focus:text-[#38BDF8] text-right">
                        {entry.title}
                      </span>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* الصف 1: القنوات المباشرة (Live Channels) بتناسق ممتاز */}
          {channels.length > 0 && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-[#64748B] tracking-wider uppercase">
                  Live Channels
                </span>
                <h2 className="text-2xl font-black text-white">القنوات المباشرة</h2>
              </div>

              <div className="flex gap-4 overflow-x-auto pb-3 pt-1">
                {channels.map((ch) => (
                  <button
                    key={ch.id}
                    tabIndex={0}
                    onClick={() => onSelectChannel(ch)}
                    className="tv-focusable flex-shrink-0 w-48 h-24 rounded-2xl bg-[#141A29] border-2 border-white/5 hover:border-white/20 focus:border-[#38BDF8] focus:bg-[#1E293B] focus:scale-105 p-3 flex flex-col items-center justify-center text-center shadow-lg transition relative overflow-hidden group"
                  >
                    {ch.logo ? (
                      <img src={ch.logo} alt={ch.name} className="h-10 object-contain mb-1" />
                    ) : (
                      <span className="text-2xl mb-1">📡</span>
                    )}
                    <span className="text-xs font-bold text-[#E2E8F0] truncate w-full group-focus:text-[#38BDF8]">
                      {ch.name}
                    </span>
                    {/* شريط الإشارة الملون أسفل الكرت المطابق للصورة */}
                    <div className="absolute inset-x-0 bottom-0 h-1 bg-red-600/70 group-focus:bg-[#38BDF8] transition-colors" />
                  </button>
                ))}
              </div>
            </section>
          )}

          {/* الصف 2: أفلام رائجة (Trending Movies) */}
          {movies.length > 0 && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-[#64748B] tracking-wider uppercase">
                  Trending Movies
                </span>
                <h2 className="text-2xl font-black text-white">أفلام رائجة</h2>
              </div>

              <div className="flex gap-4 overflow-x-auto pb-3 pt-1">
                {movies.map((m) => {
                  const thumb = m.poster_url || m.thumbnail_url;
                  const genreTag = m.genres && m.genres.length > 0 ? m.genres[0] : null;
                  return (
                    <div
                      key={m.id}
                      tabIndex={0}
                      role="button"
                      onClick={() => onSelectItem(m)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') onSelectItem(m);
                      }}
                      className="group flex-shrink-0 w-40 cursor-pointer focus:outline-none flex flex-col relative"
                    >
                      <div className="relative aspect-[2/3] w-full rounded-2xl overflow-hidden bg-[#141A29] border-2 border-transparent group-focus:border-[#38BDF8] group-focus:scale-105 group-focus:shadow-2xl group-focus:shadow-[#38BDF8]/40 transition-all duration-200 shadow-lg">
                        {thumb ? (
                          <img src={thumb} alt={m.title} className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full flex flex-col items-center justify-center p-3 text-center bg-[#1E293B]">
                            <span className="text-4xl mb-2">🎬</span>
                            <span className="text-xs font-bold text-[#94A3B8]">{m.title}</span>
                          </div>
                        )}
                        {genreTag && (
                          <span className="absolute top-2 right-2 bg-[#2563EB]/90 text-white text-[10px] font-black px-2 py-0.5 rounded-md uppercase tracking-wide">
                            {genreTag}
                          </span>
                        )}
                        <div className="absolute inset-x-0 bottom-0 h-14 bg-gradient-to-t from-black/80 to-transparent pointer-events-none" />
                      </div>
                      <span
                        dir="auto"
                        className="block text-sm font-bold text-white mt-2 truncate w-full group-focus:text-[#38BDF8] text-right transition-colors"
                      >
                        {m.title}
                      </span>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* الصف 3: مسلسلات شائعة (Popular Series مع نجوم التقييم الصفراء) */}
          {series.length > 0 && (
            <section className="space-y-3 pb-8">
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-[#64748B] tracking-wider uppercase">
                  Popular Series
                </span>
                <h2 className="text-2xl font-black text-white">مسلسلات شائعة</h2>
              </div>

              <div className="flex gap-4 overflow-x-auto pb-3 pt-1">
                {series.map((s) => {
                  const thumb = s.poster_url || s.thumbnail_url;
                  const genreTag = s.genres && s.genres.length > 0 ? s.genres[0] : null;
                  return (
                    <div
                      key={s.id}
                      tabIndex={0}
                      role="button"
                      onClick={() => onSelectItem(s)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') onSelectItem(s);
                      }}
                      className="group flex-shrink-0 w-40 cursor-pointer focus:outline-none flex flex-col relative"
                    >
                      <div className="relative aspect-[2/3] w-full rounded-2xl overflow-hidden bg-[#141A29] border-2 border-transparent group-focus:border-[#38BDF8] group-focus:scale-105 group-focus:shadow-2xl group-focus:shadow-[#38BDF8]/40 transition-all duration-200 shadow-lg">
                        {thumb ? (
                          <img src={thumb} alt={s.title} className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full flex flex-col items-center justify-center p-3 text-center bg-[#1E293B]">
                            <span className="text-4xl mb-2">📺</span>
                            <span className="text-xs font-bold text-[#94A3B8]">{s.title}</span>
                          </div>
                        )}
                        {genreTag && (
                          <span className="absolute top-2 right-2 bg-[#7C3AED]/90 text-white text-[10px] font-black px-2 py-0.5 rounded-md uppercase tracking-wide">
                            {genreTag}
                          </span>
                        )}
                        <div className="absolute inset-x-0 bottom-0 h-14 bg-gradient-to-t from-black/80 to-transparent pointer-events-none" />
                      </div>

                      <span
                        dir="auto"
                        className="block text-sm font-bold text-white mt-2 truncate w-full group-focus:text-[#38BDF8] text-right transition-colors"
                      >
                        {s.title}
                      </span>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* تبويب المفضلة لو ضغط عليه */}
          {activeTab === 'favorites' && (
            <section className="space-y-4 pt-4">
              <h2 className="text-3xl font-black text-white">قائمة المفضلة</h2>
              {favorites.length === 0 ? (
                <div className="h-64 flex flex-col items-center justify-center text-center text-[#94A3B8]">
                  <span className="text-5xl mb-3">🤍</span>
                  <p className="text-xl font-bold text-white mb-1">لا توجد عناصر مضافة للمفضلة بعد</p>
                  <p className="text-base">يمكنك إضافة المسلسلات والأفلام للمفضلة لمتابعتها هنا</p>
                </div>
              ) : (
                <div className="grid grid-cols-5 gap-5">
                  {favorites.map((fav) => (
                    <div
                      key={fav.id}
                      tabIndex={0}
                      onClick={() => onSelectItem(fav)}
                      className="tv-focusable flex flex-col cursor-pointer group"
                    >
                      <div className="aspect-[2/3] rounded-2xl overflow-hidden bg-[#141A29] border-2 border-transparent group-focus:border-[#38BDF8]">
                        <img
                          src={fav.poster_url || fav.thumbnail_url || ''}
                          alt={fav.title}
                          className="w-full h-full object-cover"
                        />
                      </div>
                      <span className="text-sm font-bold text-white mt-2 truncate text-right">
                        {fav.title}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </section>
          )}
        </main>
      )}
      </div>

      {/* المساعد التعليمي الذكي للأطفال (سنجوب) */}
      <SanjoobModal isOpen={showSanjoob} onClose={() => setShowSanjoob(false)} />
    </div>
  );
};