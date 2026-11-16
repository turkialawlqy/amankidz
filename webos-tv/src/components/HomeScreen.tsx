import React, { useState, useMemo, useEffect } from 'react';
import type { Device, ContentItem, ChildProfile, Category, LiveChannel, IptvSource } from '../types';
import { IptvBrowseView } from './IptvBrowseView';
import { SearchView } from './SearchView';
import { SanjoobModal } from './SanjoobModal';
import { MatchesView } from './MatchesView';
import { RatingBadge } from './RatingBadge';
import {
  getContinueWatchingList,
  getContinueWatchingSeries,
  getContinueWatchingMovies,
  type ProgressItem,
} from '../services/resume';
import { UpNextCard } from './UpNextCard';
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

type Tab = 'home' | 'continue' | 'live' | 'movies' | 'series' | 'favorites' | 'search' | 'matches';

/* ───────────── عناصر مشتركة بنمط Apple TV ───────────── */

const Shelf: React.FC<{ title: string; action?: React.ReactNode; children: React.ReactNode }> = ({
  title,
  action,
  children,
}) => (
  <section className="atv-shelf">
    <div className="flex items-end justify-between px-16 mb-1">
      <h2 className="text-[22px] font-bold text-white/90">{title}</h2>
      {action}
    </div>
    <div className="atv-row">{children}</div>
  </section>
);

/** بوستر طولي 2:3 */
const PosterCard: React.FC<{
  item: ContentItem;
  kind: 'movie' | 'series';
  onSelect: (item: ContentItem) => void;
  fluid?: boolean;
}> = ({ item, kind, onSelect, fluid }) => {
  const thumb = item.poster_url || item.thumbnail_url || item.backdrop_url;
  return (
    <div
      tabIndex={0}
      role="button"
      onClick={() => onSelect(item)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          onSelect(item);
        }
      }}
      className={`group atv-item ${fluid ? 'w-full' : 'w-44 flex-shrink-0'}`}
    >
      <div className="atv-card aspect-[2/3]">
        {thumb ? (
          <img src={thumb} alt="" className="w-full h-full object-cover" loading="lazy" />
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center p-3 text-center bg-gradient-to-br from-[#2C2C2E] to-[#1C1C1E]">
            <span className="text-4xl mb-2">{kind === 'series' ? '📺' : '🎬'}</span>
            <span className="text-xs font-bold text-white/70 line-clamp-3">{item.title}</span>
          </div>
        )}
        <RatingBadge title={item.title} year={item.year} kind={kind} className="absolute top-2 left-2" />
      </div>
      <div className="atv-caption">
        <span dir="auto" className="block truncate">{item.title}</span>
        {item.year ? <span className="block text-xs atv-sub">{item.year}</span> : null}
      </div>
    </div>
  );
};

const ChannelTile: React.FC<{ ch: LiveChannel; onSelect: (c: LiveChannel) => void }> = ({ ch, onSelect }) => (
  <div
    tabIndex={0}
    role="button"
    onClick={() => onSelect(ch)}
    onKeyDown={(e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onSelect(ch);
      }
    }}
    className="group atv-item w-60 flex-shrink-0"
  >
    <div className="atv-card aspect-video bg-gradient-to-br from-[#2C2C2E] to-[#1C1C1E] flex items-center justify-center">
      {ch.logo ? (
        <img src={ch.logo} alt="" className="max-h-[55%] max-w-[70%] object-contain" />
      ) : (
        <span className="text-4xl">📡</span>
      )}
      <span className="absolute top-2.5 right-2.5 flex items-center gap-1 bg-black/45 px-2 py-0.5 rounded-full text-[10px] font-bold text-white">
        <span className="w-1.5 h-1.5 rounded-full bg-[#FF453A]" /> مباشر
      </span>
    </div>
    <div className="atv-caption">
      <span dir="auto" className="block truncate">{ch.name}</span>
    </div>
  </div>
);

const EmptyState: React.FC<{ icon: string; title: string; text: string }> = ({ icon, title, text }) => (
  <div className="h-[60vh] flex flex-col items-center justify-center text-center gap-3">
    <span className="text-6xl opacity-90">{icon}</span>
    <h3 className="text-2xl font-bold text-white">{title}</h3>
    <p className="text-base text-[#A1A1A6] max-w-md">{text}</p>
  </div>
);

