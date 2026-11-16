import React, { useEffect, useRef } from 'react';
import { getProgressRatio, type ProgressItem } from '../services/resume';
import { isSeriesEntry } from './UpNextCard';

interface ResumePromptProps {
  entry: ProgressItem;
  onResume: () => void;
  onCancel: () => void;
}

/** نافذة "أكمل من حيث توقفت" عند فتح التطبيق — بنمط Apple TV */
export const ResumePrompt: React.FC<ResumePromptProps> = ({ entry, onResume, onCancel }) => {
  const resumeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const t = setTimeout(() => resumeRef.current?.focus(), 80);
    return () => clearTimeout(t);
  }, []);

  const ratio = getProgressRatio(entry.id);
  const remMin = Math.max(1, Math.round(Math.max(0, entry.dur - entry.pos) / 60));
  const img = entry.thumb || entry.item?.backdrop_url || entry.item?.thumbnail_url || entry.item?.poster_url || '';

  return (
    <div data-tv-modal-root className="fixed inset-0 z-[60] flex items-center justify-center atv-fade-in" dir="rtl">
      {/* خلفية مغبّشة من صورة العمل نفسه */}
      <div className="absolute inset-0 bg-black/70" />
      {img && <img src={img} alt="" className="absolute inset-0 w-full h-full object-cover opacity-30 blur-2xl scale-110" />}

      <div className="relative atv-sheet w-[760px] rounded-[28px] overflow-hidden atv-rise">
        <div className="relative aspect-[16/7] w-full bg-[#1C1C1E]">
          {img && <img src={img} alt="" className="w-full h-full object-cover" />}
          <div className="absolute inset-0 bg-gradient-to-t from-[#1C1C1E] via-[#1C1C1E]/30 to-transparent" />
          <div className="absolute inset-x-8 bottom-0" dir="ltr">
            <div className="h-1.5 rounded-full bg-white/25 overflow-hidden">
              <div className="h-full bg-white rounded-full" style={{ width: `${Math.max(4, Math.round(ratio * 100))}%` }} />
            </div>
          </div>
        </div>

        <div className="px-8 pt-6 pb-8 text-right">
          <div className="text-sm font-bold text-white/55 mb-1.5">
            أكمل من حيث توقفت · {isSeriesEntry(entry) ? 'مسلسل' : 'فيلم'} · متبقي {remMin} دقيقة
          </div>
          <h2 dir="auto" className="text-[30px] leading-tight font-extrabold text-white mb-7 line-clamp-2">
            {entry.title}
          </h2>
          <div className="flex items-center gap-3">
            <button
              ref={resumeRef}
              tabIndex={0}
              onClick={onResume}
              className="atv-btn-primary flex-1 h-14 rounded-xl text-lg font-bold flex items-center justify-center gap-3"
            >
              <span className="text-base">▶</span>
              <span>استئناف المتابعة</span>
            </button>
            <button tabIndex={0} onClick={onCancel} className="atv-btn-glass w-44 h-14 rounded-xl text-lg font-bold">
              إلغاء
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
