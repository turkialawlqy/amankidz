import { soundEffects } from './sound';

export interface Badge {
  id: string;
  title: string;
  description: string;
  icon: string;
  color: string;
}

export const ALL_BADGES: Badge[] = [
  {
    id: 'explorer',
    title: 'المستكشف الصغير',
    description: 'شاهد 3 برامج وثائقية وعلمية مفيدة',
    icon: '🔬',
    color: 'from-blue-500 to-indigo-600',
  },
  {
    id: 'time_hero',
    title: 'بطل الالتزام بالوقت',
    description: 'أنهى وقت المشاهدة المخصص باحترام دون تذمر',
    icon: '⏱️',
    color: 'from-amber-400 to-orange-500',
  },
  {
    id: 'genius',
    title: 'العبقري الذكي',
    description: 'أجاب على أسئلة تحدي الأبطال وكسب مكافأة',
    icon: '🧠',
    color: 'from-emerald-400 to-teal-600',
  },
  {
    id: 'bilingual',
    title: 'مستكشف اللغات',
    description: 'جرّب وضع التعلم بالترجمة المزدوجة',
    icon: '🌍',
    color: 'from-sky-400 to-cyan-600',
  },
  {
    id: 'bedtime_star',
    title: 'نجم النوم الهادئ',
    description: 'توجّه للنوم والراحة فور حلول وقت النوم',
    icon: '🌙',
    color: 'from-purple-500 to-pink-600',
  },
  {
    id: 'exercise_champ',
    title: 'بطل النشاط والحركة',
    description: 'أنجز تحدي التمارين الرياضية أثناء المشاهدة',
    icon: '🏃‍♂️',
    color: 'from-orange-500 to-red-500',
  },
  {
    id: 'radio_dreamer',
    title: 'مستمع قصص النوم',
    description: 'استمع لقصص النوم في وضع إطفاء الشاشة',
    icon: '📻',
    color: 'from-indigo-600 to-purple-800',
  },
  {
    id: 'sanjoob_friend',
    title: 'صديق سنجوب الفضولي',
    description: 'طرح أسئلة علمية ذكية على المساعد سنجوب',
    icon: '🐿️',
    color: 'from-amber-500 to-yellow-500',
  },
];

const BADGES_KEY = 'amankidz_unlocked_badges';

export function getUnlockedBadges(): string[] {
  try {
    const raw = localStorage.getItem(BADGES_KEY);
    return raw ? JSON.parse(raw) : ['time_hero']; // يبدأ دائماً بوسام ترحيبي
  } catch {
    return ['time_hero'];
  }
}

export function unlockBadge(badgeId: string): boolean {
  try {
    const current = getUnlockedBadges();
    if (!current.includes(badgeId)) {
      current.push(badgeId);
      localStorage.setItem(BADGES_KEY, JSON.stringify(current));
      soundEffects.playAchievementFanfare();
      return true;
    }
  } catch {}
  return false;
}
