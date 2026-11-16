import React from 'react';

/** مؤشر انتظار بنمط Apple TV (12 شعاع متلاشي) */
export const AtvSpinner: React.FC<{ size?: number; className?: string }> = ({ size = 44, className = '' }) => (
  <div className={`atv-spinner ${className}`} style={{ width: size, height: size }} role="status" aria-label="جاري التحميل">
    {Array.from({ length: 12 }).map((_, i) => (
      <span
        key={i}
        style={{ transform: `rotate(${i * 30}deg)`, animationDelay: `${-1.2 + i * 0.1}s` }}
      />
    ))}
  </div>
);
