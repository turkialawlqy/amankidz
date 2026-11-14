import React, { useEffect, useRef } from 'react';
import type { ContentItem } from '../types';

interface CollectionScreenProps {
  item: ContentItem;
  videos: ContentItem[];
  onPlayVideo: (video: ContentItem, list: ContentItem[]) => void;
  onBack: () => void;
}

export const CollectionScreen: React.FC<CollectionScreenProps> = ({
  item,
  videos,
  onPlayVideo,
  onBack,
}) => {
  const backButtonRef = useRef<HTMLButtonElement>(null);

  // تركيز تلقائي على زر الرجوع فور فتح الشاشة حتى لا يبقى التركيز
  // على عنصر خلف هذه الشاشة في HomeScreen
  useEffect(() => {
    backButtonRef.current?.focus();
  }, []);

  return (
    <div
      data-tv-modal-root
      className="fixed inset-0 z-40 bg-[#181C2B] text-white flex flex-col p-10 overflow-hidden select-none"
    >
      {/* الترويسة العلوية مع زر الرجوع ومعلومات القناة / القائمة */}
      <div className="flex items-center justify-between pb-6 border-b border-white/10 mb-8 flex-shrink-0">
        <div className="flex items-center gap-6">
          <div className="w-20 h-20 rounded-2xl overflow-hidden bg-[#212639] border border-white/10 flex items-center justify-center flex-shrink-0">
            {item.thumbnail_url || item.poster_url ? (
              <img
                src={item.thumbnail_url || item.poster_url || ''}
                alt={item.title}
                className="w-full h-full object-cover"
              />
            ) : (
              <span className="text-3xl">📺</span>
            )}
          </div>
          <div>
            <div className="text-xs font-bold text-[#2FD3BF] mb-1">
              {item.kind === 'channel' ? 'قناة يوتيوب' : 'قائمة تشغيل'}
            </div>
            <h1 className="text-3xl font-black text-white">{item.title}</h1>
            <p className="text-sm text-[#A7ADBF] mt-1">{videos.length} فيديو</p>
          </div>
        </div>

        <button
          ref={backButtonRef}
          tabIndex={0}
          onClick={onBack}
          className="tv-focusable bg-[#212639] hover:bg-[#2E3449] focus:bg-[#2FD3BF] focus:text-[#0E1A24] font-bold text-base px-6 py-3 rounded-xl transition flex items-center gap-2"
        >
          <span>←</span>
          <span>الرجوع</span>
        </button>
      </div>

      {/* شبكة الفيديوهات داخل هذه القناة أو القائمة */}
      <div className="flex-1 overflow-y-auto">
        {videos.length > 0 ? (
          <div className="grid grid-cols-4 gap-6 pb-12">
            {videos.map((vid, idx) => (
              <div
                key={vid.id || idx}
                tabIndex={0}
                role="button"
                onClick={() => onPlayVideo(vid, videos)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    onPlayVideo(vid, videos);
                  }
                }}
                className="tv-focusable cursor-pointer flex flex-col group rounded-xl"
              >
                <div className="w-full aspect-video rounded-xl overflow-hidden bg-[#212639] border-2 border-transparent group-focus:border-[#2FD3BF] group-focus:shadow-xl group-focus:shadow-[#2FD3BF]/25 transition relative">
                  {vid.thumbnail_url ? (
                    <img
                      src={vid.thumbnail_url}
                      alt={vid.title}
                      className="w-full h-full object-cover group-focus:scale-105 transition-transform"
                      loading="lazy"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-xs text-[#A7ADBF] p-2 text-center">
                      {vid.title}
                    </div>
                  )}
                  <div className="absolute bottom-2 left-2 bg-black/80 text-white font-mono text-[10px] px-1.5 py-0.5 rounded" dir="ltr">
                    {idx + 1}
                  </div>
                </div>
                <h4 className="text-xs font-bold text-white mt-2 truncate w-full group-focus:text-[#2FD3BF]">
                  {vid.title}
                </h4>
              </div>
            ))}
          </div>
        ) : (
          <div className="w-full h-64 flex flex-col items-center justify-center text-center p-8 bg-[#212639]/40 rounded-2xl border border-white/5">
            <span className="text-4xl mb-3">📁</span>
            <h3 className="text-lg font-bold text-white mb-1">القائمة فارغة</h3>
            <p className="text-xs text-[#A7ADBF]">
              لم يتم تحميل فيديوهات لهذه القائمة بعد.
            </p>
          </div>
        )}
      </div>
    </div>
  );
};
