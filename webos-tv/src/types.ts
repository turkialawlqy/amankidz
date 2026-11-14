export interface Device {
  id: string;
  parent_id?: string | null;
  name: string;
  device_code: string;
  locked: boolean;
  save_watched: boolean;
  auto_download: boolean;
  download_quality: number;
  collection_download_limit: number;
  settings_pin?: string | null;
  daily_limit_min?: number | null;
  allowed_from?: string | null;
  allowed_to?: string | null;
  bonus_minutes: number;
  bonus_day?: string | null;
  sleep_at?: string | null;
  play_request?: PlayRequest | null;
  search_enabled: boolean;
  weekend_days: number[];
  weekend_limit_min?: number | null;
  weekend_from?: string | null;
  weekend_to?: string | null;
  tv_message?: TvMessage | null;
  remote_cmd?: RemoteCmd | null;
  blocked_words: string[];
  theme?: string | null;
  library_id?: string | null;
}

export interface TvMessage {
  id: string;
  text: string;
  at: number;
  from?: string | null;
}

export interface RemoteCmd {
  id: string;
  cmd: string;
  at: number;
}

export interface PlayRequest {
  video_id: string;
  item_id?: string | null;
  title: string;
  at: string;
}

export interface CastMember {
  id?: number | null;
  name: string;
  character?: string | null;
  photo?: string | null;
}

export interface ContentItem {
  id: string;
  device_id: string;
  kind: 'video' | 'playlist' | 'channel' | 'mix';
  youtube_id: string;
  url: string;
  title: string;
  thumbnail_url?: string | null;
  position: number;
  offline: boolean;
  parent_id?: string | null;
  excluded_video_ids: string[];
  profile_ids: string[];
  require_approval: boolean;
  approved_video_ids: string[];
  educational: boolean;
  intro_start?: number | null;
  intro_end?: number | null;
  outro_start?: number | null;
  outro_len?: number | null;
  category_id?: string | null;
  description?: string | null;
  year?: number | null;
  genres: string[];
  tags: string[];
  backdrop_url?: string | null;
  poster_url?: string | null;
  cast_list: CastMember[];
  season?: number | null;
  follow: boolean;
}

export interface ChildProfile {
  id: string;
  device_id: string;
  name: string;
  avatar: string;
  daily_limit_min?: number | null;
  weekend_limit_min?: number | null;
  position: number;
  // لا نجلب رمز الحماية الفعلي (pin) من الخادم أبداً — فقط علامة منطقية تخبرنا
  // إن كان الملف محمياً برمز أم لا؛ التحقق الفعلي يتم عبر verifyProfilePin()
  has_pin?: boolean | null;
  theme?: string | null;
}

export interface LiveChannel {
  id: string;
  device_id: string;
  source_id?: string | null;
  name: string;
  logo?: string | null;
  stream_url: string;
  group_name?: string | null;
  epg_id?: string | null;
  position: number;
  enabled: boolean;
  profile_ids: string[];
}

export interface IptvSource {
  id: string;
  kind: string;
  name: string;
  url: string;
  username?: string | null;
  password?: string | null;
  epg_url?: string | null;
  auto_library: boolean;
  hidden_cats: string[];
  hidden_items: string[];
  profile_ids: string[];
}

export interface Category {
  id: string;
  device_id: string;
  name: string;
  icon: string;
  position: number;
}

export interface PairingCode {
  code: string;
  expires_at: string;
}

export type TvStateType =
  | { type: 'loading'; text?: string }
  | { type: 'error'; message: string }
  | { type: 'pairing'; deviceCode: string; code: string; expiresAtMs: number }
  | {
      type: 'ready';
      device: Device;
      items: ContentItem[];
      profiles: ChildProfile[];
      categories: Category[];
      channels: LiveChannel[];
      iptv: IptvSource[];
    };
