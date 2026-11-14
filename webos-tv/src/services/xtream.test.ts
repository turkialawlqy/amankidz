import { describe, it, expect } from 'vitest';
import { isAdult, parseXtream, formatEpisodeTitle } from './xtream';
import type { IptvSource } from '../types';

function makeSource(overrides: Partial<IptvSource> = {}): IptvSource {
  return {
    id: 'src1',
    kind: 'xtream',
    name: 'Test Source',
    url: 'http://example.com:8080',
    auto_library: false,
    hidden_cats: [],
    hidden_items: [],
    profile_ids: [],
    ...overrides,
  };
}

describe('isAdult', () => {
  it('flags obviously adult category/channel names', () => {
    expect(isAdult('XXX Movies')).toBe(true);
    expect(isAdult('Adult+18')).toBe(true);
    expect(isAdult('قنوات للكبار فقط')).toBe(true);
  });

  it('does not flag normal kids/family content', () => {
    expect(isAdult('Cartoons for Kids')).toBe(false);
    expect(isAdult('قناة الأطفال')).toBe(false);
    expect(isAdult('Documentary Channel')).toBe(false);
  });
});

describe('parseXtream', () => {
  it('reads credentials directly from an xtream-kind source', () => {
    const src = makeSource({
      kind: 'xtream',
      url: 'http://host.example:8080/',
      username: 'user1',
      password: 'pass1',
    });
    expect(parseXtream(src)).toEqual({
      base: 'http://host.example:8080',
      user: 'user1',
      pass: 'pass1',
    });
  });

  it('extracts credentials from a get.php-style M3U URL when kind/username are missing', () => {
    const src = makeSource({
      kind: 'm3u',
      username: null,
      password: null,
      url: 'http://host.example:8080/get.php?username=foo&password=bar&type=m3u_plus',
    });
    expect(parseXtream(src)).toEqual({
      base: 'http://host.example:8080',
      user: 'foo',
      pass: 'bar',
    });
  });

  it('returns null when no credentials can be found anywhere', () => {
    const src = makeSource({
      kind: 'm3u',
      username: null,
      password: null,
      url: 'http://host.example/playlist.m3u',
    });
    expect(parseXtream(src)).toBeNull();
  });

  it('returns null for a malformed URL instead of throwing', () => {
    const src = makeSource({ kind: 'm3u', username: null, password: null, url: 'not a url' });
    expect(parseXtream(src)).toBeNull();
  });
});

describe('formatEpisodeTitle', () => {
  it('falls back to a plain episode label when there is no raw title', () => {
    expect(formatEpisodeTitle(3)).toBe('الحلقة 3');
  });

  it('falls back to a plain episode label when the raw title is just the episode number', () => {
    expect(formatEpisodeTitle(5, 'Episode 5')).toBe('الحلقة 5');
    expect(formatEpisodeTitle(5, 'E05')).toBe('الحلقة 5');
    expect(formatEpisodeTitle(5, '05')).toBe('الحلقة 5');
    expect(formatEpisodeTitle(5, 'حلقة 5')).toBe('الحلقة 5');
  });

  it('strips a redundant leading episode marker and keeps the real title', () => {
    expect(formatEpisodeTitle(2, 'Episode 2 - The Great Escape')).toBe('الحلقة 2: The Great Escape');
    expect(formatEpisodeTitle(2, 'E02: The Great Escape')).toBe('الحلقة 2: The Great Escape');
    expect(formatEpisodeTitle(2, '2 - The Great Escape')).toBe('الحلقة 2: The Great Escape');
  });

  it('keeps a descriptive title untouched aside from the prefix', () => {
    expect(formatEpisodeTitle(1, 'مغامرة في الغابة')).toBe('الحلقة 1: مغامرة في الغابة');
  });
});
