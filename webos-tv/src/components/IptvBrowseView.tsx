import React, { useState, useEffect } from 'react';
import type { IptvSource, LiveChannel, ContentItem } from '../types';
import {
  getXtreamCategories,
  getXtreamStreams,
  streamToContentItem,
  type XtreamCategory,
  type XtreamStream,
} from '../services/xtream';

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

  const primarySource = sources[0];

  // جلب التصنيفات عند فتح الصفحة أو تغيير النوع
  useEffect(() => {
    let active = true;
    if (!primarySource) return;

    setLoadingCats(true);
    setCategories([]);
    setSelectedCatId(null);
    setStreams([]);

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

  return (
    <div className="w-full h-full flex flex-row bg-[#0E131F] text-white overflow-hidden select-none">
      {/* قائمة التصنيفات الجانبية (يمين في RTL) */}
      <aside className="w-80 h-full bg-[#131B2E] border-l border-white/10 flex flex-col p-5 flex-shrink-0">
        <h2 className="text-2xl font-black text-[#2FD3BF] mb-4 px-2 flex items-center gap-2">
          <span>{type === 'live' ? '📡' : type === 'vod' ? '🎬' : '📺'}</span>
          <span>تصنيفات {typeTitle}</span>
        </h2>

        {loadingCats ? (
          <div className="flex-1 flex items-center justify-center text-base text-[#A7ADBF]">
            جاري تحميل التصنيفات…
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto space-y-2 pr-1">
            {categories.map((cat, idx) => {
              const isSelected = selectedCatId === cat.category_id;
              return (
                <button
                  key={cat.category_id}
                  autoFocus={idx === 0}
                  tabIndex={0}
                  onClick={() => setSelectedCatId(cat.category_id)}
                  onFocus={() => setSelectedCatId(cat.category_id)}
                  className={`tv-focusable w-full text-right px-5 py-3.5 rounded-2xl font-bold text-base transition-all duration-150 truncate ${
                    isSelected
                      ? 'bg-[#2FD3BF] text-[#0E1A24] shadow-lg shadow-[#2FD3BF]/25 font-black scale-102'
                      : 'text-[#CBD5E1] hover:bg-white/10 focus:bg-[#2FD3BF] focus:text-[#0E1A24]'
                  }`}
                >
                  {cat.category_name}
                </button>
              );
            })}

            {categories.length === 0 && !loadingCats && (
              <div className="text-center text-base text-[#A7ADBF] py-8">
                لا توجد تصنيفات متاحة
              </div>
            )}
          </div>
        )}
      </aside>

      {/* شبكة عرض المحتوى (يسار في RTL) */}
      <main className="flex-1 h-full overflow-y-auto p-8">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-3xl font-black text-white flex items-center gap-3">
            <span>
              {categories.find((c) => c.category_id === selectedCatId)?.category_name ||
                typeTitle}
            </span>
            {!loadingStreams && streams.length > 0 && (
              <span className="text-base font-bold bg-white/10 text-[#2FD3BF] px-3.5 py-1 rounded-full">
                {streams.length} متوفر
              </span>
            )}
          </h3>
        </div>

        {loadingStreams ? (
          <div className="h-96 flex flex-col items-center justify-center text-[#A7ADBF]">
            <div className="w-12 h-12 border-4 border-[#2FD3BF] border-t-transparent rounded-full animate-spin mb-4" />
            <p className="text-lg">جاري جلب المحتوى من سيرفر الاشتراك…</p>
          </div>
        ) : streams.length === 0 ? (
          <div className="h-96 flex flex-col items-center justify-center text-center text-[#A7ADBF]">
            <span className="text-5xl mb-3">📁</span>
            <p className="text-xl font-bold text-white mb-2">لا يوجد محتوى في هذا التصنيف</p>
            <p className="text-base">اختر تصنيفاً آخر من القائمة الجانبية</p>
          </div>
        ) : (
          <div
            className={`grid gap-5 pb-12 ${
              type === 'live'
                ? 'grid-cols-3 lg:grid-cols-4'
                : 'grid-cols-4 lg:grid-cols-5'
            }`}
          >
            {streams.map((stream) => {
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
                    className="tv-focusable bg-[#1A2234] hover:bg-[#253048] focus:bg-[#2FD3BF] focus:text-[#0E1A24] p-4 rounded-2xl flex flex-col items-center justify-center text-center border-2 border-white/5 focus:border-white shadow-xl transition group h-36"
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
                    <span className="text-sm font-bold truncate w-full group-focus:text-[#0E1A24]">
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
                  className="group flex flex-col cursor-pointer focus:outline-none relative"
                >
                  {/* البوستر مع إطار التوهج الأزرق عند التحديد فقط */}
                  <div className="relative aspect-[2/3] w-full rounded-2xl overflow-hidden bg-[#141A29] border-2 border-transparent group-focus:border-[#38BDF8] group-focus:scale-105 group-focus:shadow-2xl group-focus:shadow-[#38BDF8]/40 transition-all duration-200 shadow-md">
                    {thumb ? (
                      <img
                        src={thumb}
                        alt={item.title}
                        className="w-full h-full object-cover"
                        loading="lazy"
                      />
                    ) : (
                      <div className="w-full h-full flex flex-col items-center justify-center p-4 text-center bg-[#1E293B]">
                        <span className="text-4xl mb-2">{type === 'series' ? '📺' : '🎬'}</span>
                        <span className="text-sm font-bold text-[#A7ADBF] line-clamp-2">
                          {item.title}
                        </span>
                      </div>
                    )}
                    {/* شريط تدرج خفيف أسفل البوستر */}
                    <div className="absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/80 to-transparent pointer-events-none" />
                  </div>

                  {/* عنوان العمل أسفل البوستر بدون أي مربعات بيضاء أو حواف مشوهة */}
                  <span
                    dir="auto"
                    className="block text-base font-bold text-white mt-2.5 truncate w-full group-focus:text-[#38BDF8] transition-colors text-right"
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
