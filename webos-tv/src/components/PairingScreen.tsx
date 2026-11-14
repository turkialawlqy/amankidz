import React, { useEffect, useState } from 'react';
import { BrandMark } from './BrandMark';
import { PARENT_SITE_URL } from '../services/supabase';

interface PairingScreenProps {
  deviceCode: string;
  code: string;
  expiresAtMs: number;
}

export const PairingScreen: React.FC<PairingScreenProps> = ({ deviceCode, code, expiresAtMs }) => {
  const [timeLeft, setTimeLeft] = useState(0);

  useEffect(() => {
    const update = () => {
      const now = Date.now();
      const diff = Math.max(0, Math.floor((expiresAtMs - now) / 1000));
      setTimeLeft(diff);
    };

    update();
    const interval = setInterval(update, 1000);
    return () => clearInterval(interval);
  }, [expiresAtMs]);

  const siteUrl = PARENT_SITE_URL.replace(/^https?:\/\//, '').replace(/\/$/, '');
  const minutes = Math.floor(timeLeft / 60);
  const seconds = timeLeft % 60;
  const progressPercent = Math.min(100, Math.max(0, (timeLeft / 585) * 100));

  return (
    <div
      tabIndex={0}
      className="w-full h-full flex flex-row items-center justify-between px-20 py-16 bg-[#181C2B] text-white select-none overflow-hidden"
    >
      {/* القسم الأيمن: التعليمات والخطوات */}
      <div className="flex-1 max-w-xl flex flex-col justify-center items-start text-right pl-12">
        <BrandMark size={72} className="mb-6" />
        <h1 className="text-4xl font-extrabold text-white mb-6 leading-tight">
          أهلاً بك في أمان كيدز
        </h1>

        <div className="space-y-4 text-xl text-[#A7ADBF]">
          <div>
            <span>١. افتح من جوالك:</span>
            <div className="text-2xl font-bold text-[#2FD3BF] mt-1 font-mono tracking-wide" dir="ltr">
              {siteUrl || 'tv.nafasoud.com'}
            </div>
          </div>

          <div className="pt-2">
            ٢. صفحة الأجهزة ← <strong className="text-white">ربط تلفزيون جديد</strong>
          </div>

          <div className="pt-2">
            ٣. اكتب الكود المعروض هنا
          </div>
        </div>

        <div className="mt-12 pt-6 border-t border-white/10 w-full">
          <div className="text-sm text-[#A7ADBF]">معرّف الجهاز:</div>
          <div className="text-base text-gray-300 font-mono tracking-wider mt-1" dir="ltr">
            {deviceCode}
          </div>
        </div>
      </div>

      {/* القسم الأيسر: كود الربط والعداد الزمني */}
      <div className="w-[580px] bg-[#212639]/80 backdrop-blur-md rounded-3xl p-12 border border-white/10 shadow-2xl flex flex-col items-center">
        <h2 className="text-xl text-[#A7ADBF] mb-8 font-medium">كود الربط</h2>

        {/* مربعات الأرقام (تُعرض دائماً من اليسار لليمين LTR) */}
        <div className="flex gap-4 mb-8" dir="ltr">
          {code.split('').map((char, index) => (
            <div
              key={index}
              className="w-16 h-24 rounded-2xl bg-[#2E3449] border-2 border-[#2FD3BF]/40 flex items-center justify-center text-5xl font-black text-[#2FD3BF] shadow-lg shadow-[#2FD3BF]/10"
            >
              {char}
            </div>
          ))}
        </div>

        {/* المؤقت التنازلي وشريط التقدم */}
        <div className="w-full flex flex-col items-center">
          <div className="text-lg font-bold text-white mb-3">
            {timeLeft > 0 ? (
              <span>
                يتغير الكود بعد{' '}
                <span className="text-[#2FD3BF] font-mono">
                  {minutes}:{seconds.toString().padStart(2, '0')}
                </span>
              </span>
            ) : (
              <span className="text-[#2FD3BF] animate-pulse">جاري تجديد الكود…</span>
            )}
          </div>

          <div className="w-64 h-2 bg-[#2E3449] rounded-full overflow-hidden">
            <div
              className="h-full bg-[#2FD3BF] transition-all duration-1000 ease-linear rounded-full"
              style={{ width: `${progressPercent}%` }}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
