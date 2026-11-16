import React, { useState, useEffect, useRef } from 'react';
import { soundEffects } from '../services/sound';
import { unlockBadge } from '../services/badges';

interface SanjoobModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface QAItem {
  id: string;
  question: string;
  icon: string;
  category: string;
  shortAnswer: string;
  detailedAnswer: string;
}

const SANJOOB_KNOWLEDGE: QAItem[] = [
  {
    id: 'sky_blue',
    question: 'ليش السماء لونها أزرق؟',
    icon: '🌤️',
    category: 'العلوم والفضاء',
    shortAnswer: 'لأن ضوء الشمس يتشتت في الهواء ويفضل اللون الأزرق الجميل!',
    detailedAnswer: 'ضوء الشمس الأبيض يحتوي على كل ألوان قوس قزح! وعندما يدخل في الغلاف الجوي للأرض، يصطدم بجزيئات الهواء، فيتشتت اللون الأزرق أكثر من بقية الألوان في كل مكان، فتراه عيوننا زرقاء مبهجة!',
  },
  {
    id: 'fish_breathe',
    question: 'كيف تتنفس الأسماك تحت الماء؟',
    icon: '🐠',
    category: 'عالم الحيوان',
    shortAnswer: 'باستخدام الخياشيم السحرية التي تمتص الأكسجين من الماء!',
    detailedAnswer: 'الأسماك لا تملك رئتين مثلنا، بل لديها خياشيم على جانبي رأسها تعمل كالفلتر الذكي، تأخذ الماء من الفم وتمرره على الخياشيم لتمتص الأكسجين وتتنفس بسهولة تحت أعماق البحار!',
  },
  {
    id: 'planets_space',
    question: 'كم كوكب يدور حول الشمس؟',
    icon: '🪐',
    category: 'العلوم والفضاء',
    shortAnswer: '8 كواكب رائعة، وأجملها كوكب الأرض الذي نعيش عليه!',
    detailedAnswer: 'مجموعتنا الشمسية تضم 8 كواكب أبطال: عطارد، الزهرة، كوكبنا الأرض، المريخ الأحمر، المشتري العملاق، زحل صاحب الحلقات الجميلة، أورانوس، ونبتون الأزرق البعيد!',
  },
  {
    id: 'sleep_night',
    question: 'ليش لازم ننام بالليل؟',
    icon: '😴',
    category: 'الصحة والنمو',
    shortAnswer: 'حتى يكبر دماغك وعضلاتك وتستيقظ بطلاً قوياً وذكياً!',
    detailedAnswer: 'أثناء النوم ليلاً، يفرز جسمك هرمون النمو، ويقوم عقلك بتنظيم وحفظ كل المعلومات والمهارات التي تعلمتها خلال يومك، وتشحن طاقتك لمغامرات يوم غد!',
  },
  {
    id: 'rain_clouds',
    question: 'من أين تأتي الغيوم والمطر؟',
    icon: '🌧️',
    category: 'الطقس والطبيعة',
    shortAnswer: 'من بخار الماء الذي يصعد للسماء ويتحول لقطرات مطر منعشة!',
    detailedAnswer: 'تدفئ أشعة الشمس مياه البحار والأنهار، فيتصاعد بخار الماء الخفيف إلى السماء عالياً، وعندما يبرد يتجمع على شكل غيوم قطنية بيضاء، وعندما تصبح ثقيلة تسقط كأمطار تروي الزهور والأشجار!',
  },
  {
    id: 'honey_bees',
    question: 'كيف تصنع النحلة العسل اللذيذ؟',
    icon: '🍯',
    category: 'عالم الحيوان',
    shortAnswer: 'تجمع رحيق الأزهار وتتعاون مع رفيقاتها داخل الخلية!',
    detailedAnswer: 'تطير النحلة النشيطة بين آلاف الزهور وتمتص الرحيق الحلو، ثم تحمله إلى الخلية وتتعاون مع باقي النحل لتحويله إلى عسل شهي ومفيد يشفي ويقوي أجسادنا!',
  },
  {
    id: 'water_health',
    question: 'ليش لازم نشرب موية كثير؟',
    icon: '💧',
    category: 'الصحة والنمو',
    shortAnswer: 'لأن أجسامنا تتكون في أغلبها من الماء لنبقى نشيطين!',
    detailedAnswer: 'الماء هو سر الحياة والطاقة! فهو يحافظ على برودة جسمك، وينقل الغذاء لخلاياك، ويجعل عقلك يفكر بسرعة خارقة، ويحمي بشرتك وعيونك!',
  },
];

