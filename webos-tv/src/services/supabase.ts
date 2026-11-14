import { createClient } from '@supabase/supabase-js';
import type { Device, ContentItem, ChildProfile, LiveChannel, IptvSource, Category, PairingCode } from '../types';

// تُقرأ من ملف .env (انظر .env.example) بدل كتابتها مباشرة بالكود، لتسهيل
// تجربة بيئة Supabase مختلفة (تطوير/اختبار) دون تعديل الكود المصدري
export const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL || 'https://iwjefmrqvgwwrjsouqej.supabase.co';
export const SUPABASE_ANON_KEY =
  import.meta.env.VITE_SUPABASE_ANON_KEY || 'sb_publishable_GLaEG6sWX4jUQkULH68oeg_jlXPZgzY';
export const PARENT_SITE_URL = import.meta.env.VITE_PARENT_SITE_URL || 'https://tv.nafasoud.com';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  auth: {
    persistSession: true,
    storageKey: 'aman_webos_auth',
  },
  realtime: {
    params: {
      eventsPerSecond: 10,
    },
  },
});

export const APP_VERSION = '3.2.0-webos';

export async function bootstrapAuth(): Promise<void> {
  const { data: { session } } = await supabase.auth.getSession();
  if (!session) {
    const { error } = await supabase.auth.signInAnonymously();
    if (error) {
      console.error('Error signing in anonymously:', error);
      throw error;
    }
  }
}

export async function registerDevice(): Promise<Device> {
  const { data, error } = await supabase.rpc('register_device', {
    p_app_version: APP_VERSION,
  });
  if (error) throw error;
  return data as Device;
}

export async function fetchDevice(id: string): Promise<Device | null> {
  const { data, error } = await supabase
    .from('devices')
    .select('*')
    .eq('id', id)
    .single();
  if (error) return null;
  return data as Device;
}

export async function fetchPairingCode(): Promise<PairingCode> {
  const { data, error } = await supabase.rpc('new_pairing_code');
  if (error) throw error;
  const list = data as PairingCode[];
  if (!list || list.length === 0) throw new Error('No pairing code returned');
  return list[0];
}

export async function getEffectiveLibraryId(device: Device): Promise<string> {
  if (device.library_id) return device.library_id;
  if (!device.parent_id) return device.id;

  try {
    // إذا لم يكن للجهاز محتوى بعد، نبحث عن أجهزة الأب الأخرى لمشاركة مكتبتها تلقائياً
    const { data: parentDevices } = await supabase
      .from('devices')
      .select('id, name')
      .eq('parent_id', device.parent_id)
      .neq('id', device.id)
      .limit(5);

    if (parentDevices && parentDevices.length > 0) {
      for (const pd of parentDevices) {
        const { data: testItems } = await supabase
          .from('content_items')
          .select('id')
          .eq('device_id', pd.id)
          .limit(1);

        if (testItems && testItems.length > 0) {
          console.log(`Auto-using library from device ${pd.name || pd.id}`);
          return pd.id;
        }
      }
      return parentDevices[0].id;
    }
  } catch (err) {
    console.warn('getEffectiveLibraryId error:', err);
  }

  return device.id;
}

export function normalizeContentItem(it: any): ContentItem {
  return {
    ...it,
    profile_ids: Array.isArray(it.profile_ids) ? it.profile_ids : [],
    excluded_video_ids: Array.isArray(it.excluded_video_ids) ? it.excluded_video_ids : [],
    approved_video_ids: Array.isArray(it.approved_video_ids) ? it.approved_video_ids : [],
    genres: Array.isArray(it.genres) ? it.genres : [],
    tags: Array.isArray(it.tags) ? it.tags : [],
    cast_list: Array.isArray(it.cast_list) ? it.cast_list : [],
    educational: !!it.educational,
    follow: !!it.follow,
  };
}

export async function fetchContentItems(device?: Device): Promise<ContentItem[]> {
  // جلب المحتوى المتاح للجهاز عبر سياسات الأمان RLS المدمجة في Supabase
  const { data, error } = await supabase
    .from('content_items')
    .select('*')
    .order('position', { ascending: true });

  if (error) {
    console.warn('fetchContentItems error:', error);
  }

  if (data && data.length > 0) {
    return data.map(normalizeContentItem);
  }

  // كخطة بديلة: إذا كان هناك معرّفات مكتبة محددة
  if (device) {
    const libId = device.library_id || device.id;
    const ids = Array.from(new Set([libId, device.id])).filter(Boolean);
    const res = await supabase
      .from('content_items')
      .select('*')
      .in('device_id', ids)
      .order('position', { ascending: true });
    if (res.data && res.data.length > 0) {
      return res.data.map(normalizeContentItem);
    }
  }

  return [];
}

