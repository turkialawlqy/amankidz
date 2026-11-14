// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { navigateSpatial } from './useRemoteKeys';

// يحاكي هذا الملف البنية الفعلية للتطبيق: شاشة PlayerView تُعرض فوق HomeScreen
// بدون فكّها من الـ DOM (لحفظ حالتها)، وتتداخل بصرياً معها في نفس إحداثيات
// الشاشة. أزرار HomeScreen (الشريط الجانبي/العلوي) تبقى لها tabIndex=0 ومساحة
// غير صفرية رغم تغطيتها بصرياً بالفيديو، فتظل "مرئية" حسابياً لدالة
// navigateSpatial ما لم يُحصر البحث داخل جذر المشغّل عبر [data-tv-modal-root]
// — وهذا بالضبط ما كانت كل النوافذ الأخرى (ProfileModal, ExitModal,
// SettingsModal, DetailsModal, CollectionScreen, SanjoobModal) تفعله، ما عدا
// PlayerView.

function makeButton(id: string, rect: { x: number; y: number; w: number; h: number }): HTMLButtonElement {
  const btn = document.createElement('button');
  btn.id = id;
  btn.tabIndex = 0;
  btn.getBoundingClientRect = () =>
    ({
      x: rect.x,
      y: rect.y,
      left: rect.x,
      top: rect.y,
      right: rect.x + rect.w,
      bottom: rect.y + rect.h,
      width: rect.w,
      height: rect.h,
      toJSON() {},
    }) as DOMRect;
  return btn;
}

describe('navigateSpatial — عزل التنقل داخل المشغّل عن الشاشة الرئيسية المختفية خلفه', () => {
  let home: HTMLDivElement;
  let player: HTMLDivElement;

  beforeEach(() => {
    document.body.innerHTML = '';
    // jsdom لا يطبّق scrollIntoView فعلياً (لا تخطيط حقيقي للصفحة لديه)
    Element.prototype.scrollIntoView = () => {};

    // شاشة رئيسية تبقى في الـ DOM خلف المشغّل (كما في App.tsx الفعلي)
    home = document.createElement('div');
    document.body.appendChild(home);
    // زر في الشريط الجانبي لـ HomeScreen، قريب جغرافياً جداً من زر المشغّل
    // المقصود (هذا بالضبط ما يجعله "يخطف" التركيز بدون عزل صحيح)
    home.appendChild(makeButton('home-decoy', { x: 520, y: 900, w: 60, h: 60 }));

    // مشغّل الفيديو، يغطي HomeScreen بصرياً (z-index أعلى) لكنه لا يفكّكه من الـ DOM
    player = document.createElement('div');
    document.body.appendChild(player);
    player.appendChild(makeButton('player-current', { x: 500, y: 1000, w: 60, h: 60 }));
    // الزر الحقيقي المقصود في شريط تحكم المشغّل، أبعد قليلاً من "الفخ" أعلاه
    player.appendChild(makeButton('player-target', { x: 500, y: 850, w: 60, h: 60 }));
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('يكشف الخلل: بدون data-tv-modal-root على جذر المشغّل، الأسهم قد تقفز إلى زر مخفي خلف الفيديو بدل زر المشغّل الفعلي', () => {
    document.getElementById('player-current')!.focus();
    expect(document.activeElement?.id).toBe('player-current');

    navigateSpatial('up');

    // الفخ (home-decoy) أقرب جغرافياً من الهدف الصحيح (player-target)، وبدون
    // عزل عبر data-tv-modal-root يفوز لأنه ضمن نفس نتائج querySelectorAll
    expect(document.activeElement?.id).toBe('home-decoy');
  });

  it('الإصلاح: مع data-tv-modal-root على جذر المشغّل، الأسهم تبقى محصورة داخل أزرار المشغّل فقط', () => {
    player.setAttribute('data-tv-modal-root', '');

    document.getElementById('player-current')!.focus();
    navigateSpatial('up');

    expect(document.activeElement?.id).toBe('player-target');
  });
});
