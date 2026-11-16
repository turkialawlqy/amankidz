import React, { useEffect, useState } from 'react';
import { searchTmdbByTitle } from '../services/tmdb';

interface RatingBadgeProps {
  title: string;
  year?: number | null;
  kind: 'movie' | 'series';
  className?: string;
}

// شارة تقييم بنمط IMDb — تظهر فقط عند توفر VITE_TMDB_API_KEY وتطابق حقيقي؛
// بدون ذلك لا تُعرض إطلاقاً (لا أرقام وهمية أبداً)
export const RatingBadge: React.FC<RatingBadgeProps> = ({ title, year, kind, className }) => {
  const [rating, setRating] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    setRating(null);
    searchTmdbByTitle(title, year, kind).then((match) => {
      if (active && match?.rating != null) setRating(match.rating);
    });
    return () => {
      active = false;
    };
  }, [title, year, kind]);

  if (rating == null) return null;

  return (
    <span
      className={`bg-[#F5C518] text-black text-[10px] font-black px-1.5 py-0.5 rounded flex items-center gap-1 ${className || ''}`}
    >
      <span>IMDb</span>
      <span>{rating}</span>
    </span>
  );
};
