import React from 'react';

export const BrandMark: React.FC<{ size?: number; className?: string }> = ({ size = 64, className = '' }) => {
  return (
    <div
      style={{ width: size, height: size }}
      className={`relative flex items-center justify-center rounded-2xl bg-gradient-to-br from-[#2FD3BF] to-[#0E9484] shadow-lg shadow-[#2FD3BF]/20 ${className}`}
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="w-3/5 h-3/5 text-[#0E1A24]"
      >
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <path d="m9 12 2 2 4-4" />
      </svg>
    </div>
  );
};
