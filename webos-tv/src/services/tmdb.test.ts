import { describe, it, expect, vi, beforeEach } from 'vitest';
import { searchTmdbByTitle } from './tmdb';

describe('searchTmdbByTitle', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('returns null and makes no network call when VITE_TMDB_API_KEY is unset (safe default)', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch');
    const result = await searchTmdbByTitle('أي فيلم', 2026, 'movie');
    expect(result).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('returns null for an empty title without calling fetch', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch');
    const result = await searchTmdbByTitle('   ', null, 'series');
    expect(result).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
