// خدمة TMDB لجلب التقييم والبوستر وطاقم التمثيل الحقيقيين (اختيارية تماماً):
// بدون مفتاح VITE_TMDB_API_KEY في .env، كل دالة هنا ترجع null بأمان ولا تُغيّر
// أي سلوك حالي — لا أرقام أو صور وهمية أبداً عند غياب المفتاح.

export interface TmdbCastMember {
  name: string;
  character?: string;
  photoUrl: string | null;
}

export interface TmdbMatch {
  rating: number | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  cast: TmdbCastMember[];
}

const TMDB_BASE = 'https://api.themoviedb.org/3';
const IMG_BASE = 'https://image.tmdb.org/t/p';

function getApiKey(): string | null {
  const key = import.meta.env.VITE_TMDB_API_KEY;
  return key && key.trim().length > 0 ? key.trim() : null;
}

// كاش محلي في الذاكرة لتفادي استدعاءات متكررة لنفس العنوان أثناء التمرير
const cache = new Map<string, { at: number; data: TmdbMatch | null }>();
const TTL = 60 * 60 * 1000; // ساعة واحدة

/** يبحث في TMDB عن فيلم أو مسلسل بالعنوان، ويرجّع التقييم والبوستر وأول 12 من طاقم التمثيل */
export async function searchTmdbByTitle(
  title: string,
  year: number | null | undefined,
  kind: 'movie' | 'series'
): Promise<TmdbMatch | null> {
  const apiKey = getApiKey();
  const cleanTitle = title?.trim();
  if (!apiKey || !cleanTitle) return null;

  const cacheKey = `${kind}:${cleanTitle.toLowerCase()}:${year || ''}`;
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.at < TTL) return hit.data;

  try {
    const endpoint = kind === 'series' ? 'tv' : 'movie';
    const yearParam = kind === 'series' ? 'first_air_date_year' : 'year';
    const params = new URLSearchParams({
      api_key: apiKey,
      query: cleanTitle,
      language: 'ar',
    });
    if (year) params.set(yearParam, String(year));

    const res = await fetch(`${TMDB_BASE}/search/${endpoint}?${params.toString()}`);
    if (!res.ok) return null;
    const json = await res.json();
    const result = json.results?.[0];
    if (!result) {
      cache.set(cacheKey, { at: Date.now(), data: null });
      return null;
    }

    let cast: TmdbCastMember[] = [];
    try {
      const creditsRes = await fetch(
        `${TMDB_BASE}/${endpoint}/${result.id}/credits?api_key=${apiKey}&language=ar`
      );
      if (creditsRes.ok) {
        const creditsJson = await creditsRes.json();
        cast = (creditsJson.cast || []).slice(0, 12).map((c: any) => ({
          name: c.name,
          character: c.character || undefined,
          photoUrl: c.profile_path ? `${IMG_BASE}/w185${c.profile_path}` : null,
        }));
      }
    } catch {
      // طاقم التمثيل اختياري — فشل جلبه لا يُسقط التقييم/البوستر
    }

    const match: TmdbMatch = {
      rating: typeof result.vote_average === 'number' ? Math.round(result.vote_average * 10) / 10 : null,
      posterUrl: result.poster_path ? `${IMG_BASE}/w500${result.poster_path}` : null,
      backdropUrl: result.backdrop_path ? `${IMG_BASE}/w780${result.backdrop_path}` : null,
      cast,
    };

    cache.set(cacheKey, { at: Date.now(), data: match });
    return match;
  } catch (err) {
    console.warn('TMDB lookup error:', err);
    return null;
  }
}
