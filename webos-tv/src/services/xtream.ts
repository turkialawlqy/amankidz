import type { IptvSource, LiveChannel, ContentItem } from '../types';

export interface XtreamCredentials {
  base: string;
  user: string;
  pass: string;
}

export interface XtreamCategory {
  category_id: string;
  category_name: string;
  parent_id?: number;
}

export interface XtreamStream {
  num?: number;
  name: string;
  stream_type?: string;
  stream_id: string | number;
  stream_icon?: string;
  epg_channel_id?: string;
  added?: string;
  category_id?: string;
  rating?: string;
  container_extension?: string;
  series_id?: string | number;
  cover?: string;
  plot?: string;
  cast?: string;
  releaseDate?: string;
  backdrop_path?: string[];
}

export interface XtreamEpisode {
  id: string | number;
  episode_num: number;
  title: string;
  container_extension?: string;
  season?: number;
  info?: {
    plot?: string;
    movie_image?: string;
    duration_secs?: number;
    duration?: string;
  };
}

export interface XtreamSeriesDetails {
  info?: {
    name?: string;
    cover?: string;
    plot?: string;
    cast?: string;
    genre?: string;
    releaseDate?: string;
    rating?: string;
    backdrop_path?: string[];
  };
  episodes?: Record<string, XtreamEpisode[]> | XtreamEpisode[][];
}

const ADULT_REGEX = /(xxx|adult|\+ ?18|18 ?\+|porn|sexy|erotic|للكبار|كبار فقط|\bvod 18)/i;

export function isAdult(name: string): boolean {
  return ADULT_REGEX.test(name);
}

export function parseXtream(src: IptvSource): XtreamCredentials | null {
  if (src.kind === 'xtream' && src.username && src.password) {
    return {
      base: src.url.replace(/\/+$/, ''),
      user: src.username,
      pass: src.password,
    };
  }

  try {
    const url = new URL(src.url);
    const user = url.searchParams.get('username');
    const pass = url.searchParams.get('password');
    if (user && pass) {
      // url.host يتضمن المنفذ (port) أصلاً إن وُجد، فلا داعي لإضافته مرة أخرى
      // (كان هذا يسبب عنواناً مكرراً للمنفذ مثل host:8080:8080 ويفشل الاتصال)
      return {
        base: `${url.protocol}//${url.host}`,
        user,
        pass,
      };
    }
  } catch {}

  return null;
}

// كاش محلي في الذاكرة لتسريع التنقل بالريموت
const cache = new Map<string, { at: number; data: any }>();
const TTL = 10 * 60 * 1000; // 10 دقائق

async function fetchXtreamApi<T>(src: IptvSource, action: string, params: Record<string, string> = {}): Promise<T | null> {
  const creds = parseXtream(src);
  if (!creds) return null;

  const query = new URLSearchParams({
    username: creds.user,
    password: creds.pass,
    action,
    ...params,
  });

  const cacheKey = `${src.id}:${action}:${query.toString()}`;
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.at < TTL) {
    return hit.data as T;
  }

  try {
    const targetUrl = `${creds.base}/player_api.php?${query.toString()}`;
    const res = await fetch(targetUrl);
    if (!res.ok) return null;
    const json = await res.json();
    cache.set(cacheKey, { at: Date.now(), data: json });
    return json as T;
  } catch (err) {
    console.warn(`Xtream API ${action} error:`, err);
    return null;
  }
}

/** جلب تصنيفات القنوات أو الأفلام أو المسلسلات */
export async function getXtreamCategories(
  src: IptvSource,
  type: 'live' | 'vod' | 'series'
): Promise<XtreamCategory[]> {
  const action =
    type === 'live'
      ? 'get_live_categories'
      : type === 'vod'
      ? 'get_vod_categories'
      : 'get_series_categories';

  const data = await fetchXtreamApi<XtreamCategory[]>(src, action);
  if (!Array.isArray(data)) return [];

  // فلترة تصنيفات الكبار والتصنيفات المخفية من الأهل
  return data.filter((c) => {
    if (!c.category_name) return false;
    if (isAdult(c.category_name)) return false;
    if (src.hidden_cats?.includes(`${type}:${c.category_id}`)) return false;
    return true;
  });
}

/** جلب عناصر تصنيف معين (قنوات، أفلام، مسلسلات) */
export async function getXtreamStreams(
  src: IptvSource,
  type: 'live' | 'vod' | 'series',
  categoryId?: string
): Promise<XtreamStream[]> {
  const action =
    type === 'live'
      ? 'get_live_streams'
      : type === 'vod'
      ? 'get_vod_streams'
      : 'get_series';

  const params: Record<string, string> = {};
  if (categoryId && categoryId !== '__all__') {
    params.category_id = categoryId;
  }

  const data = await fetchXtreamApi<XtreamStream[]>(src, action, params);
  if (!Array.isArray(data)) return [];

  return data.filter((s) => {
    if (!s.name) return false;
    if (isAdult(s.name)) return false;
    const id = String(s.series_id || s.stream_id);
    if (src.hidden_items?.includes(`${type}:${id}`)) return false;
    return true;
  });
}

