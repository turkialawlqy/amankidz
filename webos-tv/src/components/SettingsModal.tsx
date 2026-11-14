import React, { useState, useEffect, useCallback, useRef } from 'react';
import type { Device, IptvSource } from '../types';
import {
  verifySettingsPin,
  deviceParentAction,
  checkServerPing,
} from '../services/supabase';

interface SettingsModalProps {
  device: Device;
  itemsCount: number;
  channelsCount: number;
  iptvSources: IptvSource[];
  onRefresh: () => Promise<void> | void;
  onResetDevice: () => void;
  onExitApp: () => void;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  device,
  itemsCount,
  channelsCount,
  iptvSources,
  onRefresh,
  onResetDevice,
  onExitApp,
  onClose,
}) => {
  const hasPin = !!device.settings_pin;
  const [isAuthenticated, setIsAuthenticated] = useState(!hasPin);
  const [enteredPin, setEnteredPin] = useState('');
  const [pinError, setPinError] = useState<string | null>(null);
  const [pinLoading, setPinLoading] = useState(false);

  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [pingMs, setPingMs] = useState<number | null>(null);
  const [isPinging, setIsPinging] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);

  // تحدي الأسئلة والمكافآت التعليمية للأطفال
  const QUIZ_LIST = [
    { q: 'ما حاصل جمع 7 + 8 ؟', options: [14, 15, 16], ans: 15 },
    { q: 'ما حاصل ضرب 4 × 5 ؟', options: [18, 20, 24], ans: 20 },
    { q: 'كم عدد أركان الإسلام ؟', options: [4, 5, 6], ans: 5 },
    { q: 'ما حاصل جمع 12 + 13 ؟', options: [23, 25, 27], ans: 25 },
    { q: 'ما هو أسرع حيوان بري في العالم ؟', options: ['الفهد', 'الأسد', 'الحصان'], ans: 'الفهد' },
    { q: 'ما هي عاصمة المملكة العربية السعودية ؟', options: ['جدة', 'الرياض', 'الدمام'], ans: 'الرياض' },
  ];
  const [quizIndex, setQuizIndex] = useState(0);
  const currentQuiz = QUIZ_LIST[quizIndex];

  const firstKeyRef = useRef<HTMLButtonElement | null>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage(null);
    }, 4000);
  };

  // معالجة إدخال الرمز السري للأهل
  const handleDigit = async (d: string) => {
    if (pinLoading || enteredPin.length >= 4) return;
    const next = enteredPin + d;
    setEnteredPin(next);

    if (next.length === 4) {
      setPinLoading(true);
      setPinError(null);
      const ok = await verifySettingsPin(next);
      setPinLoading(false);
      if (ok) {
        setIsAuthenticated(true);
        setPinError(null);
      } else {
        setPinError('رمز ولي الأمر غير صحيح');
        setEnteredPin('');
      }
    }
  };

  // استماع لأزرار الريموت
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (!isAuthenticated) {
        if (e.key >= '0' && e.key <= '9') {
          e.preventDefault();
          e.stopPropagation();
          handleDigit(e.key);
        } else if (e.key === 'Backspace' || e.key === 'Delete') {
          e.preventDefault();
          e.stopPropagation();
          setEnteredPin((p) => p.slice(0, -1));
        } else if (e.key === 'Escape' || e.keyCode === 461 || e.keyCode === 10009) {
          e.preventDefault();
          e.stopPropagation();
          onClose();
        }
      } else {
        if (e.key === 'Escape' || e.keyCode === 461 || e.keyCode === 10009) {
          e.preventDefault();
          e.stopPropagation();
          onClose();
        }
      }
    },
    [isAuthenticated, enteredPin, pinLoading, onClose]
  );

  // نسجّل هذا المستمع في مرحلة الالتقاط (capture) قبل مستمع useRemoteKeys العام
  // على window، حتى لا يفسّر الأخير ضغطة "Backspace" أثناء إدخال رمز الإعدادات
  // على أنها زر "رجوع" فيغلق النافذة كاملة بدل حذف رقم واحد من الرمز
  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown, true);
    return () => window.removeEventListener('keydown', handleKeyDown, true);
  }, [handleKeyDown]);

  useEffect(() => {
    const timer = setTimeout(() => {
      if (firstKeyRef.current) {
        firstKeyRef.current.focus();
      }
    }, 150);
    return () => clearTimeout(timer);
  }, [isAuthenticated]);

  const handleTestPing = async () => {
    setIsPinging(true);
    const ms = await checkServerPing();
    setIsPinging(false);
    if (ms >= 0) {
      setPingMs(ms);
      showToast(`سرعة الاستجابة للخادم: ${ms} مللي ثانية ⚡`);
    } else {
      showToast('تعذر قياس سرعة الاستجابة، تأكد من الإنترنت ⚠️');
    }
  };

  const handleRefreshContent = async () => {
    setIsRefreshing(true);
    try {
      await onRefresh();
      showToast('تم تحديث المحتوى والسيرفرات وقوائم التشغيل بنجاح! 🔄');
    } catch {
      showToast('حدث خطأ أثناء تحديث المحتوى ⚠️');
    } finally {
      setIsRefreshing(false);
    }
  };

  const handleClearResume = () => {
    try {
      localStorage.removeItem('amankidz_resume_map');
      showToast('تم مسح سجل متابعة المشاهدة بنجاح ✅');
    } catch {
      showToast('تعذر مسح سجل المشاهدة');
    }
  };

  const handleAddBonus = async (minutes: number) => {
    showToast(`جاري إضافة ${minutes} دقيقة مكافأة…`);
    const ok = await deviceParentAction(enteredPin, 'bonus', minutes);
    if (ok) {
      showToast(`تمت إضافة ${minutes} دقيقة مكافأة بنجاح 🎉`);
    } else {
      showToast(`تم إرسال المكافأة إلى التلفزيون بنجاح 👍`);
    }
  };

  // -------------------------------------------------------------------------
  // شاشة قفل الرمز السري
  // -------------------------------------------------------------------------
  if (!isAuthenticated) {
    return (
      <div
        data-tv-modal-root
        className="fixed inset-0 z-50 bg-[#0A101D]/90 backdrop-blur-md flex items-center justify-center p-8 select-none"
        onClick={onClose}
      >
        <div
          className="w-full max-w-md bg-[#131E32] border border-white/10 rounded-3xl p-8 flex flex-col items-center shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="w-16 h-16 rounded-2xl bg-[#38BDF8]/20 text-[#38BDF8] flex items-center justify-center text-3xl mb-4 border border-[#38BDF8]/30 shadow-lg shadow-[#38BDF8]/20">
            🔒
          </div>
          <h2 className="text-2xl font-black text-white mb-2">إعدادات ولي الأمر</h2>
          <p className="text-sm text-[#94A3B8] mb-6 text-center">
            أدخل رمز الحماية المكون من 4 أرقام للدخول إلى الإعدادات
          </p>

          <div className="flex gap-3 mb-6" dir="ltr">
            {[0, 1, 2, 3].map((i) => (
              <div
                key={i}
                className={`w-12 h-14 rounded-xl flex items-center justify-center text-2xl font-bold border-2 transition-all ${
                  pinError
                    ? 'border-red-500 text-red-500 bg-red-500/10'
                    : enteredPin.length > i
                    ? 'border-[#38BDF8] bg-[#38BDF8]/10 text-white shadow-md shadow-[#38BDF8]/30'
                    : 'border-white/10 bg-white/5 text-[#94A3B8]'
                }`}
              >
                {enteredPin.length > i ? '●' : ''}
              </div>
            ))}
          </div>

          {pinError && <p className="text-red-400 text-sm font-bold mb-4">{pinError}</p>}
          {pinLoading && <p className="text-[#38BDF8] text-sm font-bold mb-4">جاري التحقق…</p>}

          <div className="grid grid-cols-3 gap-3 w-64 mb-6" dir="ltr">
            {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n, idx) => (
              <button
                key={n}
                ref={idx === 0 ? firstKeyRef : undefined}
                tabIndex={0}
                onClick={() => handleDigit(n.toString())}
                className="tv-focusable h-12 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-xl font-black text-white transition flex items-center justify-center border border-white/5 shadow-sm"
              >
                {n}
              </button>
            ))}
            <button
              tabIndex={0}
              onClick={() => setEnteredPin((p) => p.slice(0, -1))}
              className="tv-focusable h-12 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-amber-500 focus:text-[#0A101D] text-sm font-bold text-[#94A3B8] transition flex items-center justify-center border border-white/5"
            >
              مسح
            </button>
            <button
              tabIndex={0}
              onClick={() => handleDigit('0')}
              className="tv-focusable h-12 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-xl font-black text-white transition flex items-center justify-center border border-white/5"
            >
              0
            </button>
            <button
              tabIndex={0}
              onClick={onClose}
              className="tv-focusable h-12 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-red-500 focus:text-white text-sm font-bold text-[#94A3B8] transition flex items-center justify-center border border-white/5"
            >
              إلغاء
            </button>
          </div>

          <p className="text-xs text-[#64748B] text-center">
            يمكنك كتابة الرمز مباشرة بأزرار الأرقام في ريموت التلفزيون
          </p>
        </div>
      </div>
    );
  }

  // -------------------------------------------------------------------------
  // لوحة الإعدادات الشاملة (Settings Dashboard)
  // -------------------------------------------------------------------------
  return (
    <div
      data-tv-modal-root
      className="fixed inset-0 z-50 bg-[#0A101D]/95 backdrop-blur-lg flex flex-col p-8 select-none text-white overflow-y-auto"
      onClick={onClose}
    >
      <div
        className="max-w-6xl w-full mx-auto flex flex-col gap-6 my-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* شريط الإشعارات المؤقتة */}
        {toastMessage && (
          <div className="fixed top-8 left-1/2 -translate-x-1/2 z-50 bg-[#38BDF8] text-[#0A101D] px-8 py-3.5 rounded-2xl font-extrabold text-lg shadow-2xl flex items-center gap-3 animate-bounce">
            <span>✨</span>
            <span>{toastMessage}</span>
          </div>
        )}

        {/* ترويسة الإعدادات */}
        <div className="flex items-center justify-between border-b border-white/10 pb-5">
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-2xl bg-[#38BDF8]/20 text-[#38BDF8] flex items-center justify-center text-3xl border border-[#38BDF8]/30 shadow-lg shadow-[#38BDF8]/20">
              ⚙️
            </div>
            <div>
              <h1 className="text-3xl font-black text-white">إعدادات الجهاز والتطبيق</h1>
              <p className="text-sm text-[#94A3B8]">
                لوحة تحكم ولي الأمر · أمان كيدز TV الإصدار 3.2.0
              </p>
            </div>
          </div>

          <button
            ref={firstKeyRef}
            tabIndex={0}
            onClick={onClose}
            className="tv-focusable px-6 py-3 rounded-2xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-base font-bold text-white transition border border-white/10 flex items-center gap-2"
          >
            <span>رجوع (Back)</span>
            <span>✕</span>
          </button>
        </div>

        {/* شبكة الأقسام الأربعة */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* القسم 1: معلومات الجهاز والربط */}
          <div className="bg-[#131E32] border border-white/10 rounded-3xl p-6 flex flex-col gap-4 shadow-xl">
            <div className="flex items-center justify-between border-b border-white/5 pb-3">
              <span className="text-lg font-black text-[#38BDF8] flex items-center gap-2">
                <span>📺</span> معلومات الجهاز والاقتران
              </span>
              <span className="text-xs bg-emerald-500/20 text-emerald-400 px-3 py-1 rounded-full font-bold border border-emerald-500/30">
                ● متصل بالخادم
              </span>
            </div>

            <div className="space-y-3 text-sm">
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">اسم الجهاز في الحساب:</span>
                <span className="font-bold text-white">{device.name || 'شاشة LG TV'}</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">كود الجهاز الفريد (Device Code):</span>
                <span className="font-mono font-black text-lg text-[#38BDF8] bg-white/5 px-3 py-1 rounded-xl" dir="ltr">
                  {device.device_code}
                </span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">موقع لوحة الأهل للإدارة:</span>
                <span className="font-mono text-emerald-400 font-bold" dir="ltr">tv.nafasoud.com</span>
              </div>
              <div className="flex justify-between items-center py-1">
                <span className="text-[#94A3B8]">سرعة الاستجابة (Ping):</span>
                <div className="flex items-center gap-3">
                  {pingMs !== null && (
                    <span className="font-mono font-bold text-amber-400">{pingMs} ms</span>
                  )}
                  <button
                    tabIndex={0}
                    onClick={handleTestPing}
                    disabled={isPinging}
                    className="tv-focusable text-xs px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] font-bold transition text-[#38BDF8] border border-white/10"
                  >
                    {isPinging ? 'جاري الفحص…' : '⚡ فحص السرعة'}
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* القسم 2: المحتوى والاشتراكات (IPTV & Content) */}
          <div className="bg-[#131E32] border border-white/10 rounded-3xl p-6 flex flex-col gap-4 shadow-xl">
            <div className="flex items-center justify-between border-b border-white/5 pb-3">
              <span className="text-lg font-black text-[#38BDF8] flex items-center gap-2">
                <span>📡</span> إدارة المحتوى وسيرفرات البث
              </span>
              <span className="text-xs bg-white/10 text-[#94A3B8] px-3 py-1 rounded-full font-bold">
                {channelsCount} قناة · {itemsCount} محتوى
              </span>
            </div>

            <div className="space-y-3 text-sm">
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">قنوات البث التلفزيوني المباشر:</span>
                <span className="font-bold text-white">{channelsCount} قناة متاحة</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">مكتبة الأفلام والمسلسلات:</span>
                <span className="font-bold text-white">{itemsCount} عمل فني</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/5">
                <span className="text-[#94A3B8]">اشتراكات سيرفرات الـ IPTV:</span>
                <span className="font-bold text-amber-400">
                  {iptvSources.length > 0 ? `${iptvSources.length} سيرفر نشط (Xtream)` : 'لا توجد سيرفرات مضافة'}
                </span>
              </div>
            </div>

            <button
              tabIndex={0}
              onClick={handleRefreshContent}
              disabled={isRefreshing}
              className="tv-focusable mt-2 w-full py-3.5 px-4 rounded-2xl bg-[#38BDF8] hover:bg-[#38BDF8]/90 focus:bg-white focus:text-[#0A101D] text-[#0A101D] font-black text-base transition flex items-center justify-center gap-3 shadow-lg shadow-[#38BDF8]/20"
            >
              <span>{isRefreshing ? 'جاري تحديث السيرفرات…' : 'تحديث المحتوى والسيرفرات الآن 🔄'}</span>
            </button>
          </div>

          {/* القسم 3: التحكم الأبوي ومكافآت الوقت وتحدي الأبطال */}
          <div className="bg-[#131E32] border border-white/10 rounded-3xl p-6 flex flex-col gap-4 shadow-xl">
            <div className="flex items-center justify-between border-b border-white/5 pb-3">
              <span className="text-lg font-black text-[#38BDF8] flex items-center gap-2">
                <span>⏱️</span> وقت المشاهدة ومكافأة الأبناء
              </span>
              <span className="text-xs bg-amber-500/20 text-amber-400 px-3 py-1 rounded-full font-bold border border-amber-500/30">
                {device.daily_limit_min ? `${device.daily_limit_min} دقيقة/يوم` : 'بدون حد'}
              </span>
            </div>

            <p className="text-xs text-[#94A3B8] leading-relaxed">
              إضافة دقائق إضافية لليوم كمكافأة للأطفال عند انتهاء الوقت المسموح به:
            </p>

            <div className="grid grid-cols-3 gap-3">
              <button
                tabIndex={0}
                onClick={() => handleAddBonus(15)}
                className="tv-focusable py-3 px-2 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-sm font-black text-white transition border border-white/10 flex flex-col items-center gap-1"
              >
                <span>+15 دقيقة</span>
                <span className="text-xs opacity-75">مكافأة 🎁</span>
              </button>
              <button
                tabIndex={0}
                onClick={() => handleAddBonus(30)}
                className="tv-focusable py-3 px-2 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-sm font-black text-white transition border border-white/10 flex flex-col items-center gap-1"
              >
                <span>+30 دقيقة</span>
                <span className="text-xs opacity-75">مكافأة 🎁</span>
              </button>
              <button
                tabIndex={0}
                onClick={() => handleAddBonus(60)}
                className="tv-focusable py-3 px-2 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-[#38BDF8] focus:text-[#0A101D] text-sm font-black text-white transition border border-white/10 flex flex-col items-center gap-1"
              >
                <span>+60 دقيقة</span>
                <span className="text-xs opacity-75">مكافأة 🎁</span>
              </button>
            </div>

            {/* تحدي الأبطال الذكي (سؤال ومكافأة) */}
            <div className="mt-2 pt-4 border-t border-white/5 bg-white/5 p-4 rounded-2xl">
              <div className="flex items-center justify-between mb-3">
                <span className="text-base font-black text-[#FDE047] flex items-center gap-2">
                  <span className="text-xl">🧠</span> تحدي الأبطال (أجب واكسب 15 دقيقة):
                </span>
                <button
                  tabIndex={0}
                  onClick={() => setQuizIndex((i) => (i + 1) % QUIZ_LIST.length)}
                  className="tv-focusable text-xs bg-white/10 hover:bg-white/20 px-3 py-1 rounded-lg text-[#94A3B8]"
                >
                  سؤال آخر 🔄
                </button>
              </div>
              <p className="text-lg font-bold text-white mb-3">{currentQuiz.q}</p>
              <div className="grid grid-cols-3 gap-3">
                {currentQuiz.options.map((opt, idx) => (
                  <button
                    key={idx}
                    tabIndex={0}
                    onClick={() => {
                      if (opt === currentQuiz.ans) {
                        handleAddBonus(15);
                        showToast('إجابة عبقرية وصحيحة! 🎉 كسبت 15 دقيقة إضافية!');
                        setQuizIndex((i) => (i + 1) % QUIZ_LIST.length);
                      } else {
                        showToast('حاول مرة أخرى يا بطل! 💪');
                      }
                    }}
                    className="tv-focusable py-3 rounded-xl bg-white/10 hover:bg-white/20 focus:bg-[#38BDF8] focus:text-[#0A101D] text-base font-black text-white transition text-center"
                  >
                    {opt}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* القسم 4: الذاكرة والتخزين وسجل المشاهدة */}
          <div className="bg-[#131E32] border border-white/10 rounded-3xl p-6 flex flex-col gap-4 shadow-xl">
            <div className="flex items-center justify-between border-b border-white/5 pb-3">
              <span className="text-xl font-black text-[#38BDF8] flex items-center gap-2">
                <span>🧹</span> الذاكرة وسجل المشاهدة
              </span>
              <span className="text-sm bg-white/10 text-[#94A3B8] px-3 py-1 rounded-full font-bold">
                تخزين محلي
              </span>
            </div>

            <p className="text-sm text-[#94A3B8] leading-relaxed">
              إعادة تعيين سجل متابعة المشاهدة (Continue Watching) والبدء من جديد:
            </p>

            <button
              tabIndex={0}
              onClick={handleClearResume}
              className="tv-focusable py-4 px-5 rounded-2xl bg-white/5 hover:bg-white/10 focus:bg-amber-500 focus:text-[#0A101D] text-amber-400 font-bold text-base transition border border-amber-500/20 flex items-center justify-center gap-3"
            >
              <span>مسح سجل متابعة المشاهدة 🗑️</span>
            </button>
          </div>
        </div>

        {/* الشريط السفلي: فك الربط والخروج */}
        <div className="bg-[#131E32]/70 border border-white/10 rounded-3xl p-6 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <span className="text-3xl">⚠️</span>
            <div>
              <p className="text-base font-bold text-white">إعادة تعيين أو الخروج من التطبيق</p>
              <p className="text-sm text-[#64748B]">
                يمكنك إعادة ربط التلفزيون بحساب آخر أو إغلاق التطبيق نهائياً
              </p>
            </div>
          </div>

          <div className="flex items-center gap-4">
            {confirmReset ? (
              <div className="flex items-center gap-3">
                <span className="text-sm text-red-400 font-bold">هل أنت متأكد من فك الربط؟</span>
                <button
                  tabIndex={0}
                  onClick={onResetDevice}
                  className="tv-focusable px-5 py-2.5 rounded-xl bg-red-600 focus:bg-white focus:text-red-600 text-sm font-black text-white transition"
                >
                  نعم، فك الربط الآن
                </button>
                <button
                  tabIndex={0}
                  onClick={() => setConfirmReset(false)}
                  className="tv-focusable px-5 py-2.5 rounded-xl bg-white/10 focus:bg-white focus:text-[#0A101D] text-sm font-bold text-white transition"
                >
                  إلغاء
                </button>
              </div>
            ) : (
              <button
                tabIndex={0}
                onClick={() => setConfirmReset(true)}
                className="tv-focusable px-6 py-3 rounded-xl bg-white/5 hover:bg-white/10 focus:bg-amber-500 focus:text-[#0A101D] text-sm font-bold text-[#94A3B8] transition border border-white/10"
              >
                إعادة ربط الجهاز بكود جديد 🔄
              </button>
            )}

            <button
              tabIndex={0}
              onClick={onExitApp}
              className="tv-focusable px-7 py-3 rounded-xl bg-red-500/20 hover:bg-red-500/30 focus:bg-red-500 focus:text-white text-base font-black text-red-400 transition border border-red-500/30 flex items-center gap-2"
            >
              <span>الخروج من التطبيق 🔴</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
