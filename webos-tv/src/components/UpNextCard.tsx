import React from 'react';
import type { ContentItem } from '../types';
import { getProgressRatio, removeFromContinueWatching, type ProgressItem } from '../services/resume';

export const isSeriesEntry = (entry: ProgressItem) =>
  !!entry.item?.parent_id ||
  entry.item?.kind === 'mix' ||
  entry.item?.kind === 'playlist' ||
  entry.id.startsWith('xs:') ||
  (!!entry.title && (entry.title.includes('حلقة') || entry.title.includes('الحلقة')));

/** بطاقة "التالي" العرضية 16:9 مع شريط التقدم */
export const UpNextCard: React.FC<{
  entry: ProgressItem;
  onSelect: (item: ContentItem) => void;
  wide?: boolean;
}> = ({ entry, onSelect, wide }) => {
  const ratio = getProgressRatio(entry.id);
  const remMin = Math.max(1, Math.round(Math.max(0, entry.dur - entry.pos) / 60));
  const series = isSeriesEntry(entry);
  return (
    <div
      tabIndex={0}
      role="button"
      onClick={() => entry.item && onSelect(entry.item)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && entry.item) {
          e.preventDefault();
          onSelect(entry.item);
        }
      }}
      className={`group atv-item ${wide ? 'w-full' : 'w-[22rem] flex-shrink-0'}`}
    >
      <div className="atv-card aspect-video">
        {entry.thumb ? (
          <img src={entry.thumb} alt="" className="w-full h-full object-cover" />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-5xl bg-gradient-to-br from-[#2C2C2E] to-[#1C1C1E]">
            {series ? '📺' : '🎬'}
          </div>
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-black/85 via-black/10 to-transparent" />
        <div className="absolute bottom-3 right-4 left-4 flex items-center gap-3">
          <span className="w-8 h-8 rounded-full bg-white/90 text-black flex items-center justify-center text-sm flex-shrink-0">
            ▶
          </span>
          <div className="flex-1 h-1 rounded-full bg-white/25 overflow-hidden" dir="ltr">
            <div
              className="h-full bg-white rounded-full"
              style={{ width: `${Math.max(4, Math.min(100, Math.round(ratio * 100)))}%` }}
            />
          </div>
          <span className="text-xs font-bold text-white/85 flex-shrink-0">{remMin} د</span>
        </div>
        <button
          tabIndex={-1}
          onClick={(e) => {
            e.stopPropagation();
            removeFromContinueWatching(entry.id);
          }}
          className="absolute top-2.5 left-2.5 w-7 h-7 rounded-full bg-black/50 text-white text-xs opacity-0 group-hover:opacity-100 transition"
          title="إزالة من القائمة"
        >
          ✕
        </button>
      </div>
      <div className="atv-caption">
        <span dir="auto" className="block truncate">{entry.title}</span>
        <span className="block text-xs atv-sub">{series ? 'مسلسل' : 'فيلم'} · متبقي {remMin} دقيقة</span>
      </div>
    </div>
  );
};