/** جلب أحدث الأفلام والمسلسلات لعرضها في الصفحة الرئيسية فوراً */
export async function getXtreamLatest(
  sources: IptvSource[],
  type: 'vod' | 'series',
  limit = 20
): Promise<XtreamStream[]> {
  for (const src of sources) {
    const creds = parseXtream(src);
    if (!creds) continue;

    // نجلب أول 3 تصنيفات (غالباً أول تصنيف هو "الجديد / أحدث الإضافات")
    const cats = await getXtreamCategories(src, type);
    const topCats = cats.slice(0, 3);

    const results: XtreamStream[] = [];
    for (const cat of topCats) {
      const streams = await getXtreamStreams(src, type, cat.category_id);
      results.push(...streams.slice(0, 10));
      if (results.length >= limit) break;
    }

    if (results.length > 0) {
      return results.slice(0, limit);
    }
  }
  return [];
}

/** جلب قنوات البث المباشر لأول تصنيف في الاشتراك لتعبئة صف القنوات */
export async function getXtreamLiveChannels(
  sources: IptvSource[],
  limit = 25
): Promise<LiveChannel[]> {
  for (const src of sources) {
    const creds = parseXtream(src);
    if (!creds) continue;

    const cats = await getXtreamCategories(src, 'live');
    if (cats.length === 0) continue;

    const firstCat = cats[0];
    const streams = await getXtreamStreams(src, 'live', firstCat.category_id);

    return streams.slice(0, limit).map((s) => {
      const streamId = String(s.stream_id);
      const streamUrl = `${creds.base}/live/${creds.user}/${creds.pass}/${streamId}.m3u8`;
      return {
        id: `xl:${src.id}:${streamId}`,
        device_id: src.id,
        source_id: src.id,
        name: s.name,
        logo: s.stream_icon || null,
        stream_url: streamUrl,
        group_name: firstCat.category_name,
        epg_id: s.epg_channel_id || null,
        position: s.num || 0,
        enabled: true,
        profile_ids: [],
      };
    });
  }
  return [];
}

/** جلب تفاصيل المسلسل وحلقاته */
export async function getXtreamSeriesDetails(
  src: IptvSource,
  seriesId: string | number
): Promise<{ info: any; episodes: ContentItem[] }> {
  const creds = parseXtream(src);
  if (!creds) return { info: null, episodes: [] };

  const data = await fetchXtreamApi<XtreamSeriesDetails>(src, 'get_series_info', {
    series_id: String(seriesId),
  });

  if (!data) return { info: null, episodes: [] };

  const epsList: ContentItem[] = [];
  const rawEpisodes = data.episodes;

  if (rawEpisodes) {
    if (Array.isArray(rawEpisodes)) {
      rawEpisodes.forEach((seasonList, sIdx) => {
        if (Array.isArray(seasonList)) {
          seasonList.forEach((ep) => {
            epsList.push(convertXtreamEpisode(src, creds, ep, sIdx + 1, seriesId));
          });
        }
      });
    } else if (typeof rawEpisodes === 'object') {
      Object.entries(rawEpisodes).forEach(([seasonNum, seasonList]) => {
        if (Array.isArray(seasonList)) {
          seasonList.forEach((ep) => {
            epsList.push(convertXtreamEpisode(src, creds, ep, Number(seasonNum) || 1, seriesId));
          });
        }
      });
    }
  }

  return {
    info: data.info,
    episodes: epsList,
  };
}

/** تنظيف وتنسيق عنوان الحلقة ومنع تكرار رقم الحلقة */
export function formatEpisodeTitle(epNum: number, rawTitle?: string): string {
  if (!rawTitle) return `الحلقة ${epNum}`;
  let t = rawTitle.trim();

  // إذا كان العنوان فقط مثل "Episode 1" أو "Ep 1" أو "حلقة 1" أو "E01" أو مجرد رقم
  if (/^(episode|ep|حلقة|e)?\s*0*(\d+)$/i.test(t)) {
    return `الحلقة ${epNum}`;
  }

  // إزالة تكرار "Episode 1 -" أو "حلقة 1 -" أو "E01 -" من بداية العنوان
  t = t.replace(new RegExp('^(episode|ep|حلقة|e)\\s*0*\\d+\\s*[-:–|.]\\s*', 'i'), '').trim();

  // إزالة رقم البداية إذا كان مثل "1 - عنوان"
  t = t.replace(new RegExp('^\\d+\\s*[-:–|.]\\s*'), '').trim();

  if (!t || t.toLowerCase() === `episode ${epNum}` || t === `حلقة ${epNum}` || t === String(epNum)) {
    return `الحلقة ${epNum}`;
  }

  return `الحلقة ${epNum}: ${t}`;
}

