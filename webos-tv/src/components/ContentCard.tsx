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
      className={`atv-item ${width} flex-shrink-0 flex flex-col group`}
    >
      <div
        className={`atv-card w-full ${aspectRatio === 'backdrop' ? 'aspect-video' : 'aspect-[2/3]'}`}
      >
        {image ? (
          <img
            src={image}
            alt={item.title}
            className="w-full h-full object-cover"
            loading="lazy"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center p-4 text-center text-sm font-medium text-[#A1A1A6]">
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

      <div className="atv-caption">
        <h4 className="truncate">
          {item.title}
        </h4>
        {item.year && <span className="text-xs atv-sub">{item.year}</span>}
      </div>
    </div>
  );
};
