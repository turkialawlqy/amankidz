import { useEffect, useCallback } from 'react';

// WebOS & Samsung Tizen Key Codes
export const WEBOS_KEYS = {
  ENTER: 13,
  BACK: 461, // LG webOS back key
  TIZEN_BACK: 10009, // Samsung Tizen back key
  ESCAPE: 27,
  BACKSPACE: 8,
  LEFT: 37,
  UP: 38,
  RIGHT: 39,
  DOWN: 40,
  PLAY: 415,
  PAUSE: 19,
  PLAY_PAUSE: 10252, // Tizen Play/Pause
  STOP: 413,
  FAST_FORWARD: 417,
  REWIND: 412,
  CHANNEL_UP: 427,
  CHANNEL_DOWN: 428,
  RED: 403,
  GREEN: 404,
  YELLOW: 405,
  BLUE: 406,
};

// حارس مشترك عبر كل التطبيق لضغطة "رجوع": بعض أجهزة التحكم (والنقر المطوّل)
// يرسل أكثر من حدث keydown لنفس الضغطة الفعلية الواحدة. بدون هذا الحارس، إذا
// كانت الضغطة الأولى أغلقت مشغّل الفيديو (فيُفكك مكوّنه ومستمعه الخاص)، فإن
// الحدث "الصدى" التالي يصل لهذا الـ hook العام بعد أن أصبح مفعّلاً، فيُفسَّر
// خطأً كضغطة "رجوع" جديدة من الشاشة الرئيسية ويفتح نافذة تأكيد الخروج!
let lastBackHandledAt = 0;
export function shouldHandleBackPress(): boolean {
  const now = Date.now();
  if (now - lastBackHandledAt < 400) return false;
  lastBackHandledAt = now;
  return true;
}

interface KeyHandlerProps {
  onBack?: () => void;
  onEnter?: () => void;
  onPlayPause?: () => void;
  enabled?: boolean;
}

/**
 * دالة لحساب العنصر الأقرب جغرافياً في الشاشة عند الضغط على الأسهم (Spatial Navigation)
 */
export function navigateSpatial(direction: 'up' | 'down' | 'left' | 'right') {
  // إذا كانت هناك نافذة منبثقة مفتوحة (Modal) نحصر البحث عن العناصر داخلها فقط،
  // وإلا فإن الأسهم قد "تهرب" للعناصر المخفية خلفها في شاشة HomeScreen
  const modalRoots = document.querySelectorAll<HTMLElement>('[data-tv-modal-root]');
  const scopeRoot: ParentNode = modalRoots.length > 0 ? modalRoots[modalRoots.length - 1] : document;

  const activeElement = document.activeElement as HTMLElement | null;
  const focusables = Array.from(
    scopeRoot.querySelectorAll<HTMLElement>('[tabindex="0"]:not([disabled])')
  ).filter((el) => {
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).display !== 'none';
  });

  if (focusables.length === 0) return;

  if (!activeElement || !focusables.includes(activeElement)) {
    focusables[0]?.focus();
    return;
  }

  const currentRect = activeElement.getBoundingClientRect();
  const currentCenterX = currentRect.left + currentRect.width / 2;
  const currentCenterY = currentRect.top + currentRect.height / 2;

  let bestCandidate: HTMLElement | null = null;
  let minDistance = Infinity;

  for (const el of focusables) {
    if (el === activeElement) continue;

    const r = el.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;

    const dx = cx - currentCenterX;
    const dy = cy - currentCenterY;

    // RTL consideration: left key moves visually left, right key moves visually right
    let isValid = false;
    if (direction === 'up' && dy < -5) isValid = true;
    if (direction === 'down' && dy > 5) isValid = true;
    if (direction === 'left' && dx < -5) isValid = true;
    if (direction === 'right' && dx > 5) isValid = true;

    if (isValid) {
      // Primary axis distance vs secondary axis distance penalty
      let distance: number;
      if (direction === 'up' || direction === 'down') {
        distance = Math.abs(dy) + Math.abs(dx) * 1.8;
      } else {
        distance = Math.abs(dx) + Math.abs(dy) * 1.8;
      }

      if (distance < minDistance) {
        minDistance = distance;
        bestCandidate = el;
      }
    }
  }

  if (bestCandidate) {
    bestCandidate.focus();
    // Smooth scroll into view if needed
    bestCandidate.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }
}

export function useRemoteKeys({ onBack, onEnter, onPlayPause, enabled = true }: KeyHandlerProps = {}) {
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (!enabled) return;

      const code = e.keyCode || e.which;

      // داخل حقول الإدخال (كخانة البحث) نترك Backspace والأسهم تعمل بسلوكها
      // الطبيعي (حذف حرف وتحريك المؤشر) بدل اختطافها للتنقل العام أو اعتبارها
      // ضغطة "رجوع" — ونكتفي بالسماح لأزرار الرجوع الصريحة بإغلاق الحقل
      const target = e.target as HTMLElement | null;
      const isEditableField =
        !!target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      if (
        isEditableField &&
        code !== WEBOS_KEYS.BACK &&
        code !== WEBOS_KEYS.TIZEN_BACK &&
        code !== WEBOS_KEYS.ESCAPE
      ) {
        return;
      }

      // Handle Back Button (webOS 461, Tizen 10009, or Escape)
      if (
        code === WEBOS_KEYS.BACK ||
        code === WEBOS_KEYS.TIZEN_BACK ||
        code === WEBOS_KEYS.ESCAPE ||
        code === WEBOS_KEYS.BACKSPACE
      ) {
        // Prevent default browser history back navigation
        e.preventDefault();
        e.stopPropagation();
        if (!shouldHandleBackPress()) return;
        if (onBack) {
          onBack();
        } else if (window.history.length > 1) {
          window.history.back();
        }
        return;
      }

      // Handle Enter/OK
      if (code === WEBOS_KEYS.ENTER) {
        if (onEnter) onEnter();
        return;
      }

      // Handle Play / Pause media keys
      if (code === WEBOS_KEYS.PLAY || code === WEBOS_KEYS.PAUSE) {
        if (onPlayPause) {
          e.preventDefault();
          onPlayPause();
        }
        return;
      }

      // Directional D-Pad navigation
      if (code === WEBOS_KEYS.UP) {
        e.preventDefault();
        navigateSpatial('up');
      } else if (code === WEBOS_KEYS.DOWN) {
        e.preventDefault();
        navigateSpatial('down');
      } else if (code === WEBOS_KEYS.LEFT) {
        e.preventDefault();
        navigateSpatial('left');
      } else if (code === WEBOS_KEYS.RIGHT) {
        e.preventDefault();
        navigateSpatial('right');
      }
    },
    [enabled, onBack, onEnter, onPlayPause]
  );

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);
}