function convertXtreamEpisode(
  src: IptvSource,
  creds: XtreamCredentials,
  ep: XtreamEpisode,
  seasonNum: number,
  seriesId: string | number
): ContentItem {
  const epId = String(ep.id);
  const ext = ep.container_extension || 'mp4';
  const url = `${creds.base}/series/${creds.user}/${creds.pass}/${epId}.${ext}`;

  return {
    id: `xe:${src.id}:${epId}`,
    device_id: src.id,
    kind: 'video',
    youtube_id: `xt_e_${epId}`,
    url,
    title: formatEpisodeTitle(ep.episode_num, ep.title),
    thumbnail_url: ep.info?.movie_image || null,
    position: ep.episode_num,
    offline: false,
    parent_id: `xs:${src.id}:${seriesId}`,
    excluded_video_ids: [],
    profile_ids: [],
    require_approval: false,
    approved_video_ids: [],
    educational: false,
    description: ep.info?.plot || null,
    season: seasonNum,
    genres: [],
    tags: [],
    cast_list: [],
    follow: false,
  };
}

/** بحث في اشتراك Xtream بالاسم */
export async function searchXtream(
  sources: IptvSource[],
  query: string
): Promise<{ live: LiveChannel[]; vod: ContentItem[]; series: ContentItem[] }> {
  const q = query.trim().toLowerCase();
  if (!q) return { live: [], vod: [], series: [] };

  const results: { live: LiveChannel[]; vod: ContentItem[]; series: ContentItem[] } = {
    live: [],
    vod: [],
    series: [],
  };

  for (const src of sources) {
    const creds = parseXtream(src);
    if (!creds) continue;

    // بحث متوازي في القنوات والأفلام والمسلسلات
    const [liveStreams, vodStreams, seriesStreams] = await Promise.all([
      getXtreamStreams(src, 'live'),
      getXtreamStreams(src, 'vod'),
      getXtreamStreams(src, 'series'),
    ]);

    results.live.push(
      ...liveStreams
        .filter((s) => s.name?.toLowerCase().includes(q))
        .slice(0, 15)
        .map((s) => {
          const sid = String(s.stream_id);
          return {
            id: `xl:${src.id}:${sid}`,
            device_id: src.id,
            source_id: src.id,
            name: s.name,
            logo: s.stream_icon || null,
            stream_url: `${creds.base}/live/${creds.user}/${creds.pass}/${sid}.m3u8`,
            position: s.num || 0,
            enabled: true,
            profile_ids: [],
          };
        })
    );

    results.vod.push(
      ...vodStreams
        .filter((s) => s.name?.toLowerCase().includes(q))
        .slice(0, 15)
        .map((s) => streamToContentItem(src, s, 'vod'))
    );

    results.series.push(
      ...seriesStreams
        .filter((s) => s.name?.toLowerCase().includes(q))
        .slice(0, 15)
        .map((s) => streamToContentItem(src, s, 'series'))
    );
  }

  return results;
}
export function streamToContentItem(
  src: IptvSource,
  stream: XtreamStream,
  type: 'vod' | 'series'
): ContentItem {
  const creds = parseXtream(src)!;
  const id = String(stream.series_id || stream.stream_id);
  const ext = stream.container_extension || 'mp4';

  if (type === 'vod') {
    const url = `${creds.base}/movie/${creds.user}/${creds.pass}/${id}.${ext}`;
    return {
      id: `xv:${src.id}:${id}`,
      device_id: src.id,
      kind: 'video',
      youtube_id: `xt_m_${id}`,
      url,
      title: stream.name,
      thumbnail_url: stream.stream_icon || null,
      position: stream.num || 0,
      offline: false,
      excluded_video_ids: [],
      profile_ids: [],
      require_approval: false,
      approved_video_ids: [],
      educational: false,
      description: stream.plot || null,
      year: stream.releaseDate ? parseInt(stream.releaseDate.substring(0, 4), 10) : undefined,
      genres: [],
      tags: [],
      cast_list: [],
      follow: false,
    };
  } else {
    // مسلسل
    return {
      id: `xs:${src.id}:${id}`,
      device_id: src.id,
      kind: 'mix',
      youtube_id: `xt_s_${id}`,
      url: '',
      title: stream.name,
      thumbnail_url: stream.cover || null,
      position: stream.num || 0,
      offline: false,
      excluded_video_ids: [],
      profile_ids: [],
      require_approval: false,
      approved_video_ids: [],
      educational: false,
      description: stream.plot || null,
      year: stream.releaseDate ? parseInt(stream.releaseDate.substring(0, 4), 10) : undefined,
      genres: [],
      tags: [],
      cast_list: [],
      follow: false,
    };
  }
}
