import React, { useEffect, useState } from 'react';
import { AtvSpinner } from './AtvSpinner';
import { getUpcomingMatches, type MatchFixture } from '../services/matches';

export const MatchesView: React.FC = () => {
  const [matches, setMatches] = useState<MatchFixture[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    setLoading(true);
    getUpcomingMatches(20).then((data) => {
      if (active) {
        setMatches(data);
        setLoading(false);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="w-full h-full overflow-y-auto p-8 bg-[#0B0B0D] text-white">
      <h2 className="text-3xl font-black text-white mb-2">المباريات</h2>
      <p className="text-sm text-[#A1A1A6] mb-6">مواعيد المباريات القادمة لأشهر الدوريات</p>

      {loading ? (
        <div className="h-72 flex flex-col items-center justify-center text-[#A1A1A6]">
          <AtvSpinner className="mb-5" />
          <p>جاري جلب المواعيد…</p>
        </div>
      ) : matches.length === 0 ? (
        <div className="h-72 flex flex-col items-center justify-center text-center text-[#A1A1A6]">
          <span className="text-5xl mb-3">⚽</span>
          <p className="text-xl font-bold text-white mb-1">لا توجد مباريات قادمة حالياً</p>
          <p className="text-base">حاول مرة أخرى لاحقاً</p>
        </div>
      ) : (
        <div className="space-y-2.5 max-w-4xl">
          {matches.map((m) => (
            <div
              key={m.id}
              tabIndex={0}
              className="tv-focusable w-full flex items-center gap-4 bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-[#0B0B0D] px-5 py-4 rounded-2xl transition"
            >
              <div className="flex items-center gap-2 flex-1 justify-end">
                <span className="font-bold text-sm">{m.homeTeam}</span>
                {m.homeBadge && <img src={m.homeBadge} alt={m.homeTeam} className="w-8 h-8 object-contain" />}
              </div>
              <div className="flex flex-col items-center px-4 flex-shrink-0">
                <span className="text-xs text-[#A1A1A6]">{m.league}</span>
                <span className="font-mono font-black text-[#FFFFFF]" dir="ltr">
                  {m.kickoffAt
                    ? m.kickoffAt.toLocaleString('ar', {
                        day: '2-digit',
                        month: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })
                    : '--'}
                </span>
              </div>
              <div className="flex items-center gap-2 flex-1">
                {m.awayBadge && <img src={m.awayBadge} alt={m.awayTeam} className="w-8 h-8 object-contain" />}
                <span className="font-bold text-sm">{m.awayTeam}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
