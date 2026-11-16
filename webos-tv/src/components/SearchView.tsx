import React, { useState } from 'react';
import { AtvSpinner } from './AtvSpinner';
import { AtvToast } from './AtvAlert';
import type { IptvSource, LiveChannel, ContentItem } from '../types';
import { searchXtream } from '../services/xtream';

interface SearchViewProps {
  sources: IptvSource[];
  onSelectChannel: (ch: LiveChannel) => void;
  onSelectItem: (item: ContentItem) => void;
}

export const SearchView: React.FC<SearchViewProps> = ({
  sources,
  onSelectChannel,
  onSelectItem,
}) => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [results, setResults] = useState<{
    live: LiveChannel[];
    vod: ContentItem[];
    series: ContentItem[];
  }>({ live: [], vod: [], series: [] });

  const handleSearch = async (text: string) => {
    setQuery(text);
    if (!text.trim()) {
      setResults({ live: [], vod: [], series: [] });
      return;
    }
    setLoading(true);
    try {
      const res = await searchXtream(sources, text);
      setResults(res);
    } finally {
      setLoading(false);
    }
  };

  // البحث الصوتي الذكي عبر الميكروفون أو ماجيك ريموت
  const startVoiceSearch = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setNotice('البحث الصوتي غير مدعوم على هذه الشاشة');
      setTimeout(() => setNotice(null), 3000);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.lang = 'ar-SA';
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;

      setIsListening(true);

      recognition.onresult = (event: any) => {
        const transcript = event.results?.[0]?.[0]?.transcript;
        if (transcript) {
          handleSearch(transcript);
        }
        setIsListening(false);
      };

      recognition.onerror = () => {
        setIsListening(false);
      };

      recognition.onend = () => {
        setIsListening(false);
      };

      recognition.start();
    } catch (err) {
      console.warn('SpeechRecognition start error:', err);
      setIsListening(false);
    }
  };

  const totalResults =
    results.live.length + results.vod.length + results.series.length;

  return (
    <div className="w-full h-full flex flex-col p-8 bg-[#0B0B0D] text-white overflow-y-auto">
      {notice && <AtvToast tone="warn">{notice}</AtvToast>}
      {/* خانة البحث */}
      <div className="max-w-2xl mx-auto w-full mb-8">
        <h2 className="text-2xl font-black text-white mb-4 text-center">
          البحث في القنوات والأفلام والمسلسلات
        </h2>
        <div className="flex gap-3">
          <input
            type="text"
            autoFocus
            tabIndex={0}
            value={query}
            placeholder="اكتب اسم القناة، الفيلم، أو المسلسل…"
            onChange={(e) => handleSearch(e.target.value)}
            className="tv-focusable flex-1 bg-[#1C1C1E] border-2 border-white/10 focus:border-[#FFFFFF] rounded-2xl px-6 py-4 text-lg font-bold text-white placeholder-[#A1A1A6] focus:outline-none transition shadow-lg text-right"
          />

          {/* زر البحث الصوتي بالمايكروفون */}
          <button
            tabIndex={0}
            onClick={startVoiceSearch}
            className={`tv-focusable px-5 py-4 rounded-2xl font-black text-lg transition flex items-center gap-2 border border-white/10 ${
              isListening
                ? 'bg-red-500 text-white animate-pulse'
                : 'bg-white/10 hover:bg-white/20 text-[#FFFFFF] focus:bg-[#FFFFFF] focus:text-[#0B0B0D]'
            }`}
            title="بحث صوتي (تحدث الآن)"
          >
            <span>🎙️</span>
            <span className="text-sm">{isListening ? 'جاري الاستماع…' : 'صوتي'}</span>
          </button>

          {query && (
            <button
              tabIndex={0}
              onClick={() => handleSearch('')}
              className="tv-focusable bg-white/10 hover:bg-white/20 px-6 py-4 rounded-2xl font-bold text-base transition"
            >
              مسح
            </button>
          )}
        </div>

        {isListening && (
          <p className="text-center text-sm font-bold text-[#FFFFFF] mt-3 animate-pulse">
            🎙️ تحدّث الآن باسم المحتوى بصوت واضح…
          </p>
        )}
      </div>

      {loading && (
        <div className="flex flex-col items-center justify-center py-12 text-[#A1A1A6]">
          <AtvSpinner className="mb-5" />
          <p>جاري البحث في الاشتراك…</p>
        </div>
      )}

      {!loading && query && totalResults === 0 && (
        <div className="text-center py-16 text-[#A1A1A6]">
          <span className="text-5xl mb-3 block">🔍</span>
          <p className="text-xl font-bold text-white mb-1">لا توجد نتائج مطابقة</p>
          <p className="text-sm">تأكد من كتابة الاسم بشكل صحيح أو جرب كلمة بحث أخرى</p>
        </div>
      )}

      {/* نتائج قنوات البث المباشر */}
      {!loading && results.live.length > 0 && (
        <section className="mb-8">
          <h3 className="text-xl font-black text-white mb-4">
            قنوات البث المباشر ({results.live.length})
          </h3>
          <div className="grid grid-cols-4 lg:grid-cols-6 gap-x-7 gap-y-4 pt-2">
            {results.live.map((ch) => (
              <button
                key={ch.id}
                tabIndex={0}
                onClick={() => onSelectChannel(ch)}
                className="tv-focusable bg-[#1C1C1E] hover:bg-[#2C2C2E] focus:bg-[#FFFFFF] focus:text-[#0B0B0D] p-3 rounded-2xl flex flex-col items-center justify-center text-center shadow-md transition h-28"
              >
                {ch.logo ? (
                  <img
                    src={ch.logo}
                    alt={ch.name}
                    className="h-10 max-w-[80%] object-contain mb-1"
                  />
                ) : (
                  <span className="text-2xl mb-1">📡</span>
                )}
                <span className="text-xs font-bold truncate w-full">{ch.name}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      {/* نتائج الأفلام */}
      {!loading && results.vod.length > 0 && (
        <section className="mb-8">
          <h3 className="text-xl font-black text-white mb-4">
            الأفلام ({results.vod.length})
          </h3>
          <div className="grid grid-cols-4 lg:grid-cols-6 gap-x-7 gap-y-4 pt-2">
            {results.vod.map((item) => (
              <div
                key={item.id}
                tabIndex={0}
                role="button"
                onClick={() => onSelectItem(item)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') onSelectItem(item);
                }}
                className="atv-item flex flex-col group"
              >
                <div className="atv-card aspect-[2/3] w-full">
                  {item.thumbnail_url ? (
                    <img
                      src={item.thumbnail_url}
                      alt={item.title}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-3xl">
                      🎬
                    </div>
                  )}
                </div>
                <span className="text-xs font-bold text-white mt-2 truncate w-full group-focus:text-[#FFFFFF] text-right">
                  {item.title}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* نتائج المسلسلات */}
      {!loading && results.series.length > 0 && (
        <section className="mb-8">
          <h3 className="text-xl font-black text-white mb-4">
            المسلسلات ({results.series.length})
          </h3>
          <div className="grid grid-cols-4 lg:grid-cols-6 gap-x-7 gap-y-4 pt-2">
            {results.series.map((item) => (
              <div
                key={item.id}
                tabIndex={0}
                role="button"
                onClick={() => onSelectItem(item)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') onSelectItem(item);
                }}
                className="atv-item flex flex-col group"
              >
                <div className="atv-card aspect-[2/3] w-full">
                  {item.thumbnail_url ? (
                    <img
                      src={item.thumbnail_url}
                      alt={item.title}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-3xl">
                      📺
                    </div>
                  )}
                </div>
                <span className="text-xs font-bold text-white mt-2 truncate w-full group-focus:text-[#FFFFFF] text-right">
                  {item.title}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
};
