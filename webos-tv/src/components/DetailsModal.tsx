import React, { useEffect, useRef, useState, useMemo } from 'react';
import type { ContentItem } from '../types';
import { getStartPosition } from '../services/resume';

interface DetailsModalProps {
  item: ContentItem;
  episodes?: ContentItem[];
  onPlay: (item: ContentItem, playlist?: ContentItem[]) => void;
  onClose: () => void;
}

function cleanDisplayTitle(title: string, pos?: number): string {
  if (!title) return pos ? `الحلقة ${pos}` : '';
  let t = title.trim();
  // إزالة التكرار مثل "1. حلقة 1 - Episode 1"
  t = t.replace(new RegExp('^\\d+[\\.\\-]\\s*'), '').trim();
  if (!t.startsWith('الحلقة') && !t.startsWith('حلقة') && pos) {
    return `الحلقة ${pos}: ${t}`;
  }
  return t;
}

export const DetailsModal: React.FC<DetailsModalProps> = ({
  item,
  episodes = [],
  onPlay,
  onClose,
}) => {
  const playButtonRef = useRef<HTMLButtonElement>(null);
  const backButtonRef = useRef<HTMLButtonElement>(null);
  const isSeries = item.kind === 'mix' || episodes.length > 0;

  // استخراج قائمة المواسم المتاحة
  const seasons = useMemo(() => {
    const s = Array.from(new Set(episodes.map((e) => e.season || 1))).sort((a, b) => a - b);
    return s.length > 0 ? s : [1];
  }, [episodes]);

  // البحث عن حلقة للاستئناف
  const resumeEpisode = useMemo(() => {
    if (!isSeries || episodes.length === 0) return null;
    for (const ep of episodes) {
      const pos = getStartPosition(ep.id);
      if (pos > 5) return ep;
    }
    return episodes[0];
  }, [isSeries, episodes]);

  // الموسم النشط
  const [selectedSeason, setSelectedSeason] = useState<number>(
    resumeEpisode?.season || seasons[0] || 1
  );

  // حلقات الموسم المختار
  const seasonEpisodes = useMemo(() => {
    if (seasons.length > 1) {
      return episodes.filter((e) => (e.season || 1) === selectedSeason);
    }
    return episodes;
  }, [episodes, seasons, selectedSeason]);

  // تقسيم الحلقات إلى دفعات من 50 حلقة للمسلسلات الطويلة
  const BATCH_SIZE = 50;
  const totalBatches = Math.ceil(seasonEpisodes.length / BATCH_SIZE);

  // الدفعة الابتدائية
  const initialBatch = useMemo(() => {
    if (!resumeEpisode) return 0;
    const matchIdx = seasonEpisodes.findIndex((e) => e.id === resumeEpisode.id);
    return matchIdx >= 0 ? Math.floor(matchIdx / BATCH_SIZE) : 0;
  }, [seasonEpisodes, resumeEpisode]);

  const [selectedBatch, setSelectedBatch] = useState<number>(initialBatch);
  const [lastPlayedId, setLastPlayedId] = useState<string | null>(resumeEpisode?.id || null);

  useEffect(() => {
    setSelectedBatch(0);
  }, [selectedSeason]);

  // الحلقات المعروضة في الدفعة الحالية
  const displayedEpisodes = useMemo(() => {
    if (seasonEpisodes.length <= BATCH_SIZE) {
      return seasonEpisodes;
    }
    const start = selectedBatch * BATCH_SIZE;
    return seasonEpisodes.slice(start, start + BATCH_SIZE);
  }, [seasonEpisodes, selectedBatch]);

  // التركيز التلقائي على الحلقة النشطة أو زر المشاهدة
  useEffect(() => {
    const timer = setTimeout(() => {
      if (lastPlayedId) {
        const el = document.getElementById(`ep-btn-${lastPlayedId}`);
        if (el) {
          el.focus();
          return;
        }
      }
      playButtonRef.current?.focus();
    }, 100);
    return () => clearTimeout(timer);
  }, [lastPlayedId, selectedSeason, selectedBatch]);

  const handlePlayEpisode = (ep: ContentItem) => {
    setLastPlayedId(ep.id);
    onPlay(ep, episodes);
  };

  const handleMainPlay = () => {
    if (isSeries && episodes.length > 0) {
      const target = resumeEpisode || episodes[0];
      setLastPlayedId(target.id);
      onPlay(target, episodes);
    } else {
      onPlay(item, [item]);
    }
  };

  const playButtonLabel = useMemo(() => {
    if (isSeries && episodes.length > 0) {
      if (resumeEpisode && getStartPosition(resumeEpisode.id) > 5) {
        return `متابعة ${cleanDisplayTitle(resumeEpisode.title, resumeEpisode.position)}`;
      }
      return 'بدء الحلقة 1';
    }
    return 'مشاهدة الآن';
  }, [isSeries, episodes, resumeEpisode]);

  const posterImg = item.poster_url || item.thumbnail_url;
  const backdropImg = item.backdrop_url || item.thumbnail_url || posterImg;

  return (
    <div data-tv-modal-root className="fixed inset-0 z-40 bg-[#0E131F] text-white flex flex-col w-screen h-screen overflow-hidden select-none">
      {/* البنر العلوي العريض والخلفية الشاملة */}
      <div className="relative w-full h-[46vh] flex-shrink-0 overflow-hidden">
        {backdropImg ? (
          <img
            src={backdropImg}
            alt={item.title}
            className="w-full h-full object-cover object-center opacity-40 scale-105 filter blur-sm"
          />
        ) : (
          <div className="w-full h-full bg-[#131B2E]" />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-[#0E131F] via-[#0E131F]/70 to-black/60" />

        {/* شريط الأدوات العلوي مع زر الرجوع */}
        <div className="absolute top-8 right-12 left-12 flex items-center justify-between z-20">
          <button
            ref={backButtonRef}
            onClick={onClose}
            tabIndex={0}
            className="tv-focusable bg-black/60 hover:bg-[#2FD3BF] hover:text-[#0E1A24] focus:bg-[#2FD3BF] focus:text-[#0E1A24] px-6 py-3 rounded-2xl border border-white/20 font-bold text-lg flex items-center gap-3 transition"
          >
            <span className="text-xl">←</span>
            <span>الرجوع (Back)</span>
          </button>

          <div className="flex items-center gap-3 bg-black/40 px-5 py-2.5 rounded-2xl border border-white/10 text-base font-bold text-[#A7ADBF]">
            <span>📺</span>
            <span>{isSeries ? 'مسلسل' : 'فيلم'}</span>
            <span>•</span>
            <span className="text-white font-black">{item.title}</span>
          </div>
        </div>

        {/* معلومات المحتوى الرئيسية (البوستر + العنوان + الأزرار) */}
        <div className="absolute bottom-6 right-12 left-12 flex items-end gap-8 z-10">
          {/* بوستر العمل */}
          {posterImg && (
            <div className="w-44 aspect-[2/3] rounded-2xl overflow-hidden shadow-2xl border-2 border-white/20 flex-shrink-0 hidden md:block bg-[#1B2438]">
              <img src={posterImg} alt={item.title} className="w-full h-full object-cover" />
            </div>
          )}

          {/* نصوص التفاصيل */}
          <div className="flex-1 max-w-4xl">
            <div className="flex items-center gap-2.5 mb-3 flex-wrap">
              {item.year && (
                <span className="text-xs font-black bg-white/20 px-3 py-1 rounded-lg text-white">
                  {item.year}
                </span>
              )}
              {isSeries && episodes.length > 0 && (
                <span className="text-xs font-black bg-[#2FD3BF] text-[#0E1A24] px-3 py-1 rounded-lg">
                  {episodes.length} حلقة
                </span>
              )}
              {seasons.length > 1 && (
                <span className="text-xs font-bold bg-white/10 text-white px-3 py-1 rounded-lg border border-white/20">
                  {seasons.length} مواسم
                </span>
              )}
              <span className="text-xs font-bold bg-emerald-500/20 text-emerald-300 px-3 py-1 rounded-lg border border-emerald-500/30">
                مناسب للأطفال ✓
              </span>
              {item.genres?.map((g, i) => (
                <span key={i} className="text-xs text-[#A7ADBF] bg-white/5 px-2.5 py-1 rounded-lg">
                  {g}
                </span>
              ))}
            </div>

            <h1 className="text-3xl lg:text-4xl font-black text-white mb-3 drop-shadow-md leading-tight">
              {item.title}
            </h1>

            {item.description && (
              <p className="text-sm lg:text-base text-[#CBD5E1] line-clamp-2 leading-relaxed max-w-2xl drop-shadow">
                {item.description}
              </p>
            )}
          </div>

          {/* زر المشاهدة الرئيسي المتناسق */}
          <button
            ref={playButtonRef}
            tabIndex={0}
            onClick={handleMainPlay}
            className="tv-focusable bg-[#2FD3BF] text-[#0E1A24] font-black text-xl px-10 py-4 rounded-xl shadow-2xl hover:scale-105 focus:ring-4 focus:ring-white flex items-center gap-3.5 flex-shrink-0 transition-transform"
          >
            <svg className="w-7 h-7" fill="currentColor" viewBox="0 0 24 24">
              <path d="M8 5v14l11-7z" />
            </svg>
            <span>{playButtonLabel}</span>
          </button>
        </div>
      </div>

      {/* الجزء السفلي: المواسم ودفعات الحلقات وشبكة الحلقات */}
      <div className="flex-1 overflow-y-auto px-10 py-5 space-y-5">
        {episodes.length > 0 && (
          <div className="space-y-4">
            {/* أشرطة التبويبات (المواسم + الدفعات) */}
            <div className="flex items-center justify-between border-b border-white/10 pb-3 flex-wrap gap-3">
              {/* شريط المواسم */}
              {seasons.length > 1 ? (
                <div className="flex items-center gap-2.5 overflow-x-auto pb-1">
                  <span className="text-base font-bold text-[#A7ADBF] ml-1">الموسم:</span>
                  {seasons.map((sn) => (
                    <button
                      key={sn}
                      tabIndex={0}
                      onClick={() => setSelectedSeason(sn)}
                      className={`tv-focusable px-6 py-2.5 rounded-xl text-base font-bold transition-all ${
                        selectedSeason === sn
                          ? 'bg-[#2FD3BF] text-[#0E1A24] shadow-md font-black'
                          : 'bg-[#1A2234] text-[#A7ADBF] hover:bg-[#26314A] hover:text-white'
                      }`}
                    >
                      الموسم {sn}
                    </button>
                  ))}
                </div>
              ) : (
                <div className="flex items-center gap-2.5">
                  <h2 className="text-2xl font-black text-white">قائمة الحلقات</h2>
                  <span className="text-xs font-bold bg-[#2FD3BF]/20 text-[#2FD3BF] px-3 py-1 rounded-full border border-[#2FD3BF]/30">
                    {seasonEpisodes.length} حلقة متوفرة
                  </span>
                </div>
              )}

              {/* شريط الدفعات للمسلسلات الطويلة */}
              {totalBatches > 1 && (
                <div className="flex items-center gap-2 overflow-x-auto pb-1">
                  <span className="text-sm font-bold text-[#A7ADBF] ml-1">المجموعة:</span>
                  {Array.from({ length: totalBatches }).map((_, bIdx) => {
                    const fromEp = bIdx * BATCH_SIZE + 1;
                    const toEp = Math.min((bIdx + 1) * BATCH_SIZE, seasonEpisodes.length);
                    return (
                      <button
                        key={bIdx}
                        tabIndex={0}
                        onClick={() => setSelectedBatch(bIdx)}
                        className={`tv-focusable px-4 py-2 rounded-xl text-sm font-bold transition-all ${
                          selectedBatch === bIdx
                            ? 'bg-[#2FD3BF] text-[#0E1A24] shadow-sm font-black'
                            : 'bg-[#1A2234] text-[#A7ADBF] hover:bg-[#26314A] hover:text-white'
                        }`}
                      >
                        {fromEp} - {toEp}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {/* شبكة الحلقات المتناسقة */}
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3.5 pb-8">
              {displayedEpisodes.map((ep) => {
                const savedPos = getStartPosition(ep.id);
                const isWatched = savedPos > 5;
                const displayTitle = cleanDisplayTitle(ep.title, ep.position);
                const isCurrent = ep.id === lastPlayedId;

                return (
                  <button
                    key={ep.id}
                    id={`ep-btn-${ep.id}`}
                    tabIndex={0}
                    onClick={() => handlePlayEpisode(ep)}
                    className={`tv-focusable p-3.5 rounded-xl text-right flex items-center justify-between group transition-all shadow-md border ${
                      isCurrent
                        ? 'bg-[#1E293B] border-[#2FD3BF]'
                        : 'bg-[#161E2E] border-white/5 hover:bg-[#1E293B]'
                    } focus:bg-[#2FD3BF] focus:text-[#0E1A24] focus:border-white`}
                  >
                    <div className="flex items-center gap-3 truncate flex-1">
                      <span className="w-10 h-10 rounded-xl bg-black/40 group-focus:bg-black/15 flex items-center justify-center text-base font-black text-[#2FD3BF] group-focus:text-[#0E1A24] flex-shrink-0">
                        {isWatched ? '✓' : '▶'}
                      </span>
                      <span className="font-bold text-base truncate group-focus:text-[#0E1A24]">
                        {displayTitle}
                      </span>
                    </div>

                    {isWatched && (
                      <span className="text-xs font-black bg-[#2FD3BF]/20 text-[#2FD3BF] group-focus:bg-black/10 group-focus:text-[#0E1A24] px-2 py-0.5 rounded-md mr-1.5 flex-shrink-0">
                        متابعة
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* طاقم العمل إذا توفر */}
        {item.cast_list && item.cast_list.length > 0 && (
          <div className="pt-6 border-t border-white/10 pb-8">
            <h3 className="text-2xl font-bold text-white mb-4">طاقم العمل</h3>
            <div className="flex gap-6 overflow-x-auto pb-4">
              {item.cast_list.map((c, i) => (
                <div key={i} className="flex-shrink-0 w-32 flex flex-col items-center text-center">
                  <div className="w-20 h-20 rounded-full overflow-hidden bg-[#1A2234] mb-2 border border-white/10">
                    {c.photo ? (
                      <img src={c.photo} alt={c.name} className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center text-2xl">👤</div>
                    )}
                  </div>
                  <span className="text-sm font-bold text-white truncate w-full">{c.name}</span>
                  {c.character && (
                    <span className="text-xs text-[#A7ADBF] truncate w-full">{c.character}</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};