export async function fetchProfiles(): Promise<ChildProfile[]> {
  // لا نطلب عمود "pin" الحقيقي إطلاقاً — فقط "has_pin" المنطقي، حتى لا يصل
  // رمز الحماية كنص صريح لجهاز التلفزيون (انظر verifyProfilePin أدناه)
  const { data, error } = await supabase
    .from('child_profiles')
    .select('id, device_id, name, avatar, daily_limit_min, weekend_limit_min, position, theme, has_pin')
    .order('position', { ascending: true });
  if (error) {
    console.warn('fetchProfiles error:', error);
    return [];
  }
  return (data || []) as ChildProfile[];
}

export async function verifyProfilePin(profileId: string, pin: string): Promise<boolean> {
  try {
    const { data, error } = await supabase.rpc('device_check_profile_pin', {
      p_profile_id: profileId,
      p_pin: pin,
    });
    if (error) return false;
    return !!data;
  } catch {
    return false;
  }
}

export async function fetchCategories(): Promise<Category[]> {
  const { data, error } = await supabase
    .from('categories')
    .select('*')
    .order('position', { ascending: true });
  if (error) {
    console.warn('fetchCategories error:', error);
    return [];
  }
  return (data || []) as Category[];
}

export async function fetchChannels(): Promise<LiveChannel[]> {
  const { data, error } = await supabase
    .from('live_channels')
    .select('*')
    .eq('enabled', true)
    .order('position', { ascending: true });
  if (error) return [];
  return (data || []) as LiveChannel[];
}

export async function fetchSources(): Promise<IptvSource[]> {
  const { data, error } = await supabase
    .from('iptv_sources')
    .select('*');
  if (error) return [];
  return (data || []) as IptvSource[];
}

export async function sendHeartbeat(): Promise<void> {
  try {
    await supabase.rpc('device_heartbeat', {
      p_app_version: APP_VERSION,
    });
  } catch (err) {
    console.warn('heartbeat error:', err);
  }
}

export async function verifySettingsPin(pin: string): Promise<boolean> {
  try {
    const { data, error } = await supabase.rpc('device_check_pin', {
      p_pin: pin,
    });
    if (error) return false;
    return !!data;
  } catch {
    return false;
  }
}

export async function deviceParentAction(
  pin: string,
  action: 'bonus' | 'sleep' | 'sleep_cancel',
  minutes: number = 0
): Promise<boolean> {
  try {
    const today = new Date().toISOString().split('T')[0];
    const { error } = await supabase.rpc('device_parent_action', {
      p_pin: pin,
      p_action: action,
      p_minutes: minutes,
      p_day: today,
    });
    return !error;
  } catch (err) {
    console.warn('deviceParentAction error:', err);
    return false;
  }
}

export async function updateDeviceSettings(
  pin: string,
  saveWatched: boolean,
  autoDownload: boolean,
  quality: number,
  volumeId: string | null = null
): Promise<boolean> {
  try {
    const { error } = await supabase.rpc('device_update_settings', {
      p_pin: pin,
      p_save_watched: saveWatched,
      p_auto_download: autoDownload,
      p_download_quality: quality,
      p_storage_volume: volumeId,
    });
    return !error;
  } catch (err) {
    console.warn('updateDeviceSettings error:', err);
    return false;
  }
}

export async function checkServerPing(): Promise<number> {
  const start = Date.now();
  try {
    await supabase.from('devices').select('id').limit(1);
    return Date.now() - start;
  } catch {
    return -1;
  }
}

export function subscribeToDeviceChanges(
  deviceId: string,
  _libraryId: string,
  onRefresh: () => void,
  onDeviceUpdate: (device: Device) => void
): () => void {
  // اسم فريد للقناة لمنع تكرار الاشتراك
  const channelName = `device-${deviceId}-${Date.now()}`;
  const channel = supabase.channel(channelName);

  channel
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'devices', filter: `id=eq.${deviceId}` },
      (payload) => {
        if (payload.new) {
          onDeviceUpdate(payload.new as Device);
        }
      }
    )
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'content_items' },
      () => onRefresh()
    )
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'child_profiles' },
      () => onRefresh()
    )
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'categories' },
      () => onRefresh()
    )
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'live_channels' },
      () => onRefresh()
    )
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'iptv_sources' },
      () => onRefresh()
    )
    .subscribe();

  return () => {
    supabase.removeChannel(channel);
  };
}
