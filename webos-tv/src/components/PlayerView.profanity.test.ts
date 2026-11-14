import { describe, it, expect } from 'vitest';
import { subtitleHasBlockedWord } from './PlayerView';

describe('subtitleHasBlockedWord', () => {
  it('matches a blocked word that appears as its own standalone word', () => {
    expect(subtitleHasBlockedWord('لا تقل هذه الكلمة أبداً', ['الكلمة'])).toBe(true);
    expect(subtitleHasBlockedWord('Do not say that word', ['word'])).toBe(true);
  });

  it('does NOT false-positive when the blocked word is only a substring of another word', () => {
    // "قتل" (kill) يظهر هنا داخل كلمة "مقتلع" (مختلفة تماماً بالمعنى) وليس ككلمة مستقلة
    expect(subtitleHasBlockedWord('الشجرة مقتلعة من جذورها', ['قتل'])).toBe(false);
    // "cat" لا يجب أن يطابق "category" أو "concatenate"
    expect(subtitleHasBlockedWord('Please pick a category', ['cat'])).toBe(false);
    expect(subtitleHasBlockedWord('We need to concatenate the strings', ['cat'])).toBe(false);
  });

  it('is case-insensitive and ignores surrounding punctuation', () => {
    expect(subtitleHasBlockedWord('Hello, WORD!', ['word'])).toBe(true);
    expect(subtitleHasBlockedWord('«كلمة» جميلة', ['كلمة'])).toBe(true);
  });

  it('returns false when there is no blocked-word list or no match', () => {
    expect(subtitleHasBlockedWord('نص عادي تماماً', [])).toBe(false);
    expect(subtitleHasBlockedWord('نص عادي تماماً', ['غير موجود'])).toBe(false);
  });
});