/* ───────────── الشاشة الرئيسية ───────────── */

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
  const [activeTab, setActiveTab] = useState<Tab>('home');
  const [continueWatching, setContinueWatching] = useState<ProgressItem[]>(getContinueWatchingList());
  const [continueFilter, setContinueFilter] = useState<'all' | 'series' | 'movies'>('all');
  const [showSanjoob, setShowSanjoob] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const routineStatus = checkCurrentRoutine();

  const [clockTime, setClockTime] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setClockTime(new Date()), 30 * 1000);
    return () => clearInterval(timer);
  }, []);

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

  useEffect(() => setScrolled(false), [activeTab]);

  const continueSeries = useMemo(() => getContinueWatchingSeries(), [continueWatching]);
  const continueMovies = useMemo(() => getContinueWatchingMovies(), [continueWatching]);

  const visibleItems = useMemo(() => {
    if (!items || items.length === 0) return [];
    if (!activeProfile) return items;
    return items.filter(
      (item) => !item.profile_ids || item.profile_ids.length === 0 || item.profile_ids.includes(activeProfile.id)
    );
  }, [items, activeProfile]);

  const movies = useMemo(
    () => visibleItems.filter((it) => it.kind === 'video' || it.id.startsWith('xv:')),
    [visibleItems]
  );
  const series = useMemo(
    () => visibleItems.filter((it) => it.kind === 'mix' || it.kind === 'playlist' || it.id.startsWith('xs:')),
    [visibleItems]
  );
  const favorites = useMemo(() => visibleItems.filter((it) => it.follow), [visibleItems]);

  // Top Shelf: يفضّل الأعمال التي لها صورة عريضة
  const featuredItems = useMemo(() => {
    const pool = [...series, ...movies];
    const wide = pool.filter((it) => it.backdrop_url);
    const picked = (wide.length >= 3 ? wide : pool).slice(0, 6);
    return picked.length > 0 ? picked : visibleItems.slice(0, 6);
  }, [series, movies, visibleItems]);

  const [heroIndex, setHeroIndex] = useState(0);
  const [heroFocused, setHeroFocused] = useState(false);

  useEffect(() => {
    if (featuredItems.length <= 1 || heroFocused) return;
    const timer = setInterval(() => setHeroIndex((i) => (i + 1) % featuredItems.length), 8000);
    return () => clearInterval(timer);
  }, [featuredItems.length, heroIndex, heroFocused]);

  const safeHeroIndex = featuredItems.length > 0 ? heroIndex % featuredItems.length : 0;
  const featuredItem = featuredItems[safeHeroIndex] || null;
  const goToHero = (idx: number) => {
    if (featuredItems.length === 0) return;
    setHeroIndex(((idx % featuredItems.length) + featuredItems.length) % featuredItems.length);
  };
  const heroImage = featuredItem?.backdrop_url || featuredItem?.thumbnail_url || featuredItem?.poster_url || '';
  const heroIsSeries =
    !!featuredItem && (featuredItem.kind === 'mix' || featuredItem.kind === 'playlist' || featuredItem.id.startsWith('xs:'));

  const navTabs: { id: Tab; label: string }[] = [
    { id: 'home', label: 'الرئيسية' },
    { id: 'continue', label: 'التالي' },
    { id: 'live', label: 'مباشر' },
    { id: 'movies', label: 'أفلام' },
    { id: 'series', label: 'مسلسلات' },
    { id: 'favorites', label: 'مكتبتي' },
    { id: 'matches', label: 'مباريات' },
  ];

  const hasIptv = iptv && iptv.length > 0;
  const onScroll = (e: React.UIEvent<HTMLElement>) => {
    const s = e.currentTarget.scrollTop > 40;
    if (s !== scrolled) setScrolled(s);
  };

  /* ── الشريط العلوي العائم ── */
  const header = (
    <header
      className={`absolute top-0 inset-x-0 z-40 h-24 px-12 flex items-center justify-between transition-all duration-300 ${
        scrolled || activeTab !== 'home' ? 'atv-header-solid' : ''
      }`}
    >
      {/* يمين: الملف الشخصي */}
      <div className="flex items-center gap-3 w-64">
        <button
          tabIndex={0}
          onClick={onOpenProfileModal}
          title={activeProfile ? activeProfile.name : 'الحساب'}
          className="atv-icon-btn relative w-12 h-12 text-2xl"
        >
          {activeProfile ? activeProfile.avatar : '🧒'}
          {getUnlockedBadges().length > 0 && (
            <span className="absolute -bottom-0.5 -left-0.5 bg-[#FFD60A] text-black text-[9px] font-black min-w-[16px] h-4 px-1 rounded-full flex items-center justify-center">
              {getUnlockedBadges().length}
            </span>
          )}
        </button>
        {activeProfile && <span className="text-sm font-bold text-white/80 truncate">{activeProfile.name}</span>}
      </div>

      {/* الوسط: كبسولة التبويبات الزجاجية */}
      <nav className="atv-tabbar flex items-center gap-1 px-2 py-1.5 rounded-full">
        {navTabs.map((t) => (
          <button
            key={t.id}
            tabIndex={0}
            onClick={() => setActiveTab(t.id)}
            className={`atv-tab relative px-5 py-2 rounded-full text-[15px] font-bold ${
              activeTab === t.id ? 'atv-tab-active' : ''
            }`}
          >
            {t.label}
            {t.id === 'continue' && continueWatching.length > 0 && (
              <span className="mr-1.5 text-[11px] opacity-70">{continueWatching.length}</span>
            )}
          </button>
        ))}
        <button
          tabIndex={0}
          onClick={() => setActiveTab('search')}
          title="بحث"
          className={`atv-tab w-10 h-10 rounded-full flex items-center justify-center ${
            activeTab === 'search' ? 'atv-tab-active' : ''
          }`}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
        </button>
      </nav>

      {/* يسار: سنجوب + الإعدادات + الساعة */}
      <div className="flex items-center justify-end gap-2 w-64">
        <button tabIndex={0} onClick={() => setShowSanjoob(true)} title="اسأل سنجوب" className="atv-icon-btn w-11 h-11 text-xl">
          🐿️
        </button>
        <button tabIndex={0} onClick={onOpenSettings || onOpenExitModal} title="الإعدادات" className="atv-icon-btn w-11 h-11">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z" />
          </svg>
        </button>
        <span className="text-[17px] font-semibold text-white/85 pr-2 tabular-nums">
          {clockTime.toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit' })}
        </span>
      </div>
    </header>
  );

  /* ── صفحة كاملة لغير الرئيسية ── */
  const page = (children: React.ReactNode) => (
    <main onScroll={onScroll} className="absolute inset-0 overflow-y-auto atv-fade-in pt-28 pb-16">
      {children}
    </main>
  );

  let body: React.ReactNode;

  if (activeTab === 'continue') {
    const list = continueFilter === 'series' ? continueSeries : continueFilter === 'movies' ? continueMovies : continueWatching;
    body = page(
      <div className="px-16">
        <div className="flex items-center justify-between mb-8">
          <h1 className="text-4xl font-extrabold text-white">التالي</h1>
          <div className="atv-segment flex items-center gap-1 p-1 rounded-full">
            {(
              [
                ['all', `الكل ${continueWatching.length}`],
                ['series', `مسلسلات ${continueSeries.length}`],
                ['movies', `أفلام ${continueMovies.length}`],
              ] as const
            ).map(([id, label]) => (
              <button
                key={id}
                tabIndex={0}
                onClick={() => setContinueFilter(id)}
                className={`atv-tab px-5 py-2 rounded-full text-sm font-bold ${continueFilter === id ? 'atv-tab-active' : ''}`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        {list.length === 0 ? (
          <EmptyState icon="🍿" title="لا شيء هنا بعد" text="ابدأ مشاهدة أي حلقة أو فيلم وسيظهر هنا لتكمل من حيث وقفت." />
        ) : (
          <div className="grid grid-cols-4 gap-x-8 gap-y-4">
            {list.map((entry) => (
              <UpNextCard key={entry.id} entry={entry} onSelect={onSelectItem} wide />
            ))}
          </div>
        )}
      </div>
    );
  } else if (activeTab === 'live' || activeTab === 'movies' || activeTab === 'series') {
    body = hasIptv ? (
      <div className="absolute inset-0 pt-24 atv-fade-in">
        <IptvBrowseView
          sources={iptv}
          type={activeTab === 'live' ? 'live' : activeTab === 'movies' ? 'vod' : 'series'}
          onSelectChannel={onSelectChannel}
          onSelectItem={onSelectItem}
        />
      </div>
    ) : (
      page(<EmptyState icon="📡" title="لا يوجد اشتراك مضاف" text="أضف اشتراك IPTV من لوحة التحكم ليظهر المحتوى هنا." />)
    );
  } else if (activeTab === 'search') {
    body = (
      <div className="absolute inset-0 pt-24 atv-fade-in">
        <SearchView sources={iptv} onSelectItem={onSelectItem} onSelectChannel={onSelectChannel} />
      </div>
    );
  } else if (activeTab === 'matches') {
    body = (
      <div className="absolute inset-0 pt-24 atv-fade-in">
        <MatchesView />
      </div>
    );
  } else if (activeTab === 'favorites') {
    body = page(
      <div className="px-16">
        <h1 className="text-4xl font-extrabold text-white mb-8">مكتبتي</h1>
        {favorites.length === 0 ? (
          <EmptyState icon="🤍" title="مكتبتك فارغة" text="المسلسلات والأفلام اللي تتابعها تظهر هنا." />
        ) : (
          <div className="grid grid-cols-7 gap-x-6 gap-y-4">
            {favorites.map((fav) => (
              <PosterCard
                key={fav.id}
                item={fav}
                kind={fav.kind === 'video' ? 'movie' : 'series'}
                onSelect={onSelectItem}
                fluid
              />
            ))}
          </div>
        )}
      </div>
    );
  } else {
    /* ── الرئيسية: Top Shelf + الرفوف ── */
    body = (
      <main onScroll={onScroll} className="absolute inset-0 overflow-y-auto atv-fade-in">
        {featuredItem ? (
          <section className="relative w-full h-[82vh] overflow-hidden">
            {heroImage ? (
              <img
                key={featuredItem.id}
                src={heroImage}
                alt=""
                className="absolute inset-0 w-full h-full object-cover atv-ken-burns"
              />
            ) : (
              <div className="absolute inset-0 bg-gradient-to-br from-[#2C2C2E] to-[#0B0B0D]" />
            )}
            <div className="absolute inset-0 bg-gradient-to-l from-black/80 via-black/30 to-transparent" />
            <div className="absolute inset-0 bg-gradient-to-t from-[#0B0B0D] via-[#0B0B0D]/30 to-transparent" />
            <div className="absolute inset-x-0 top-0 h-40 bg-gradient-to-b from-black/60 to-transparent" />

            <div key={`t-${featuredItem.id}`} className="absolute bottom-24 right-16 max-w-[46%] text-right atv-rise">
              <div className="text-[13px] font-bold tracking-wide text-white/70 mb-3">
                {heroIsSeries ? 'مسلسل' : 'فيلم'}
                {featuredItem.year ? ` · ${featuredItem.year}` : ''}
                {featuredItem.genres && featuredItem.genres.length > 0 ? ` · ${featuredItem.genres.slice(0, 2).join('، ')}` : ''}
                {' · '}
                <span className="text-[#30D158]">آمن للأطفال</span>
              </div>
              <h1 dir="auto" className="text-[56px] leading-[1.1] font-extrabold text-white mb-4 atv-text-shadow line-clamp-2">
                {featuredItem.title}
              </h1>
              {featuredItem.description && (
                <p className="text-lg text-white/80 line-clamp-2 mb-7 leading-relaxed atv-text-shadow">
                  {featuredItem.description}
                </p>
              )}
              <div
                className="flex items-center gap-3"
                onFocus={() => setHeroFocused(true)}
                onBlur={() => setHeroFocused(false)}
              >
                <button
                  tabIndex={0}
                  onClick={() => onSelectItem(featuredItem)}
                  className="atv-btn-primary px-9 h-14 rounded-xl text-lg font-bold flex items-center gap-3"
                >
                  <span className="text-base">▶</span>
                  <span>تشغيل</span>
                </button>
                <button
                  tabIndex={0}
                  onClick={() => onSelectItem(featuredItem)}
                  className="atv-btn-glass px-7 h-14 rounded-xl text-lg font-bold"
                >
                  التفاصيل
                </button>
                {featuredItems.length > 1 && (
                  <button
                    tabIndex={0}
                    onClick={() => goToHero(safeHeroIndex + 1)}
                    title="التالي"
                    className="atv-btn-glass w-14 h-14 rounded-xl text-2xl"
                  >
                    ‹
                  </button>
                )}
              </div>
            </div>

            {featuredItems.length > 1 && (
              <div className="absolute bottom-10 right-16 flex items-center gap-2">
                {featuredItems.map((it, idx) => (
                  <span
                    key={it.id}
                    className={`h-1.5 rounded-full transition-all duration-500 ${
                      idx === safeHeroIndex ? 'w-8 bg-white' : 'w-1.5 bg-white/35'
                    }`}
                  />
                ))}
              </div>
            )}
          </section>
        ) : (
          <div className="h-28" />
        )}

        <div className="relative -mt-6 pb-16 space-y-2">
          {routineStatus.isLocked && (
            <div className="mx-16 mb-4 atv-glass rounded-2xl px-6 py-4 flex items-center gap-4">
              <span className="text-3xl">{routineStatus.icon}</span>
              <div className="flex-1">
                <h3 className="text-lg font-bold text-white">{routineStatus.message}</h3>
                <p className="text-sm text-white/65">
                  {routineStatus.remainingMinutes
                    ? `متبقي على انتهاء الفترة: ${routineStatus.remainingMinutes} دقيقة`
                    : 'خصص وقتاً لإنجاز واجباتك لتكون بطلاً متفوقاً!'}
                </p>
              </div>
            </div>
          )}

          {continueWatching.length > 0 && (
            <Shelf
              title="التالي"
              action={
                continueWatching.length > 4 ? (
                  <button tabIndex={0} onClick={() => setActiveTab('continue')} className="atv-link text-sm font-bold">
                    عرض الكل
                  </button>
                ) : undefined
              }
            >
              {continueWatching.map((entry) => (
                <UpNextCard key={entry.id} entry={entry} onSelect={onSelectItem} />
              ))}
            </Shelf>
          )}

          {favorites.length > 0 && (
            <Shelf title="مكتبتي">
              {favorites.map((f) => (
                <PosterCard key={f.id} item={f} kind={f.kind === 'video' ? 'movie' : 'series'} onSelect={onSelectItem} />
              ))}
            </Shelf>
          )}

          {channels.length > 0 && (
            <Shelf title="البث المباشر">
              {channels.map((ch) => (
                <ChannelTile key={ch.id} ch={ch} onSelect={onSelectChannel} />
              ))}
            </Shelf>
          )}

          {series.length > 0 && (
            <Shelf title="مسلسلات">
              {series.map((s) => (
                <PosterCard key={s.id} item={s} kind="series" onSelect={onSelectItem} />
              ))}
            </Shelf>
          )}

          {movies.length > 0 && (
            <Shelf title="أفلام">
              {movies.map((m) => (
                <PosterCard key={m.id} item={m} kind="movie" onSelect={onSelectItem} />
              ))}
            </Shelf>
          )}

          {visibleItems.length === 0 && channels.length === 0 && (
            <EmptyState icon="✨" title="أهلاً بك" text="أضف محتوى من لوحة التحكم أو تصفح الاشتراك من تبويب الأفلام والمسلسلات." />
          )}
        </div>
      </main>
    );
  }

  return (
    <div className="relative w-screen h-screen atv-bg text-white overflow-hidden select-none font-tajawal">
      {body}
      {header}
      <SanjoobModal isOpen={showSanjoob} onClose={() => setShowSanjoob(false)} />
    </div>
  );
};