export const SanjoobModal: React.FC<SanjoobModalProps> = ({ isOpen, onClose }) => {
  const [selectedQA, setSelectedQA] = useState<QAItem>(SANJOOB_KNOWLEDGE[0]);
  const [isSpeaking, setIsSpeaking] = useState<boolean>(false);
  const firstButtonRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (isOpen) {
      soundEffects.playSanjoobGreeting();
      unlockBadge('sanjoob_friend');
      setTimeout(() => {
        firstButtonRef.current?.focus();
      }, 150);
    } else {
      if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
        window.speechSynthesis.cancel();
      }
      setIsSpeaking(false);
    }
  }, [isOpen]);

  const speakText = (text: string) => {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
      return;
    }

    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'ar-SA';
      utterance.rate = 0.95; // سرعة هادئة واضحة ومحبوبة للأطفال
      utterance.pitch = 1.15; // نبرة مرحة ودودة

      utterance.onstart = () => setIsSpeaking(true);
      utterance.onend = () => setIsSpeaking(false);
      utterance.onerror = () => setIsSpeaking(false);

      window.speechSynthesis.speak(utterance);
    } catch {
      setIsSpeaking(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div data-tv-modal-root className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-8 select-none">
      <div className="w-full max-w-6xl h-[88vh] bg-[#0E1626] border-2 border-[#FFFFFF]/40 rounded-3xl p-8 flex flex-col shadow-2xl text-white overflow-hidden relative">
        {/* رأس النافذة */}
        <div className="flex items-center justify-between border-b border-white/10 pb-5 mb-6">
          <div className="flex items-center gap-4">
            <span className="text-5xl animate-bounce">🐿️</span>
            <div>
              <h2 className="text-3xl font-black text-[#FFFFFF] flex items-center gap-3">
                <span>المساعد الذكي "سنجوب"</span>
                <span className="text-xs bg-amber-400/20 text-amber-300 px-3 py-1 rounded-full border border-amber-400/30">
                  صديق الأطفال المعرفي
                </span>
              </h2>
              <p className="text-base text-[#A1A1A6] mt-1">
                اختر أي سؤال يدور في ذهنك وسيشرحه لك سنجوب بالصوت والصورة!
              </p>
            </div>
          </div>

          <button
            tabIndex={0}
            onClick={onClose}
            className="tv-focusable bg-white/10 hover:bg-white/20 px-6 py-3 rounded-2xl text-lg font-bold border border-white/10 transition"
          >
            إغلاق (Back) ✕
          </button>
        </div>

        {/* جسم النافذة: عمود الأسئلة يميناً وعمود الإجابة الشارحة يساراً */}
        <div className="flex-1 grid grid-cols-12 gap-8 overflow-hidden">
          {/* قائمة الأسئلة (5 أعمدة) */}
          <div className="col-span-5 overflow-y-auto space-y-3 pr-2">
            <span className="text-sm font-bold text-[#6E6E73] block mb-2 uppercase tracking-wider">
              أسئلة الأبطال الأذكياء:
            </span>
            {SANJOOB_KNOWLEDGE.map((qa, idx) => (
              <button
                key={qa.id}
                ref={idx === 0 ? firstButtonRef : undefined}
                tabIndex={0}
                onClick={() => {
                  setSelectedQA(qa);
                  speakText(qa.detailedAnswer);
                }}
                className={`tv-focusable w-full text-right p-4 rounded-2xl flex items-center gap-4 transition border ${
                  selectedQA.id === qa.id
                    ? 'bg-[#FFFFFF] text-[#0B0B0D] font-black border-white shadow-lg shadow-[#FFFFFF]/30 scale-[1.02]'
                    : 'bg-white/5 hover:bg-white/10 text-white border-white/5'
                }`}
              >
                <span className="text-3xl">{qa.icon}</span>
                <div className="flex-1 truncate">
                  <span className="text-lg font-bold block truncate">{qa.question}</span>
                  <span className={`text-xs block ${selectedQA.id === qa.id ? 'text-[#0B0B0D]/75' : 'text-[#A1A1A6]'}`}>
                    {qa.category}
                  </span>
                </div>
                <span className="text-xl">◀</span>
              </button>
            ))}
          </div>

          {/* لوحة الإجابة التفاعلية مع سنجوب (7 أعمدة) */}
          <div className="col-span-7 bg-[#141E33] border border-white/10 rounded-3xl p-8 flex flex-col justify-between shadow-inner relative overflow-hidden">
            {/* خلفية جمالية */}
            <div className="absolute top-0 left-0 w-64 h-64 bg-[#FFFFFF]/10 rounded-full blur-3xl pointer-events-none" />

            <div className="space-y-6 relative z-10">
              <div className="flex items-center justify-between">
                <span className="bg-[#FFFFFF]/20 text-[#FFFFFF] text-sm font-black px-4 py-1.5 rounded-xl border border-[#FFFFFF]/30">
                  {selectedQA.category}
                </span>
                <div className="flex items-center gap-2">
                  {isSpeaking && (
                    <span className="flex items-center gap-1.5 text-xs text-emerald-400 bg-emerald-500/20 px-3 py-1 rounded-full animate-pulse border border-emerald-500/30">
                      <span>🔊</span>
                      <span>سنجوب يتحدث الآن…</span>
                    </span>
                  )}
                </div>
              </div>

              <h3 className="text-3xl font-black text-white flex items-center gap-3">
                <span className="text-4xl">{selectedQA.icon}</span>
                <span>{selectedQA.question}</span>
              </h3>

              {/* بطاقة الإجابة المختصرة */}
              <div className="bg-[#FFFFFF]/10 border-r-4 border-[#FFFFFF] p-4 rounded-xl">
                <p className="text-xl font-black text-[#FFFFFF]">
                  "{selectedQA.shortAnswer}"
                </p>
              </div>

              {/* الشرح التفصيلي اللطيف */}
              <div className="text-xl text-[#D1D1D6] leading-relaxed space-y-3 font-medium">
                <p>{selectedQA.detailedAnswer}</p>
              </div>
            </div>

            {/* أزرار التحكم الصوتي */}
            <div className="pt-6 border-t border-white/10 flex items-center justify-between relative z-10">
              <div className="flex items-center gap-4">
                <button
                  tabIndex={0}
                  onClick={() => speakText(selectedQA.detailedAnswer)}
                  className="tv-focusable bg-[#2C2C2E] hover:bg-[#3A3A3C] focus:bg-[#FFFFFF] focus:text-[#0B0B0D] text-white font-black text-lg px-7 py-3.5 rounded-2xl shadow-xl flex items-center gap-3 transition"
                >
                  <span className="text-2xl">{isSpeaking ? '🔊' : '🗣️'}</span>
                  <span>{isSpeaking ? 'أعد القراءة بالصوت' : 'اسمع إجابة سنجوب'}</span>
                </button>

                {isSpeaking && (
                  <button
                    tabIndex={0}
                    onClick={() => {
                      if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
                        window.speechSynthesis.cancel();
                      }
                      setIsSpeaking(false);
                    }}
                    className="tv-focusable bg-white/10 hover:bg-white/20 text-white font-bold text-base px-5 py-3.5 rounded-2xl border border-white/10 transition"
                  >
                    إيقاف الصوت ⏹
                  </button>
                )}
              </div>

              <div className="text-left text-xs text-[#A1A1A6]">
                <span>نصيحة سنجوب: كُن دائم الفضول واسأل دائماً! 🌟</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
