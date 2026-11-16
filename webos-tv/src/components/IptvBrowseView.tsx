import React, { useState, useEffect } from 'react';
import type { IptvSource, LiveChannel, ContentItem } from '../types';
import {
  getXtreamCategories,
  getXtreamStreams,
  getXtreamLatest,
  streamToContentItem,
  type XtreamCategory,
  type XtreamStream,
} from '../services/xtream';
import { RatingBadge } from './RatingBadge';
import { UpNextCard } from './UpNextCard';
import { AtvSpinner } from './AtvSpinner';
import { getContinueWatchingSeries, getContinueWatchingMovies, type ProgressItem } from '../services/resume';

interface IptvBrowseViewProps {
  sources: IptvSource[];
  type: 'live' | 'vod' | 'series';
  onSelectChannel: (ch: LiveChannel) => void;
  onSelectItem: (item: ContentItem) => void;
}

export const IptvBrowseView: React.FC<IptvBrowseViewProps> = ({
  sources,
  type,
  onSelectChannel,
  onSelectItem,
}) => {
  const [categories, setCategories] = useState<XtreamCategory[]>([]);
  const [selectedCatId, setSelectedCatId] = useState<string | null>(null);
  const [streams, setStreams] = useState<XtreamStream[]>([]);
  const [loadingCats, setLoadingCats] = useState(true);
  const [loadingStreams, setLoadingStreams] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');

  // فلتر "أُضيف حديثاً" (للأفلام والمسلسلات فقط) يحلّ محل التصنيف العادي مؤقتاً
  const [showingRecent, setShowingRecent] = useState(false);
  const [recentStreams, setRecentStreams] = useState<XtreamStream[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(false);

  // "متابعة المشاهدة" تحت "أُضيف حديثاً": ما بدأت مشاهدته من هذا النوع
  const [showingContinue, setShowingContinue] = useState(false);
  const readContinue = () =>
    type === 'series' ? getContinueWatchingSeries() : type === 'vod' ? getContinueWatchingMovies() : [];
  const [continueList, setContinueList] = useState<ProgressItem[]>(readContinue);
  useEffect(() => {
    const refresh = () => setContinueList(readContinue());
    refresh();
    window.addEventListener('aman_continue_watching_updated', refresh);
    window.addEventListener('focus', refresh);
    return () => {
      window.removeEventListener('aman_continue_watching_updated', refresh);
      window.removeEventListener('focus', refresh);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type]);

  const primarySource = sources[0];

  // جلب التصنيفات عند فتح الصفحة أو تغيير النوع
  useEffect(() => {
    let active = true;
    if (!primarySource) return;

    setLoadingCats(true);
    setCategories([]);
    setSelectedCatId(null);
    setStreams([]);
    setShowingRecent(false);
    setShowingContinue(false);
    setSearchQuery('');

    getXtreamCategories(primarySource, type).then((cats) => {
      if (!active) return;
      setCategories(cats);
      setLoadingCats(false);
      if (cats.length > 0) {
        setSelectedCatId(cats[0].category_id);
      }
    });

    return () => {
      active = false;
    };
  }, [primarySource, type]);

  // جلب المحتوى عند تغيير التصنيف
  useEffect(() => {
    let active = true;
    if (!primarySource || !selectedCatId) return;

    setLoadingStreams(true);
    setStreams([]);

    getXtreamStreams(primarySource, type, selectedCatId).then((data) => {
      if (!active) return;
      setStreams(data);
      setLoadingStreams(false);
    });

    return () => {
      active = false;
    };
  }, [primarySource, type, selectedCatId]);

  const typeTitle =
    type === 'live' ? 'البث المباشر' : type === 'vod' ? 'الأفلام' : 'المسلسلات';

  const handleShowRecent = () => {
    if (!primarySource || type === 'live') return;
    setShowingContinue(false);
    setShowingRecent(true);
    setLoadingRecent(true);
    getXtreamLatest(sources, type, 40).then((data) => {
      setRecentStreams(data);
      setLoadingRecent(false);
    });
  };

  const baseList = showingRecent ? recentStreams : streams;
  const activeList = searchQuery.trim()
    ? baseList.filter((s) => s.name.toLowerCase().includes(searchQuery.trim().toLowerCase()))
    : baseList;
  const isLoadingList = showingRecent ? loadingRecent : loadingStreams;

  return (
    <div className="w-full h-full flex flex-row bg-transparent text-white overflow-hidden select-none">
      {/* قائمة التصنيفات الجانبية (يمين في RTL) */}
      <aside className="w-80 h-full flex flex-col pr-10 pl-4 py-4 flex-shrink-0">
        <h2 className="text-sm font-bold text-white/45 mb-3 px-3 tracking-wide flex items-center gap-2">
          <span>{type === 'live' ? '📡' : type === 'vod' ? '🎬' : '📺'}</span>
          <span>تصنيفات {typeTitle}</span>
        </h2>

        {loadingCats ? (
          <div className="flex-1 flex items-center justify-center text-base text-[#A1A1A6]">
            جاري تحميل التصنيفات…
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto space-y-2 pr-1">
            {type !== 'live' && (
              <button
                tabIndex={0}
                onClick={handleShowRecent}
                className={`tv-focusable w-full text-right px-5 py-3 rounded-xl font-bold text-[17px] transition-all duration-150 flex items-center gap-2 ${
                  showingRecent
                    ? 'bg-white/15 text-white font-black focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                    : 'text-white/65 hover:bg-white/10 focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                }`}
              >
                <span>🆕</span>
                <span>أُضيف حديثاً</span>
              </button>
            )}

            {type !== 'live' && (
              <button
                tabIndex={0}
                onClick={() => {
                  setShowingRecent(false);
                  setShowingContinue(true);
                }}
                className={`tv-focusable w-full text-right px-5 py-3 rounded-xl font-bold text-[17px] transition-all duration-150 flex items-center gap-2 ${
                  showingContinue
                    ? 'bg-white/15 text-white font-black focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                    : 'text-white/65 hover:bg-white/10 focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                }`}
              >
                <span>▶</span>
                <span className="flex-1">متابعة المشاهدة</span>
                {continueList.length > 0 && <span className="text-xs opacity-60">{continueList.length}</span>}
              </button>
            )}

            {categories.map((cat, idx) => {
              const isSelected = !showingRecent && !showingContinue && selectedCatId === cat.category_id;
              return (
                <button
                  key={cat.category_id}
                  autoFocus={idx === 0}
                  tabIndex={0}
                  onClick={() => {
                    setShowingRecent(false);
                    setShowingContinue(false);
                    setSelectedCatId(cat.category_id);
                  }}
                  onFocus={() => {
                    setShowingRecent(false);
                    setShowingContinue(false);
                    setSelectedCatId(cat.category_id);
                  }}
                  className={`tv-focusable w-full text-right px-5 py-3 rounded-xl font-bold text-[17px] transition-all duration-150 truncate ${
                    isSelected
                      ? 'bg-white/15 text-white font-black focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                      : 'text-white/65 hover:bg-white/10 focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
                  }`}
                >
                  {cat.category_name}
                </button>
              );
            })}

            {categories.length === 0 && !loadingCats && (
              <div className="text-center text-base text-[#A1A1A6] py-8">
                لا توجد تصنيفات متاحة
              </div>
            )}
          </div>
        )}
      </aside>

      {/* شبكة عرض المحتوى (يسار في RTL) */}
      <main className="flex-1 h-full overflow-y-auto py-6 pl-14 pr-4">
        <div className="flex items-center justify-between mb-4 gap-4">
          <h3 className="text-3xl font-black text-white flex items-center gap-3 flex-shrink-0">
            <span>{showingContinue ? 'متابعة المشاهدة' : showingRecent ? 'أُضيف حديثاً' : categories.find((c) => c.category_id === selectedCatId)?.category_name || typeTitle}</span>
            {!showingContinue && !isLoadingList && activeList.length > 0 && (
              <span className="text-base font-bold bg-white/10 text-[#FFFFFF] px-3.5 py-1 rounded-full">
                {activeList.length} متوفر
              </span>
            )}
          </h3>

          {/* شريط البحث الفوري وتبديل شكل العرض */}
          <div className="flex items-center gap-2.5 flex-1 max-w-xl">
            <input
              type="text"
              tabIndex={0}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={`ابحث في ${typeTitle}…`}
              className="tv-focusable flex-1 bg-white/10 border border-transparent focus:bg-white/20 rounded-full px-5 py-2.5 text-sm font-bold text-white placeholder-[#6E6E73] focus:outline-none transition text-right"
            />
            <button
              tabIndex={0}
              onClick={() => setViewMode('grid')}
              title="شبكة"
              className={`tv-focusable px-3 py-2.5 rounded-xl text-sm font-bold transition ${
                viewMode === 'grid' ? 'bg-[#FFFFFF] text-[#0B0B0D]' : 'bg-white/5 text-white hover:bg-white/10'
              }`}
            >
              ▦
            </button>
            <button
              tabIndex={0}
              onClick={() => setViewMode('list')}
              title="قائمة"
              className={`tv-focusable px-3 py-2.5 rounded-xl text-sm font-bold transition ${
                viewMode === 'list' ? 'bg-[#FFFFFF] text-[#0B0B0D]' : 'bg-white/5 text-white hover:bg-white/10'
              }`}
            >
              ☰
            </button>
          </div>
        </div>

        {showingContinue ? (
          continueList.length === 0 ? (
            <div className="h-96 flex flex-col items-center justify-center text-center gap-3">
              <span className="text-5xl">🍿</span>
              <p className="text-2xl font-bold text-white">لا شيء هنا بعد</p>
              <p className="text-base text-[#A1A1A6]">
                {type === 'series' ? 'المسلسلات' : 'الأفلام'} اللي تبدأ مشاهدتها تظهر هنا لتكمل من حيث وقفت
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-3 gap-x-8 gap-y-4 pt-2 pb-12">
              {continueList.map((entry) => (
                <UpNextCard key={entry.id} entry={entry} onSelect={onSelectItem} wide />
              ))}
            </div>
          )
        ) : isLoadingList ? (
          <div className="h-96 flex flex-col items-center justify-center text-[#A1A1A6]">
            <AtvSpinner className="mb-5" />
            <p className="text-lg">جاري جلب المحتوى من سيرفر الاشتراك…</p>
          </div>
        ) : activeList.length === 0 ? (
          <div className="h-96 flex flex-col items-center justify-center text-center text-[#A1A1A6]">
            <span className="text-5xl mb-3">📁</span>
            <p className="text-xl font-bold text-white mb-2">
              {searchQuery.trim() ? 'لا توجد نتائج مطابقة للبحث' : 'لا يوجد محتوى في هذا التصنيف'}
            </p>
            <p className="text-base">اختر تصنيفاً آخر من القائمة الجانبية</p>
          </div>
        ) : viewMode === 'list' ? (
          <div className="space-y-2.5 pb-12">
            {activeList.map((stream) => {
              if (type === 'live') {
                return (
                  <button
                    key={String(stream.stream_id)}
                    tabIndex={0}
                    onClick={() => {
                      const streamId = String(stream.stream_id);
                      const creds = primarySource
                        ? {
                            base: primarySource.url.replace(/\/+$/, ''),
                            user: primarySource.username || '',
                            pass: primarySource.password || '',
                          }
                        : null;
                      const streamUrl = creds
                        ? `${creds.base}/live/${creds.user}/${creds.pass}/${streamId}.m3u8`
                        : '';
                      onSelectChannel({
                        id: `xl:${primarySource.id}:${streamId}`,
                        device_id: primarySource.id,
                        source_id: primarySource.id,
                        name: stream.name,
                        logo: stream.stream_icon || null,
                        stream_url: streamUrl,
                        position: stream.num || 0,
                        enabled: true,
                        profile_ids: [],
                      });
                    }}
                    className="tv-focusable w-full flex items-center gap-4 bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-[#0B0B0D] px-4 py-3 rounded-xl text-right transition"
                  >
                    {stream.stream_icon ? (
                      <img src={stream.stream_icon} alt={stream.name} className="h-10 w-10 object-contain flex-shrink-0" />
                    ) : (
                      <span className="text-2xl flex-shrink-0">📡</span>
                    )}
                    <span className="font-bold text-sm flex-1 truncate">{stream.name}</span>
                  </button>
                );
              }

              const item = streamToContentItem(primarySource, stream, type);
              const thumb = item.poster_url || item.thumbnail_url;
              return (
                <div
                  key={item.id}
                  tabIndex={0}
                  role="button"
                  onClick={() => onSelectItem(item)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') onSelectItem(item);
                  }}
                  className="tv-focusable w-full flex items-center gap-4 bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-[#0B0B0D] px-4 py-3 rounded-xl text-right transition cursor-pointer"
                >
                  {thumb ? (
                    <img src={thumb} alt={item.title} className="h-14 w-10 object-cover rounded-md flex-shrink-0" />
                  ) : (
                    <span className="text-2xl flex-shrink-0">{type === 'series' ? '📺' : '🎬'}</span>
                  )}
                  <span className="font-bold text-sm flex-1 truncate">{item.title}</span>
                  <RatingBadge title={item.title} year={item.year} kind={type === 'series' ? 'series' : 'movie'} />
                </div>
              );
            })}
          </div>
        ) : (
          <div
            className={`grid gap-x-7 gap-y-5 pt-2 pb-12 ${
              type === 'live'
                ? 'grid-cols-3 lg:grid-cols-4'
                : 'grid-cols-4 lg:grid-cols-5'
            }`}
          >
            {activeList.map((stream) => {
              if (type === 'live') {
                const streamId = String(stream.stream_id);
                const creds = primarySource ? {
                  base: primarySource.url.replace(/\/+$/, ''),
                  user: primarySource.username || '',
                  pass: primarySource.password || '',
                } : null;
                const streamUrl = creds
                  ? `${creds.base}/live/${creds.user}/${creds.pass}/${streamId}.m3u8`
                  : '';

                const liveChan: LiveChannel = {
                  id: `xl:${primarySource.id}:${streamId}`,
                  device_id: primarySource.id,
                  source_id: primarySource.id,
                  name: stream.name,
                  logo: stream.stream_icon || null,
                  stream_url: streamUrl,
                  position: stream.num || 0,
                  enabled: true,
                  profile_ids: [],
                };

                return (
                  <button
                    key={streamId}
                    tabIndex={0}
                    onClick={() => onSelectChannel(liveChan)}
                    className="tv-focusable tv-channel-card bg-gradient-to-br from-[#2C2C2E] to-[#1C1C1E] hover:bg-[#2C2C2E] p-4 rounded-2xl flex flex-col items-center justify-center text-center shadow-xl transition group h-36"
                  >
                    {stream.stream_icon ? (
                      <img
                        src={stream.stream_icon}
                        alt={stream.name}
                        className="h-16 max-w-[85%] object-contain mb-2"
                      />
                    ) : (
                      <span className="text-3xl mb-1.5">📡</span>
                    )}
                    <span className="text-sm font-bold truncate w-full text-white/80 group-focus:text-white">
                      {stream.name}
                    </span>
                  </button>
                );
              }

              // أفلام ومسلسلات
              const item = streamToContentItem(primarySource, stream, type);
              const thumb = item.poster_url || item.thumbnail_url;

              return (
                <div
                  key={item.id}
                  tabIndex={0}
                  role="button"
                  onClick={() => onSelectItem(item)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      onSelectItem(item);
                    }
                  }}
                  className="group atv-item flex flex-col relative"
                >
                  {/* البوستر مع إطار التوهج الأزرق عند التحديد فقط */}
                  <div className="atv-card aspect-[2/3] w-full">
                    {thumb ? (
                      <img
                        src={thumb}
                        alt={item.title}
                        className="w-full h-full object-cover"
                        loading="lazy"
                      />
                    ) : (
                      <div className="w-full h-full flex flex-col items-center justify-center p-4 text-center bg-[#1C1C1E]">
                        <span className="text-4xl mb-2">{type === 'series' ? '📺' : '🎬'}</span>
                        <span className="text-sm font-bold text-[#A1A1A6] line-clamp-2">
                          {item.title}
                        </span>
                      </div>
                    )}
                    <RatingBadge
                      title={item.title}
                      year={item.year}
                      kind={type === 'series' ? 'series' : 'movie'}
                      className="absolute top-2 right-2"
                    />
                    {/* شريط تدرج خفيف أسفل البوستر */}
                    <div className="absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/80 to-transparent pointer-events-none" />
                  </div>

                  {/* عنوان العمل أسفل البوستر بدون أي مربعات بيضاء أو حواف مشوهة */}
                  <span
                    dir="auto"
                    className="atv-caption block truncate w-full"
                  >
                    {item.title}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
};
