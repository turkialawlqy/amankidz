import React from 'react';
import type { ContentItem } from '../types';

interface ContentCardProps {
  item: ContentItem;
  onSelect: (item: ContentItem) => void;
  width?: string;
  aspectRatio?: 'poster' | 'backdrop';
}

export const ContentCard: React.FC<ContentCardProps> = ({
  item,
  onSelect,
  width = 'w-52',
  aspectRatio = 'poster',
}) => {
  const image =
    aspectRatio === 'backdrop'
      ? item.backdrop_url || item.thumbnail_url || item.poster_url
      : item.poster_url || item.thumbnail_url || item.backdrop_url;

  return (
    <div
      tabIndex={0}
      role="button"
      onClick={() => onSelect(item)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          onSelect(item);
        }
      }}
      className={`tv-focusable ${width} flex-shrink-0 flex flex-col group cursor-pointer focus:outline-none rounded-xl`}
    >
      <div
        className={`relative w-full ${
          aspectRatio === 'backdrop' ? 'aspect-video' : 'aspect-[2/3]'
        } rounded-xl overflow-hidden bg-[#212639] border-2 border-transparent group-focus:border-[#2FD3BF] group-focus:shadow-xl group-focus:shadow-[#2FD3BF]/20 transition-all duration-150`}
      >
        {image ? (
          <img
            src={image}
            alt={item.title}
            className="w-full h-full object-cover group-focus:scale-105 transition-transform duration-200"
            loading="lazy"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center p-4 text-center text-sm font-medium text-[#A7ADBF]">
            {item.title}
          </div>
        )}

        {/* شارة المحتوى التعليمي */}
        {item.educational && (
          <div className="absolute top-2 right-2 bg-emerald-500/90 text-white text-xs font-bold px-2 py-0.5 rounded-md shadow">
            تعليمي
          </div>
        )}

        {/* شارة الموسم / الحلقة */}
        {item.season && (
          <div className="absolute bottom-2 right-2 bg-black/80 backdrop-blur-sm text-white text-xs font-mono px-2 py-0.5 rounded">
            م{item.season}
          </div>
        )}
      </div>

      <div className="mt-2 text-right">
        <h4 className="text-sm font-medium text-white truncate group-focus:text-[#2FD3BF] transition-colors">
          {item.title}
        </h4>
        {item.year && <span className="text-xs text-[#A7ADBF]">{item.year}</span>}
      </div>
    </div>
  );
};
