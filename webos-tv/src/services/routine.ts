/**
 * خدمة الروتين اليومي وجدول المذاكرة والصلاة الذكي
 * Smart Kids Daily Routine & Schedule
 */

export interface RoutineConfig {
  studyLockEnabled: boolean;
  studyStartHour: number; // e.g. 16 (4:00 PM)
  studyEndHour: number;   // e.g. 18 (6:00 PM)
  bedtimeHour: number;    // e.g. 21 (9:00 PM)
  prayerPauseEnabled: boolean;
}

const ROUTINE_KEY = 'amankidz_daily_routine_config';

export const DEFAULT_ROUTINE: RoutineConfig = {
  studyLockEnabled: true,
  studyStartHour: 16, // من 4 عصراً
  studyEndHour: 18,   // إلى 6 مساءً
  bedtimeHour: 21,    // 9 ليلاً
  prayerPauseEnabled: true,
};

export function getRoutineConfig(): RoutineConfig {
  try {
    const raw = localStorage.getItem(ROUTINE_KEY);
    return raw ? { ...DEFAULT_ROUTINE, ...JSON.parse(raw) } : DEFAULT_ROUTINE;
  } catch {
    return DEFAULT_ROUTINE;
  }
}

export function saveRoutineConfig(config: RoutineConfig): void {
  try {
    localStorage.setItem(ROUTINE_KEY, JSON.stringify(config));
  } catch (e) {
    console.error('Failed to save routine config', e);
  }
}

export interface RoutineStatus {
  isLocked: boolean;
  reason: 'study' | 'bedtime' | 'prayer' | 'none';
  message: string;
  icon: string;
  remainingMinutes?: number;
}

export function checkCurrentRoutine(): RoutineStatus {
  const config = getRoutineConfig();
  const now = new Date();
  const currentHour = now.getHours();
  const currentMinute = now.getMinutes();

  // فحص وقت المذاكرة
  if (config.studyLockEnabled) {
    if (currentHour >= config.studyStartHour && currentHour < config.studyEndHour) {
      const minutesLeft = (config.studyEndHour - currentHour) * 60 - currentMinute;
      return {
        isLocked: true,
        reason: 'study',
        message: 'وقت المذاكرة والواجبات المدرسية يا بطل! 📚✏️',
        icon: '🎒',
        remainingMinutes: minutesLeft,
      };
    }
  }

  // فحص وقت النوم
  if (currentHour >= config.bedtimeHour || currentHour < 6) {
    return {
      isLocked: true,
      reason: 'bedtime',
      message: 'حان وقت النوم والراحة لأحلام سعيدة! 🌙⭐',
      icon: '🛌',
    };
  }

  return {
    isLocked: false,
    reason: 'none',
    message: '',
    icon: '',
  };
}
