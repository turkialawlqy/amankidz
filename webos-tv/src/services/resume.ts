import { supabase } from './supabase';
import type { ContentItem } from '../types';

export interface ProgressItem {
  id: string;
  title: string;
  thumb?: string | null;
  pos: number; // بالثواني
  dur: number; // بالثواني
  done: boolean;
  at: string;
  item?: ContentItem;
}

const STORAGE_KEY = 'aman_resume_progress';

export function getLocalProgress(): Record<string, ProgressItem> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export function saveLocalProgress(item: ProgressItem) {
  try {
    const all = getLocalProgress();
    all[item.id] = item;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all));
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('aman_continue_watching_updated'));
    }
  } catch {}
}

export function removeFromContinueWatching(id: string) {
  try {
    const all = getLocalProgress();
    delete all[id];
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all));
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('aman_continue_watching_updated'));
    }
  } catch {}
}

export function getProgressRatio(videoId: string): number {
  const all = getLocalProgress();
  const entry = all[videoId];
  if (!entry || entry.dur <= 0) return 0;
  if (entry.done) return 1;
  return Math.min(1, Math.max(0, entry.pos / entry.dur));
}

export function getStartPosition(videoId: string): number {
  const all = getLocalProgress();
  const entry = all[videoId];
  if (!entry || entry.done) return 0;
  // إذا تمت مشاهدة أكثر من 90% أو متبقي أقل من 30 ثانية، يبدأ دائماً من البداية 0
  if (entry.dur > 0 && (entry.dur - entry.pos < 30 || entry.pos / entry.dur >= 0.90)) {
    return 0;
  }
  // إذا كان الموضع في أول 5 ثواني، يبدأ من الأول
  if (entry.pos <= 5) return 0;
  return entry.pos;
}

/** قائمة أكمل المشاهدة مرتبة من الأحدث */
export function getContinueWatchingList(): ProgressItem[] {
  const all = getLocalProgress();
  return Object.values(all)
    .filter(
      (entry) =>
        !entry.done &&
        entry.pos >= 5 &&
        entry.dur > 0 &&
        entry.pos / entry.dur < 0.90 &&
        entry.dur - entry.pos >= 30
    )
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
    .slice(0, 30);
}

/** قائمة المسلسلات المتابعة */
export function getContinueWatchingSeries(): ProgressItem[] {
  return getContinueWatchingList().filter(
    (entry) =>
      !!entry.item?.parent_id ||
      entry.item?.kind === 'mix' ||
      entry.item?.kind === 'playlist' ||
      entry.id.startsWith('xs:') ||
      (entry.title &&
        (entry.title.includes('حلقة') ||
          entry.title.includes('الحلقة') ||
          entry.title.includes('الموسم') ||
          entry.title.includes('Episode') ||
          entry.title.includes('Ep.')))
  );
}

/** قائمة الأفلام المتابعة */
export function getContinueWatchingMovies(): ProgressItem[] {
  return getContinueWatchingList().filter(
    (entry) =>
      !entry.item?.parent_id &&
      (entry.item?.kind === 'video' || entry.id.startsWith('xv:')) &&
      !(
        entry.title &&
        (entry.title.includes('حلقة') ||
          entry.title.includes('الحلقة') ||
          entry.title.includes('الموسم') ||
          entry.title.includes('Episode') ||
          entry.title.includes('Ep.'))
      )
  );
}

// قائمة مؤقتة للإرسال إلى السيرفر
let pendingQueue: ProgressItem[] = [];
let syncTimer: ReturnType<typeof setTimeout> | null = null;

export function recordWatchProgress(
  item: ContentItem,
  posSeconds: number,
  durSeconds: number,
  profileId?: string | null
) {
  if (durSeconds <= 0) return;

  // إذا تمت مشاهدة أكثر من 90% أو متبقي أقل من 25 ثانية، تعتبر منتهية ويُعاد ضبط الموضع على 0
  const isDone = posSeconds >= durSeconds - 25 || (durSeconds > 0 && posSeconds / durSeconds >= 0.90);
  const progressEntry: ProgressItem = {
    id: item.id,
    title: item.title,
    thumb: item.thumbnail_url || item.poster_url,
    pos: isDone ? 0 : Math.round(posSeconds),
    dur: Math.round(durSeconds),
    done: isDone,
    at: new Date().toISOString(),
    item,
  };

  saveLocalProgress(progressEntry);

  pendingQueue.push(progressEntry);

  if (!syncTimer) {
    syncTimer = setTimeout(() => {
      flushProgressToSupabase(profileId);
    }, 8000);
  }
}

async function flushProgressToSupabase(profileId?: string | null) {
  syncTimer = null;
  if (pendingQueue.length === 0) return;

  const toSend = [...pendingQueue];
  pendingQueue = [];

  const payload = toSend.map((p) => ({
    id: p.id,
    title: p.title,
    profile: profileId || '',
    pos: p.pos,
    dur: p.dur,
    done: p.done,
    at: p.at,
  }));

  try {
    await supabase.rpc('device_report_progress', {
      p_items: payload,
    });
  } catch (err) {
    console.warn('Error reporting progress to Supabase:', err);
  }
}
