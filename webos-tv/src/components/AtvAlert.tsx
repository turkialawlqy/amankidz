import React from 'react';

/**
 * إشعار عائم موحّد بنمط Apple TV (كبسولة زجاجية أعلى الشاشة).
 * tone: يحدد لون النقطة الصغيرة فقط ليبقى التصميم هادئاً.
 */
export const AtvToast: React.FC<{
  children: React.ReactNode;
  icon?: React.ReactNode;
  tone?: 'info' | 'ok' | 'warn' | 'error';
  top?: number;
  className?: string;
}> = ({ children, icon, tone = 'info', top = 40, className = '' }) => (
  <div className={`atv-toast atv-toast-${tone} ${className}`} style={{ top }} role="status">
    {icon != null ? <span className="atv-toast-icon">{icon}</span> : <span className="atv-toast-dot" />}
    <span className="min-w-0">{children}</span>
  </div>
);
