import React, { useState, useEffect, useRef } from 'react';
import type { ChildProfile } from '../types';
import { ALL_BADGES, getUnlockedBadges } from '../services/badges';
import { verifyProfilePin } from '../services/supabase';

interface ProfileModalProps {
  profiles: ChildProfile[];
  currentProfileId: string | null;
  onSelectProfile: (profile: ChildProfile | null) => void;
  onClose: () => void;
}

export const ProfileModal: React.FC<ProfileModalProps> = ({
  profiles,
  currentProfileId,
  onSelectProfile,
  onClose,
}) => {
  const [selectedProfile, setSelectedProfile] = useState<ChildProfile | null>(null);
  const [enteredPin, setEnteredPin] = useState('');
  const [pinError, setPinError] = useState(false);
  const [pinLoading, setPinLoading] = useState(false);
  const firstButtonRef = useRef<HTMLButtonElement>(null);

  // تركيز تلقائي على أول عنصر داخل النافذة فور فتحها حتى لا يبقى التركيز
  // على عنصر خلفها في شاشة HomeScreen (ويؤدي لضغطة "موافق" خاطئة)
  useEffect(() => {
    firstButtonRef.current?.focus();
  }, []);

  const handleProfileClick = (p: ChildProfile) => {
    if (p.has_pin) {
      setSelectedProfile(p);
      setEnteredPin('');
      setPinError(false);
    } else {
      onSelectProfile(p);
      onClose();
    }
  };

  // التحقق من الرمز يتم على الخادم (device_check_profile_pin) وليس بمقارنته
  // محلياً في المتصفح، حتى لا يصل رمز الحماية الفعلي لجهاز التلفزيون إطلاقاً
  const handlePinDigit = async (digit: string) => {
    if (pinLoading || enteredPin.length >= 4 || !selectedProfile) return;
    const next = enteredPin + digit;
    setEnteredPin(next);
    if (next.length === 4) {
      setPinLoading(true);
      setPinError(false);
      const valid = await verifyProfilePin(selectedProfile.id, next);
      setPinLoading(false);
      if (valid) {
        onSelectProfile(selectedProfile);
        onClose();
      } else {
        setPinError(true);
        setEnteredPin('');
      }
    }
  };

  return (
    <div
      data-tv-modal-root
      className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-8"
      onClick={onClose}
    >
      <div
        className="w-full max-w-2xl bg-[#0B0B0D] border border-white/10 rounded-3xl p-10 flex flex-col items-center shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {!selectedProfile ? (
          <>
            <h2 className="text-3xl font-extrabold text-white mb-2">من يشاهد الآن؟</h2>
            <p className="text-lg text-[#A1A1A6] mb-10">اختر ملفك الشخصي</p>

            <div className="flex flex-wrap justify-center gap-8">
              {profiles.map((p, idx) => {
                const isSelected = p.id === currentProfileId;
                return (
                  <button
                    key={p.id}
                    ref={idx === 0 ? firstButtonRef : undefined}
                    tabIndex={0}
                    onClick={() => handleProfileClick(p)}
                    className="tv-focusable flex flex-col items-center p-4 rounded-2xl group focus:scale-110 focus:ring-4 focus:ring-[#FFFFFF] transition"
                  >
                    <div
                      className={`w-28 h-28 rounded-2xl flex items-center justify-center text-5xl mb-4 bg-[#1C1C1E] border-2 ${
                        isSelected ? 'border-[#FFFFFF]' : 'border-white/10'
                      } group-focus:border-transparent shadow-lg`}
                    >
                      {p.avatar || '🧒'}
                    </div>
                    <span className="text-xl font-bold text-white group-focus:text-[#FFFFFF]">
                      {p.name}
                    </span>
                    {p.has_pin && <span className="text-xs text-[#A1A1A6] mt-1">🔒 مقفل برمز</span>}
                  </button>
                );
              })}

              {/* خيار عرض كل المحتوى */}
              <button
                ref={profiles.length === 0 ? firstButtonRef : undefined}
                tabIndex={0}
                onClick={() => {
                  onSelectProfile(null);
                  onClose();
                }}
                className="tv-focusable flex flex-col items-center p-4 rounded-2xl group focus:scale-110 focus:ring-4 focus:ring-white transition"
              >
                <div className="w-28 h-28 rounded-2xl flex items-center justify-center text-4xl mb-4 bg-[#2C2C2E] border-2 border-white/10 group-focus:border-white shadow-lg text-white">
                  🏠
                </div>
                <span className="text-xl font-bold text-white group-focus:text-white">
                  الكل
                </span>
              </button>
            </div>

            {/* قسم أوسمة وإنجازات الأبطال */}
            <div className="w-full mt-8 pt-6 border-t border-white/10">
              <h3 className="text-xl font-black text-[#FDE047] mb-4 flex items-center justify-center gap-2">
                <span>🏆</span> أوسمة وإنجازات الأبطال المكتسبة:
              </h3>
              <div className="flex flex-wrap justify-center gap-4">
                {ALL_BADGES.map((b) => {
                  const unlocked = getUnlockedBadges().includes(b.id);
                  return (
                    <div
                      key={b.id}
                      className={`flex items-center gap-3 px-4 py-2.5 rounded-2xl border transition ${
                        unlocked
                          ? 'bg-white/10 border-white/20 text-white shadow-md'
                          : 'bg-white/5 border-white/5 text-[#6E6E73] opacity-50'
                      }`}
                    >
                      <span className="text-2xl">{b.icon}</span>
                      <div className="text-right">
                        <span className="text-sm font-black block leading-tight">{b.title}</span>
                        <span className="text-xs opacity-80 block">{unlocked ? 'مكتسب ✓' : 'قفل 🔒'}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </>
        ) : (
          /* شاشة إدخال رمز الأمان PIN للملف الشخصي */
          <div className="flex flex-col items-center w-full">
            <div className="w-20 h-20 rounded-2xl bg-[#1C1C1E] flex items-center justify-center text-4xl mb-4 border border-white/10">
              {selectedProfile.avatar}
            </div>
            <h2 className="text-2xl font-bold text-white mb-2">أدخل رمز {selectedProfile.name}</h2>
            <p className="text-sm text-[#A1A1A6] mb-6">مكون من 4 أرقام</p>

            <div className="flex gap-4 mb-6" dir="ltr">
              {[0, 1, 2, 3].map((idx) => (
                <div
                  key={idx}
                  className={`w-12 h-14 rounded-xl flex items-center justify-center text-2xl font-bold border-2 ${
                    pinError
                      ? 'border-red-500 text-red-500'
                      : enteredPin.length > idx
                      ? 'border-[#FFFFFF] bg-[#2C2C2E] text-white'
                      : 'border-white/20 bg-[#1C1C1E] text-[#A1A1A6]'
                  }`}
                >
                  {enteredPin.length > idx ? '●' : ''}
                </div>
              ))}
            </div>

            {pinError && <p className="text-red-400 font-bold mb-4">الرمز غير صحيح، حاول مرة أخرى</p>}
            {pinLoading && <p className="text-[#FFFFFF] text-sm font-bold mb-4">جاري التحقق…</p>}

            {/* لوحة الأرقام للتحكم بريموت التلفزيون */}
            <div className="grid grid-cols-3 gap-3 w-64 mb-6" dir="ltr">
              {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((num) => (
                <button
                  key={num}
                  tabIndex={0}
                  onClick={() => handlePinDigit(num.toString())}
                  className="tv-focusable h-14 rounded-xl bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-black text-2xl font-bold transition flex items-center justify-center"
                >
                  {num}
                </button>
              ))}
              <button
                tabIndex={0}
                onClick={() => setEnteredPin((prev) => prev.slice(0, -1))}
                className="tv-focusable h-14 rounded-xl bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-red-500 focus:text-white text-lg font-bold transition flex items-center justify-center"
              >
                مسح
              </button>
              <button
                tabIndex={0}
                onClick={() => handlePinDigit('0')}
                className="tv-focusable h-14 rounded-xl bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-black text-2xl font-bold transition flex items-center justify-center"
              >
                0
              </button>
              <button
                tabIndex={0}
                onClick={() => setSelectedProfile(null)}
                className="tv-focusable h-14 rounded-xl bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-gray-400 focus:text-black text-lg font-bold transition flex items-center justify-center"
              >
                إلغاء
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
