import React, { useState, useEffect, useRef } from 'react';
import { verifySettingsPin } from '../services/supabase';

interface ExitModalProps {
  hasPin: boolean;
  onExit: () => void;
  onClose: () => void;
}

export const ExitModal: React.FC<ExitModalProps> = ({ hasPin, onExit, onClose }) => {
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const firstButtonRef = useRef<HTMLButtonElement>(null);

  // تركيز تلقائي على أول زر داخل النافذة فور فتحها حتى لا يبقى التركيز
  // على عنصر خلفها في شاشة HomeScreen (ويؤدي لضغطة "موافق" خاطئة)
  useEffect(() => {
    firstButtonRef.current?.focus();
  }, []);

  const handleDigit = async (d: string) => {
    if (loading || pin.length >= 4) return;
    const next = pin + d;
    setPin(next);

    if (next.length === 4) {
      setLoading(true);
      setError(null);
      const valid = await verifySettingsPin(next);
      setLoading(false);
      if (valid) {
        onExit();
      } else {
        setError('الرمز غير صحيح');
        setPin('');
      }
    }
  };

  return (
    <div
      data-tv-modal-root
      className="fixed inset-0 z-50 bg-black/75 flex items-center justify-center p-8 atv-fade-in"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md atv-sheet rounded-[28px] p-9 flex flex-col items-center atv-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="w-16 h-16 rounded-full bg-white/10 flex items-center justify-center text-3xl mb-4">
          🔒
        </div>
        <h2 className="text-2xl font-bold text-white mb-2">إذن الخروج (ولي الأمر)</h2>
        <p className="text-sm text-[#A1A1A6] mb-6 text-center">
          {hasPin ? 'أدخل رمز الحماية المكون من 4 أرقام' : 'هل ترغب بالخروج من التطبيق؟'}
        </p>

        {hasPin ? (
          <>
            <div className="flex gap-3 mb-6" dir="ltr">
              {[0, 1, 2, 3].map((i) => (
                <div
                  key={i}
                  className={`w-12 h-14 rounded-xl flex items-center justify-center text-2xl font-bold border-2 ${
                    error
                      ? 'border-red-500 text-red-500'
                      : pin.length > i
                      ? 'border-[#FFFFFF] bg-[#2C2C2E] text-white'
                      : 'border-white/20 bg-[#1C1C1E] text-[#A1A1A6]'
                  }`}
                >
                  {pin.length > i ? '●' : ''}
                </div>
              ))}
            </div>

            {error && <p className="text-red-400 text-sm font-bold mb-4">{error}</p>}
            {loading && <p className="text-[#FFFFFF] text-sm font-bold mb-4">جاري التحقق…</p>}

            <div className="grid grid-cols-3 gap-3 w-64 mb-6" dir="ltr">
              {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => (
                <button
                  key={n}
                  ref={n === 1 ? firstButtonRef : undefined}
                  tabIndex={0}
                  onClick={() => handleDigit(n.toString())}
                  className="tv-focusable h-12 rounded-xl bg-white/[0.08] hover:bg-white/15 focus:bg-[#FFFFFF] focus:text-black text-xl font-bold transition flex items-center justify-center"
                >
                  {n}
                </button>
              ))}
              <button
                tabIndex={0}
                onClick={() => setPin((p) => p.slice(0, -1))}
                className="tv-focusable h-12 rounded-xl bg-white/[0.08] hover:bg-white/15 focus:bg-red-500 focus:text-white text-sm font-bold transition flex items-center justify-center"
              >
                مسح
              </button>
              <button
                tabIndex={0}
                onClick={() => handleDigit('0')}
                className="tv-focusable h-12 rounded-xl bg-white/[0.08] hover:bg-white/15 focus:bg-[#FFFFFF] focus:text-black text-xl font-bold transition flex items-center justify-center"
              >
                0
              </button>
              <button
                tabIndex={0}
                onClick={onClose}
                className="tv-focusable h-12 rounded-xl bg-white/[0.08] hover:bg-white/15 focus:bg-gray-400 focus:text-black text-sm font-bold transition flex items-center justify-center"
              >
                إلغاء
              </button>
            </div>
          </>
        ) : (
          <div className="flex gap-4 w-full">
            <button
              ref={firstButtonRef}
              tabIndex={0}
              onClick={onExit}
              className="atv-btn-glass atv-btn-danger flex-1 h-14 rounded-xl text-lg font-bold"
            >
              خروج
            </button>
            <button
              tabIndex={0}
              onClick={onClose}
              className="atv-btn-glass flex-1 h-14 rounded-xl text-lg font-bold"
            >
              متابعة المشاهدة
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
