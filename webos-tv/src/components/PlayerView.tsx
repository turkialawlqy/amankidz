import React, { useEffect, useRef, useState, useCallback } from 'react';
import { AtvSpinner } from './AtvSpinner';
import { AtvToast } from './AtvAlert';
import Hls from 'hls.js';
import type { ContentItem, Device, LiveChannel } from '../types';
import { WEBOS_KEYS, navigateSpatial, shouldHandleBackPress } from '../hooks/useRemoteKeys';
import { getStartPosition, recordWatchProgress } from '../services/resume';
import { unlockBadge } from '../services/badges';

interface PlayerViewProps {
  item: ContentItem;
  playlist?: ContentItem[];
  liveChannels?: LiveChannel[];
  device: Device;
  activeProfileId?: string | null;
  remainingMinutes?: number | null;
  onClose: () => void;
  onNext?: () => void;
  onPrevious?: () => void;
  onSelectChannel?: (ch: LiveChannel) => void;
  onSelectEpisode?: (ep: ContentItem) => void;
}

function extractYouTubeId(url: string, id?: string): string | null {
  if (id && id.length === 11 && !id.startsWith('xt') && !id.startsWith('tg_') && !id.startsWith('up_')) {
    return id;
  }
  const match = url.match(/(?:youtu\.be\/|youtube\.com\/(?:embed\/|v\/|watch\?v=|watch\?.+&v=))([\w-]{11})/);
  return match ? match[1] : null;
}

// نقسّم على أي شيء ليس حرفاً أو رقماً (بأي لغة)، بدل سرد علامات ترقيم محددة
// يدوياً — يدعم هذا تلقائياً كل علامات الترقيم العربية والإنجليزية وغيرها
const WORD_SPLIT_REGEX = /[^\p{L}\p{N}]+/u;

// فحص "كلمة كاملة" مطابقة تماماً وليس مجرد جزء من كلمة أخرى — النص الأصلي كان
// يستخدم includes() البسيطة، فأي كلمة محظورة قصيرة (شائع جداً بالعربية لتشابه
// الجذور) كانت تُطابق بالخطأ داخل كلمات حوار عادية بريئة وتُسكت الصوت 3.5 ثانية
// فجأة وبلا سبب ظاهر للمشاهد
export function subtitleHasBlockedWord(rawText: string, blockedWords: string[]): boolean {
  if (!blockedWords || blockedWords.length === 0) return false;
  const tokens = rawText.toLowerCase().split(WORD_SPLIT_REGEX).filter(Boolean);
  const tokenSet = new Set(tokens);
  return blockedWords.some((w) => w && tokenSet.has(w.toLowerCase()));
}

export const PlayerView: React.FC<PlayerViewProps> = ({
  item,
  playlist = [],
  liveChannels = [],
  activeProfileId,
  remainingMinutes,
  device,
  onClose,
  onNext,
  onPrevious,
  onSelectChannel,
  onSelectEpisode,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const topBarRef = useRef<HTMLDivElement>(null);
  const bottomBarRef = useRef<HTMLDivElement>(null);
  const playPauseRef = useRef<HTMLButtonElement>(null);

  const [isPlaying, setIsPlaying] = useState(true);
  const [isBuffering, setIsBuffering] = useState(true);
  // أثناء شاشة الانتظار الأولى يكون شريط التحكم مخفياً بصرياً
  const initialLoadingRef = useRef(true);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [showControls, setShowControls] = useState(true);
  const [showSkipIntro, setShowSkipIntro] = useState(false);
  const [nextCountdown, setNextCountdown] = useState<number | null>(null);
  const [showChannelDrawer, setShowChannelDrawer] = useState(false);
  const [showSettingsDrawer, setShowSettingsDrawer] = useState(false);
  const [showEpisodesDrawer, setShowEpisodesDrawer] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [retryCount, setRetryCount] = useState(0);
  const [isReconnecting, setIsReconnecting] = useState(false);
  const [reconnectToast, setReconnectToast] = useState<string | null>(null);

  // ميزات جديدة متقدمة
  const [isLocked, setIsLocked] = useState(false);
  const [lockToast, setLockToast] = useState<string | null>(null);
  const lastBackPressRef = useRef<number>(0);

  const [isProfanityMuted, setIsProfanityMuted] = useState(false);
  const [dismissBedtime, setDismissBedtime] = useState(false);

  // وضع الاستماع وإطفاء الشاشة لقصص النوم (Screen-Off Bedtime Audio)
  const [isScreenOffMode, setIsScreenOffMode] = useState(false);

  // معادل وضوح صوت الحوار وضغط الصوت الليلي (Speech Clarity & Dynamic EQ)
  const [speechEnhanceMode, setSpeechEnhanceMode] = useState(() => {
    return localStorage.getItem('amankidz_speech_enhance') === 'true';
  });
  const [nightAudioMode, setNightAudioMode] = useState(() => {
    return localStorage.getItem('amankidz_night_audio') === 'true';
  });
  const audioCtxRef = useRef<AudioContext | null>(null);
  const biquadFilterRef = useRef<BiquadFilterNode | null>(null);
  const compressorRef = useRef<DynamicsCompressorNode | null>(null);

  // ميزة 4: المفضلة السريعة المباشرة
  const [isFavorite, setIsFavorite] = useState(() => {
    try {
      const raw = localStorage.getItem('amankidz_favorites');
      const list = raw ? JSON.parse(raw) : [];
      return list.some((f: any) => f.id === item.id);
    } catch {
      return false;
    }
  });
  const [favToast, setFavToast] = useState<string | null>(null);

  // الوضع الليلي الدافئ لحماية العين بعد الساعة 8 مساءً
  const [nightMode, setNightMode] = useState(() => {
    const h = new Date().getHours();
    return h >= 20 || h < 6;
  });
  // --- ميزات IPTV الاحترافية الستة المتقدمة ---
  // 1. شاشة الفحص والتشخيص الفني المباشر (Diagnostics HUD)
  const [showDiagnosticsHud, setShowDiagnosticsHud] = useState(false);
  const [hudStats, setHudStats] = useState({
    resolution: 'جاري القراءة...',
    fps: 0,
    bitrateMbps: '0.00',
    bufferSec: '0.0',
    droppedFrames: 0,
    streamProtocol: 'HLS Live',
    videoCodec: 'H.264 (AVC)',
  });

  // 2. وضع النافذة العائمة المصغرة (PiP Preview)
  const [isPipMode, setIsPipMode] = useState(false);

  // 3. التبديل الرقمي السريع للقنوات بالريموت 0..9 (Numeric Channel Zapping)
  const [channelNumberInput, setChannelNumberInput] = useState('');
  const [channelZappingToast, setChannelZappingToast] = useState<string | null>(null);
  const zappingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const channelNumberInputRef = useRef('');
  channelNumberInputRef.current = channelNumberInput;

  // 4. إشعار تحديد الصوت العربي التلقائي
  const [audioToast, setAudioToast] = useState<string | null>(null);

  // 5. محاولات التبديل التلقائي للبث البديل (Stream Failover)
  const failoverAttemptRef = useRef(0);


  // وضع التعلم بالترجمة المزدوجة
  const [bilingualMode, setBilingualMode] = useState(false);
  const [activeSubtitleText, setActiveSubtitleText] = useState<string | null>(null);

  // إعدادات الصوت والترجمة والجودة والسرعة
  const [audioTracks, setAudioTracks] = useState<Array<{ id: number; name: string; lang: string }>>([]);
  const [selectedAudioTrack, setSelectedAudioTrack] = useState<number>(-1);
  const [subtitleTracks, setSubtitleTracks] = useState<Array<{ id: number; name: string; lang: string }>>([]);
  const [selectedSubtitleTrack, setSelectedSubtitleTrack] = useState<number>(-1);
  const [qualityLevels, setQualityLevels] = useState<Array<{ id: number; height: number; bitrate: number }>>([]);
  const [selectedQuality, setSelectedQuality] = useState<number>(-1);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1.0);

  const controlsTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastPositionRef = useRef<number>(0);
  const currentItemIdRef = useRef<string>(item.id);

  // تصفير موضع الفيديو السابق فوراً عند تغيير الحلقة أو المحتوى
  if (currentItemIdRef.current !== item.id) {
    currentItemIdRef.current = item.id;
    lastPositionRef.current = 0;
  }

  const showControlsRef = useRef(showControls);
  showControlsRef.current = showControls;
  const showChannelDrawerRef = useRef(showChannelDrawer);
  showChannelDrawerRef.current = showChannelDrawer;
  const showSettingsDrawerRef = useRef(showSettingsDrawer);
  showSettingsDrawerRef.current = showSettingsDrawer;
  const showEpisodesDrawerRef = useRef(showEpisodesDrawer);
  showEpisodesDrawerRef.current = showEpisodesDrawer;
  const isLockedRef = useRef(isLocked);
  isLockedRef.current = isLocked;

  const youtubeVideoId = extractYouTubeId(item.url, item.youtube_id);

  // تحديد نوع البث بدقة: الحلقات والأفلام المسجلة ليست بثاً مباشراً حتى لو كانت بصيغة m3u8
  const isSeriesEpisode =
    Boolean(item.parent_id) ||
    item.id.startsWith('xe:') ||
    item.url.includes('/series/') ||
    Boolean(playlist && playlist.length > 1);

  const isVodMovie =
    item.id.startsWith('xv:') ||
    item.url.includes('/movie/');

  const isLive =
    !isSeriesEpisode &&
    !isVodMovie &&
    (item.kind === 'channel' ||
      (item.url.includes('/live/') && !item.url.includes('/series/') && !item.url.includes('/movie/')));

  // 1. تحديث إحصائيات شاشة الفحص والتشخيص الفني (Diagnostics HUD)
  useEffect(() => {
    if (!showDiagnosticsHud) return;

    const interval = setInterval(() => {
      const video = videoRef.current;
      if (!video) return;

      let res = `${video.videoWidth}x${video.videoHeight}`;
      if (video.videoHeight >= 1080) res += ' (FHD 1080p)';
      else if (video.videoHeight >= 720) res += ' (HD 720p)';
      else if (video.videoHeight > 0) res += ' (SD)';

      let bufferSec = '0.0';
      try {
        const cur = video.currentTime;
        for (let i = 0; i < video.buffered.length; i++) {
          if (video.buffered.start(i) <= cur && cur <= video.buffered.end(i)) {
            bufferSec = (video.buffered.end(i) - cur).toFixed(1);
            break;
          }
        }
      } catch {}

      let dropped = 0;
      try {
        dropped = (video as any).getVideoPlaybackQuality?.()?.droppedVideoFrames || 0;
      } catch {}

      let bitrateMbps = '0.00';
      if (hlsRef.current && hlsRef.current.levels && hlsRef.current.currentLevel >= 0) {
        const lvl = hlsRef.current.levels[hlsRef.current.currentLevel];
        if (lvl && lvl.bitrate) {
          bitrateMbps = (lvl.bitrate / 1000000).toFixed(2);
        }
      }

      setHudStats({
        resolution: res,
        fps: video.videoHeight >= 1080 ? 50 : 30,
        bitrateMbps,
        bufferSec,
        droppedFrames: dropped,
        streamProtocol: isLive ? 'HLS Live (MPEG-TS)' : 'VOD Stream',
        videoCodec: 'H.264 (AVC)',
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [showDiagnosticsHud, isLive]);

  // محاولة إعادة الاتصال التلقائي بمجرد عودة الإنترنت
  const triggerReconnect = useCallback(() => {
    setIsReconnecting(true);
    setErrorMessage(null);

    if (reconnectTimerRef.current) clearInterval(reconnectTimerRef.current);

    reconnectTimerRef.current = setInterval(() => {
      if (navigator.onLine) {
        console.log('Internet connectivity restored! Resuming stream...');
        if (reconnectTimerRef.current) clearInterval(reconnectTimerRef.current);
        setIsReconnecting(false);
        setReconnectToast('تمت استعادة الاتصال بالإنترنت واستئناف التشغيل تلقائياً ✓');
        setTimeout(() => setReconnectToast(null), 3500);
        setRetryCount((c) => c + 1);
      }
    }, 3000);
  }, []);

  // الاستماع لأحداث الشبكة (Online / Offline)
  useEffect(() => {
    const handleOnline = () => {
      console.log('PlayerView online event fired!');
      if (reconnectTimerRef.current) clearInterval(reconnectTimerRef.current);
      setIsReconnecting(false);
      setErrorMessage(null);
      setReconnectToast('تمت استعادة الاتصال بالإنترنت واستئناف التشغيل تلقائياً ✓');
      setTimeout(() => setReconnectToast(null), 3500);
      setRetryCount((c) => c + 1);
    };

    const handleOffline = () => {
      console.log('PlayerView offline event fired!');
      triggerReconnect();
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      if (reconnectTimerRef.current) clearInterval(reconnectTimerRef.current);
    };
  }, [triggerReconnect]);

  // 2. التبديل التلقائي الذكي للبث البديل عند الانقطاع (Auto-Stream Failover)
  const handleSwitchStreamSource = useCallback((isAutomatic = false) => {
    const video = videoRef.current;
    if (!video || !item.url) return;

    failoverAttemptRef.current += 1;
    let nextUrl = item.url;

    // تبديل الصيغة بين .m3u8 و .ts
    if (nextUrl.includes('.m3u8')) {
      nextUrl = nextUrl.replace(/\.m3u8$/, '.ts');
    } else if (nextUrl.includes('.ts')) {
      nextUrl = nextUrl.replace(/\.ts$/, '.m3u8');
    }

    // تبديل المنافذ الاحتياطية إن وجدت
    if (failoverAttemptRef.current > 1) {
      if (nextUrl.includes(':8080/')) {
        nextUrl = nextUrl.replace(':8080/', ':80/');
      } else if (nextUrl.includes(':80/')) {
        nextUrl = nextUrl.replace(':80/', ':8080/');
      }
    }

    setReconnectToast(
      isAutomatic
        ? '🔄 تم كشف انقطاع، جاري التبديل التلقائي للمسار البديل...'
        : '🔄 جاري التبديل للمصدر الاحتياطي...'
    );

    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }

    const isHls = nextUrl.includes('.m3u8') || nextUrl.includes('m3u') || nextUrl.includes('/live/');

    if (isHls && Hls.isSupported()) {
      const hls = new Hls({
        // معطّل على webOS: الـ Web Workers المنشأة من blob: قد تفشل بصمت على بعض
        // إصدارات WebKit المدمجة في شاشات LG، مما يوقف تشغيل البث بالكامل
        enableWorker: false,
        lowLatencyMode: isLive,
        backBufferLength: 60,
        maxBufferLength: 180,
      });
      hlsRef.current = hls;
      hls.loadSource(nextUrl);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(console.warn);
        setReconnectToast('✅ تم الاتصال بالبث البديل بنجاح');
        setTimeout(() => setReconnectToast(null), 3500);
      });
      hls.on(Hls.Events.ERROR, (_, d) => {
        if (d.fatal) {
          triggerReconnect();
        }
      });
    } else {
      // للحلقات والأفلام: نكمل من نفس الثانية بدل البداية، ومع الصوت
      const resumeAt = !isLive ? lastPositionRef.current : 0;
      video.src = nextUrl;
      if (resumeAt > 1) {
        const onMeta = () => {
          video.removeEventListener('loadedmetadata', onMeta);
          try { video.currentTime = resumeAt; } catch {}
        };
        video.addEventListener('loadedmetadata', onMeta);
      }
      video.muted = false;
      video.play().catch(console.warn);
      setReconnectToast('✅ تم التبديل للبث البديل بنجاح');
      setTimeout(() => setReconnectToast(null), 3500);
    }
  }, [item.url, isLive, triggerReconnect]);

  // 3. خصائص التايم شفت للبث المباشر (Catch-up & TimeShift)
  const timeshiftRewind = (seconds: number) => {
    if (!videoRef.current) return;
    const target = Math.max(0, videoRef.current.currentTime - seconds);
    videoRef.current.currentTime = target;
    setReconnectToast(`⏪ تم الرجوع بالبث: ${Math.round(seconds / 60)} دقيقة (TimeShift)`);
    setTimeout(() => setReconnectToast(null), 3000);
  };

  const returnToLiveEdge = () => {
    if (!videoRef.current) return;
    if (hlsRef.current && hlsRef.current.liveSyncPosition) {
      videoRef.current.currentTime = hlsRef.current.liveSyncPosition;
    } else if (videoRef.current.duration && !isNaN(videoRef.current.duration)) {
      videoRef.current.currentTime = videoRef.current.duration;
    }
    setReconnectToast('🔴 تم العودة إلى البث المباشر الحي الآن');
    setTimeout(() => setReconnectToast(null), 3000);
  };

  // 4. تقليب القنوات بالأرقام وسهم القنوات (Numeric Zapping & Channel +/-)
  const switchChannelByOffset = useCallback(
    (offset: number) => {
      if (!liveChannels || liveChannels.length === 0 || !onSelectChannel) return;
      const curIdx = liveChannels.findIndex(
        (c) => c.stream_url === item.url || c.name === item.title || c.id === item.id
      );
      const startIdx = curIdx >= 0 ? curIdx : 0;
      const nextIdx = (startIdx + offset + liveChannels.length) % liveChannels.length;
      const target = liveChannels[nextIdx];
      if (target) {
        onSelectChannel(target);
        setChannelZappingToast(`📺 ${target.name} (${nextIdx + 1})`);
        setTimeout(() => setChannelZappingToast(null), 3000);
      }
    },
    [liveChannels, item.url, item.title, item.id, onSelectChannel]
  );

  const commitNumericZapping = useCallback(
    (numStr: string) => {
      if (!numStr) return;
      const targetIdx = parseInt(numStr, 10) - 1;
      setChannelNumberInput('');
      channelNumberInputRef.current = '';

      if (liveChannels && liveChannels.length > 0) {
        let targetCh: LiveChannel | undefined = liveChannels[targetIdx];
        if (!targetCh) {
          targetCh = liveChannels.find(
            (c) => c.position === parseInt(numStr, 10) || c.id === numStr
          );
        }
        if (targetCh && onSelectChannel) {
          onSelectChannel(targetCh);
          setChannelZappingToast(`📺 تم الانتقال إلى: ${targetCh.name}`);
          setTimeout(() => setChannelZappingToast(null), 3000);
          return;
        }
      }
      setChannelZappingToast(`⚠️ القناة رقم ${numStr} غير متوفرة`);
      setTimeout(() => setChannelZappingToast(null), 2500);
    },
    [liveChannels, onSelectChannel]
  );

  const handleNumericZapping = useCallback(
    (digit: string) => {
      const nextVal = (channelNumberInputRef.current + digit).slice(0, 4);
      setChannelNumberInput(nextVal);
      channelNumberInputRef.current = nextVal;
      setChannelZappingToast(`📺 القناة: ${nextVal}`);

      if (zappingTimerRef.current) clearTimeout(zappingTimerRef.current);
      zappingTimerRef.current = setTimeout(() => {
        commitNumericZapping(nextVal);
      }, 1200);
    },
    [commitNumericZapping]
  );



  // تحديثات الجهاز اللحظية (Realtime) تنشئ كائن device جديد كل مرة؛ لو كانت
  // blocked_words ضمن اعتماديات مؤثر التشغيل كان الفيديو يُعاد تحميله فجأة
  // أثناء المشاهدة (يوقف ثم يرجع بدون صوت). نقرأها من ref بدل ذلك.
  const mediaRecoverAtRef = useRef(0);
  const lastVideoErrorAtRef = useRef(0);
  const blockedWordsRef = useRef<string[]>(device.blocked_words || []);
  blockedWordsRef.current = device.blocked_words || [];
  const profanityMutedRef = useRef(false);

  const resetControlsTimeout = useCallback(() => {
    setShowControls(true);
    if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    controlsTimeoutRef.current = setTimeout(() => {
      setShowControls(false);
      setShowChannelDrawer(false);
      setShowSettingsDrawer(false);
      setShowEpisodesDrawer(false);
    }, 5000);
  }, []);

  // تشغيل الروابط المباشرة و HLS مع استخراج الترجمات والأصوات والجودة
  useEffect(() => {
    if (youtubeVideoId) return;

    const video = videoRef.current;
    if (!video) return;

    if (!item.url) {
      setErrorMessage('رابط التشغيل غير متوفر لهذا المحتوى');
      return;
    }

    let hls: Hls | null = null;
    let streamUrl = item.url;

    if (streamUrl.includes('/live/') && streamUrl.endsWith('.ts')) {
      streamUrl = streamUrl.replace(/\.ts$/, '.m3u8');
    }

    setErrorMessage(null);
    setCurrentTime(0);
    setIsBuffering(true);
    lastPositionRef.current = 0;

    const isHls =
      streamUrl.includes('.m3u8') ||
      streamUrl.includes('m3u') ||
      streamUrl.includes('/live/');

    const playVideo = (v: HTMLVideoElement) => {
      if (isLive) {
        v.play().catch(console.warn);
        return;
      }

      // قراءة موضع البداية المحفوظ مع التحقق من عدم البدء من نهاية الفيديو
      const savedPos = getStartPosition(item.id);
      const startSec = lastPositionRef.current > 0 ? lastPositionRef.current : savedPos;

      const dur = v.duration || 0;
      if (startSec > 5 && (dur === 0 || startSec < dur - 20)) {
        v.currentTime = startSec;
      } else {
        v.currentTime = 0;
      }

      v.muted = false;
      v.play().catch((e) => console.warn('Play error:', e));
    };

    if (isHls && Hls.isSupported()) {
      hls = new Hls({
        // معطّل على webOS: الـ Web Workers المنشأة من blob: قد تفشل بصمت على بعض
        // إصدارات WebKit المدمجة في شاشات LG، مما يوقف تشغيل البث بالكامل
        enableWorker: false,
        lowLatencyMode: isLive,
        backBufferLength: 60,
        maxBufferLength: 180, // تخزين استباقي 3 دقائق مسبقاً (Zero Buffering)
        maxMaxBufferLength: 300, // حد أقصى 5 دقائق مخزنة بالذاكرة
        maxBufferSize: 60 * 1024 * 1024, // 60MB RAM
      });
      hlsRef.current = hls;

      hls.loadSource(streamUrl);
      hls.attachMedia(video);

      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        playVideo(video);

        // استخراج مسارات الصوت مع التحديد التلقائي للغة العربية (Preferred Audio Auto-Select)
        if (hls && hls.audioTracks && hls.audioTracks.length > 0) {
          setAudioTracks(
            hls.audioTracks.map((t, i) => ({
              id: i,
              name: t.name || t.lang || `مسار صوت ${i + 1}`,
              lang: t.lang || '',
            }))
          );

          const preferredLang = localStorage.getItem('amankidz_default_audio_lang') || 'ar';
          const arIndex = hls.audioTracks.findIndex((t) => {
            const lang = (t.lang || '').toLowerCase();
            const name = (t.name || '').toLowerCase();
            if (preferredLang === 'ar') {
              return (
                lang.startsWith('ar') ||
                name.includes('arab') ||
                name.includes('عرب') ||
                name.includes('مدبلج')
              );
            }
            return lang.startsWith(preferredLang) || name.includes(preferredLang);
          });

          if (arIndex !== -1 && hls.audioTrack !== arIndex) {
            hls.audioTrack = arIndex;
            setSelectedAudioTrack(arIndex);
            setAudioToast(`🔊 تم تفعيل الصوت العربي تلقائياً (${hls.audioTracks[arIndex].name || 'عربي'})`);
            setTimeout(() => setAudioToast(null), 3500);
          } else {
            setSelectedAudioTrack(hls.audioTrack);
          }
        }

        // استخراج مسارات الترجمة
        if (hls && hls.subtitleTracks && hls.subtitleTracks.length > 0) {
          setSubtitleTracks(
            hls.subtitleTracks.map((s, i) => ({
              id: i,
              name: s.name || s.lang || `ترجمة ${i + 1}`,
              lang: s.lang || '',
            }))
          );
          setSelectedSubtitleTrack(hls.subtitleTrack);
        }

        // استخراج مستويات الجودة
        if (hls && hls.levels && hls.levels.length > 0) {
          setQualityLevels(
            hls.levels.map((l, i) => ({
              id: i,
              height: l.height || 0,
              bitrate: l.bitrate || 0,
            }))
          );
          setSelectedQuality(hls.currentLevel);
        }
      });

      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
          switch (data.type) {
            case Hls.ErrorTypes.NETWORK_ERROR:
              console.warn('HLS Network Error, initiating stream failover...');
              handleSwitchStreamSource(true);
              break;
            case Hls.ErrorTypes.MEDIA_ERROR: {
              const now = Date.now();
              if (now - mediaRecoverAtRef.current < 3000) {
                hls?.swapAudioCodec();
              }
              mediaRecoverAtRef.current = now;
              hls?.recoverMediaError();
              break;
            }
            default:
              if (streamUrl.includes('.m3u8')) {
                const tsUrl = streamUrl.replace(/\.m3u8$/, '.ts');
                hls?.destroy();
                hls = null;
                hlsRef.current = null;
                video.src = tsUrl;
                playVideo(video);
              } else {
                triggerReconnect();
                hls?.destroy();
                hls = null;
                hlsRef.current = null;
              }
              break;
          }
        }
      });
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = streamUrl;
      playVideo(video);
    } else {
      video.src = streamUrl;
      playVideo(video);
    }

    // فحص الألفاظ في نصوص الترجمة لكتم الصوت تلقائياً (AI / Rule Profanity Shield)
    const checkCues = () => {
      if (!video.textTracks || video.textTracks.length === 0) return;
      for (let i = 0; i < video.textTracks.length; i++) {
        const track = video.textTracks[i];
        track.oncuechange = () => {
          const cues = track.activeCues;
          if (cues && cues.length > 0) {
            for (let j = 0; j < cues.length; j++) {
              const cue = cues[j] as VTTCue;
              const rawText = cue.text || '';
              setActiveSubtitleText(rawText);
              const blockedWords = blockedWordsRef.current;
              const hasBad = subtitleHasBlockedWord(rawText, blockedWords);
              if (hasBad && !video.muted) {
                console.log('Profanity Shield: Bad word detected, muting audio...');
                video.muted = true;
                profanityMutedRef.current = true;
                setIsProfanityMuted(true);
                setTimeout(() => {
                  if (video) video.muted = false;
                  profanityMutedRef.current = false;
                  setIsProfanityMuted(false);
                }, 3500);
              }
            }
          } else {
            setActiveSubtitleText(null);
          }
        };
      }
    };
    checkCues();

    resetControlsTimeout();

    return () => {
      if (videoRef.current && !isLive) {
        const cur = videoRef.current.currentTime;
        const dur = videoRef.current.duration || 0;
        if (dur > 0 && cur > 1) {
          recordWatchProgress(item, cur, dur, activeProfileId);
        }
      }
      if (hls) hls.destroy();
      hlsRef.current = null;
      if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
      if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id, item.url, youtubeVideoId, isLive, retryCount, resetControlsTimeout, triggerReconnect]);

  // تتبع الوقت ومزامنة التقدم لميزة "أكمل المشاهدة"
  const handleTimeUpdate = () => {
    const video = videoRef.current;
    if (!video) return;

    const curr = video.currentTime;
    const dur = video.duration || 0;
    setCurrentTime(curr);
    setDuration(dur);
    lastPositionRef.current = curr;

    if (!isLive && dur > 0) {
      recordWatchProgress(item, curr, dur, activeProfileId);
    }

    // تخطي المقدمة
    if (
      item.intro_start != null &&
      item.intro_end != null &&
      curr >= item.intro_start &&
      curr < item.intro_end
    ) {
      setShowSkipIntro(true);
    } else {
      setShowSkipIntro(false);
    }

    // الكشف الذكي عن نهاية الحلقة والانتقال التلقائي
    if (!isLive && dur > 60 && onNext) {
      let isOutro = false;
      if (item.outro_start != null && curr >= item.outro_start) {
        isOutro = true;
      } else if (item.outro_len != null && curr >= dur - item.outro_len) {
        isOutro = true;
      } else if (item.kind === 'video' && curr >= dur - 20) {
        isOutro = true;
      }

      if (isOutro) {
        if (nextCountdown === null) {
          setNextCountdown(10);
          if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
          countdownIntervalRef.current = setInterval(() => {
            setNextCountdown((prev) => {
              if (prev === null) return null;
              if (prev <= 1) {
                if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
                onNext();
                return null;
              }
              return prev - 1;
            });
          }, 1000);
        }
      } else if (nextCountdown !== null && curr < dur - 25) {
        setNextCountdown(null);
        if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
      }
    }
  };

  const togglePlay = () => {
    const video = videoRef.current;
    if (!video) return;
    if (isPlaying) {
      video.pause();
      setIsPlaying(false);
    } else {
      video.play().catch((e) => console.warn('Play error:', e));
      setIsPlaying(true);
    }
    resetControlsTimeout();
  };

  const seek = (seconds: number) => {
    const video = videoRef.current;
    if (!video || isLive) return;
    video.currentTime = Math.max(0, Math.min(video.duration || 0, video.currentTime + seconds));
    resetControlsTimeout();
  };

  const skipIntro = () => {
    const video = videoRef.current;
    if (!video || item.intro_end == null) return;
    video.currentTime = item.intro_end;
    setShowSkipIntro(false);
    resetControlsTimeout();
  };

  // التحكم بسرعة التشغيل
  const handleSpeedChange = (speed: number) => {
    const video = videoRef.current;
    if (video) {
      video.playbackRate = speed;
      setPlaybackSpeed(speed);
      resetControlsTimeout();
    }
  };

  // تبديل مسار الصوت
  const handleSelectAudio = (idx: number) => {
    if (hlsRef.current) {
      hlsRef.current.audioTrack = idx;
      setSelectedAudioTrack(idx);
    }
  };

  // تبديل الترجمة
  const handleSelectSubtitle = (idx: number) => {
    if (hlsRef.current) {
      hlsRef.current.subtitleTrack = idx;
      setSelectedSubtitleTrack(idx);
    }
  };

  // تبديل الجودة
  const handleSelectQuality = (idx: number) => {
    if (hlsRef.current) {
      hlsRef.current.currentLevel = idx;
      setSelectedQuality(idx);
    }
  };

  // تبديل المفضلة بضغطة زر
  const toggleFavorite = () => {
    try {
      const raw = localStorage.getItem('amankidz_favorites');
      let list = raw ? JSON.parse(raw) : [];
      const exists = list.some((f: any) => f.id === item.id);
      if (exists) {
        list = list.filter((f: any) => f.id !== item.id);
        setIsFavorite(false);
        setFavToast('تمت إزالة العنصر من المفضلة 🤍');
      } else {
        list.unshift(item);
        setIsFavorite(true);
        setFavToast('تمت إضافة العنصر إلى المفضلة بنجاح ❤️');
      }
      localStorage.setItem('amankidz_favorites', JSON.stringify(list));
      setTimeout(() => setFavToast(null), 3000);
    } catch (e) {
      console.error(e);
    }
  };

  // تبديل معادل وضوح صوت الحوار (Speech Enhancer EQ)
  const toggleSpeechEnhance = () => {
    const next = !speechEnhanceMode;
    setSpeechEnhanceMode(next);
    localStorage.setItem('amankidz_speech_enhance', String(next));
    if (biquadFilterRef.current && audioCtxRef.current) {
      try {
        biquadFilterRef.current.gain.setValueAtTime(next ? 6 : 0, audioCtxRef.current.currentTime);
      } catch {}
    }
  };

  // تبديل ضغط الصوت الليلي الهادئ (Night Cinema Volume)
  const toggleNightAudio = () => {
    const next = !nightAudioMode;
    setNightAudioMode(next);
    localStorage.setItem('amankidz_night_audio', String(next));
    if (compressorRef.current && audioCtxRef.current) {
      try {
        compressorRef.current.threshold.setValueAtTime(next ? -24 : 0, audioCtxRef.current.currentTime);
        compressorRef.current.ratio.setValueAtTime(next ? 12 : 1, audioCtxRef.current.currentTime);
      } catch {}
    }
  };

  // وضع الاستماع لقصص النوم وإطفاء الشاشة
  const toggleScreenOffMode = () => {
    const next = !isScreenOffMode;
    setIsScreenOffMode(next);
    if (next) {
      unlockBadge('radio_dreamer');
    }
  };

  initialLoadingRef.current = isBuffering && currentTime < 1 && !errorMessage;

  // إغلاق المشغل مع الحفظ الفوري لآخر ثانية تمت مشاهدتها
  const handleSafeClose = useCallback(() => {
    if (videoRef.current && !isLive) {
      const cur = videoRef.current.currentTime;
      const dur = videoRef.current.duration || 0;
      if (dur > 0 && cur > 1) {
        recordWatchProgress(item, cur, dur, activeProfileId);
      }
    }
    onClose();
  }, [item, isLive, activeProfileId, onClose]);

  // التحكم بالريموت (LG webOS + Samsung Tizen)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const code = e.keyCode || e.which;

      // إذا كان وضع الاستماع بدون شاشة مفعلاً: أي ضغطة زر بالريموت توقظ الشاشة فوراً
      if (isScreenOffMode) {
        e.preventDefault();
        setIsScreenOffMode(false);
        return;
      }

      // الزر الأزرق: شاشة الفحص والتشخيص الفني المباشر (Diagnostics HUD)
      if (code === WEBOS_KEYS.BLUE || code === 406) {
        e.preventDefault();
        setShowDiagnosticsHud((prev) => !prev);
        return;
      }

      // الزر الأصفر: تفعيل / إلغاء وضع النافذة العائمة المصغرة (PiP Preview Mode)
      if (code === WEBOS_KEYS.YELLOW || code === 405) {
        e.preventDefault();
        setIsPipMode((prev) => !prev);
        return;
      }

      // أزرار تقليب القنوات المباشرة بالريموت (Channel Up & Channel Down)
      if (code === WEBOS_KEYS.CHANNEL_UP || code === 427) {
        e.preventDefault();
        switchChannelByOffset(1);
        return;
      }
      if (code === WEBOS_KEYS.CHANNEL_DOWN || code === 428) {
        e.preventDefault();
        switchChannelByOffset(-1);
        return;
      }

      // أزرار الأرقام 0..9 للتنقل المباشر والسريع بين القنوات (Numeric Channel Zapping)
      const isNumericKey = (code >= 48 && code <= 57) || (code >= 96 && code <= 105);
      if (isNumericKey && liveChannels && liveChannels.length > 0) {
        e.preventDefault();
        const digit = code >= 96 ? String(code - 96) : String(code - 48);
        handleNumericZapping(digit);
        return;
      }

      if (code === WEBOS_KEYS.ENTER && channelNumberInputRef.current) {
        e.preventDefault();
        commitNumericZapping(channelNumberInputRef.current);
        return;
      }

      // زر المفضلة الأخضر في ريموت التلفزيون (Green Color Key)
      if (code === 404) {
        e.preventDefault();
        toggleFavorite();
        return;
      }

      // 1. إذا كان قفل الأطفال مفعلاً: أي ضغطة عدا رجوع مرتين محظورة
      if (isLockedRef.current) {
        if (
          code === WEBOS_KEYS.BACK ||
          code === WEBOS_KEYS.TIZEN_BACK ||
          code === WEBOS_KEYS.ESCAPE ||
          code === WEBOS_KEYS.BACKSPACE
        ) {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          const now = Date.now();
          if (now - lastBackPressRef.current < 1500) {
            setIsLocked(false);
            setLockToast('تم فك قفل الشاشة بنجاح ✓');
            setTimeout(() => setLockToast(null), 3000);
          } else {
            lastBackPressRef.current = now;
            setLockToast('اضغط زر الرجوع (Back) مرة أخرى لفك القفل 🔓');
            setTimeout(() => setLockToast(null), 2500);
          }
        } else {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          setLockToast('🔒 الشاشة مقفلة لمنع العبث (اضغط رجوع مرتين للفتح)');
          setTimeout(() => setLockToast(null), 2500);
        }
        return;
      }

      // 2. معالجة زر الرجوع العادي
      if (
        code === WEBOS_KEYS.BACK ||
        code === WEBOS_KEYS.TIZEN_BACK ||
        code === WEBOS_KEYS.ESCAPE ||
        code === WEBOS_KEYS.BACKSPACE
      ) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();

        if (showSettingsDrawerRef.current) {
          setShowSettingsDrawer(false);
          return;
        }

        if (showChannelDrawerRef.current) {
          setShowChannelDrawer(false);
          return;
        }

        if (showEpisodesDrawerRef.current) {
          setShowEpisodesDrawer(false);
          return;
        }

        if (showControlsRef.current && !initialLoadingRef.current) {
          setShowControls(false);
          if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
          return;
        }

        // هذه آخر ضغطة ستُغلق المشغّل فعلياً وتُفكّك مستمع المفاتيح هذا؛ نسجّلها
        // في الحارس المشترك حتى لا تُعامَل أي ضغطة "صدى" تالية (شائعة في بعض
        // أجهزة التحكم) كضغطة رجوع جديدة من الشاشة الرئيسية بعد إغلاق المشغّل
        if (!shouldHandleBackPress()) return;
        handleSafeClose();
        return;
      }

      if (youtubeVideoId) return;

      // 3. أثناء فتح أي درج (قنوات/حلقات/إعدادات): الأسهم تتنقل بين أزراره فقط
      // (بدل التقديم/التأخير أو إغلاقه)، وزر "موافق" يُفعّل العنصر الذي عليه
      // التركيز بشكل طبيعي (نقرة المتصفح الافتراضية) بدل تشغيل/إيقاف الفيديو
      const isDrawerOpen =
        showChannelDrawerRef.current || showSettingsDrawerRef.current || showEpisodesDrawerRef.current;

      if (isDrawerOpen) {
        if (
          code === WEBOS_KEYS.UP ||
          code === WEBOS_KEYS.DOWN ||
          code === WEBOS_KEYS.LEFT ||
          code === WEBOS_KEYS.RIGHT
        ) {
          e.preventDefault();
          navigateSpatial(
            code === WEBOS_KEYS.UP
              ? 'up'
              : code === WEBOS_KEYS.DOWN
              ? 'down'
              : code === WEBOS_KEYS.LEFT
              ? 'left'
              : 'right'
          );
        }
        return;
      }

      resetControlsTimeout();

      if (code === WEBOS_KEYS.ENTER) {
        if (showSkipIntro) {
          skipIntro();
        } else if (nextCountdown !== null && onNext) {
          lastPositionRef.current = 0;
          onNext();
        } else {
          togglePlay();
        }
        return;
      }

      if (code === WEBOS_KEYS.PLAY || code === WEBOS_KEYS.PAUSE || code === WEBOS_KEYS.PLAY_PAUSE) {
        togglePlay();
        return;
      }

      // هل التركيز الحالي داخل أحد شريطي التحكم (العلوي أو السفلي)؟ إن كان كذلك
      // فالأسهم تتنقل بين أزرارهما بدل التقديم/التأخير المباشر، حتى يمكن فعلاً
      // الوصول لأزرار الإعدادات/الحلقات/القنوات/المفضلة وغيرها بالريموت
      const activeEl = document.activeElement;
      const isFocusInPlayerChrome =
        !!activeEl && (topBarRef.current?.contains(activeEl) || bottomBarRef.current?.contains(activeEl));

      if (code === WEBOS_KEYS.RIGHT) {
        if (isFocusInPlayerChrome) {
          e.preventDefault();
          navigateSpatial('right');
        } else {
          seek(10);
        }
      } else if (code === WEBOS_KEYS.LEFT) {
        if (isFocusInPlayerChrome) {
          e.preventDefault();
          navigateSpatial('left');
        } else {
          seek(-10);
        }
      } else if (code === WEBOS_KEYS.UP || code === WEBOS_KEYS.DOWN) {
        e.preventDefault();
        if (!showControlsRef.current) {
          // أول ضغطة: أظهر الشريط وركّز زر التشغيل/الإيقاف مباشرة
          setShowControls(true);
          resetControlsTimeout();
          setTimeout(() => playPauseRef.current?.focus(), 50);
        } else {
          navigateSpatial(code === WEBOS_KEYS.UP ? 'up' : 'down');
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose, onNext, showSkipIntro, nextCountdown, youtubeVideoId, liveChannels, resetControlsTimeout]);

  // تركيز تلقائي على أول زر داخل أي درج (قنوات/حلقات/إعدادات) فور فتحه، حتى
  // تعمل الأسهم فوراً للتنقل بداخله بدل البقاء على عنصر خارجه
  useEffect(() => {
    if (!showChannelDrawer && !showSettingsDrawer && !showEpisodesDrawer) return;
    const timer = setTimeout(() => {
      const roots = document.querySelectorAll<HTMLElement>('[data-tv-modal-root]');
      const root = roots[roots.length - 1];
      root?.querySelector<HTMLElement>('[tabindex="0"]')?.focus();
    }, 50);
    return () => clearTimeout(timer);
  }, [showChannelDrawer, showSettingsDrawer, showEpisodesDrawer]);

  const formatTime = (sec: number) => {
    if (isNaN(sec)) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  // -------------------------------------------------------------------------
  // شاشة النوم الهادئة التفاعلية للأطفال (Bedtime Ambient Screen)
  // -------------------------------------------------------------------------
  if (remainingMinutes != null && remainingMinutes <= 0 && !dismissBedtime) {
    return (
      <div
        data-tv-modal-root
        className="fixed inset-0 z-50 bg-[#0B0B0D] flex flex-col items-center justify-center text-center p-8 select-none text-white overflow-hidden"
      >
        {/* خلفية النجوم المتلألئة */}
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-indigo-900/40 via-[#0B0B0D] to-black" />
        
        <div className="relative z-10 flex flex-col items-center max-w-xl">
          <div className="text-8xl mb-6 animate-pulse drop-shadow-[0_0_35px_rgba(253,224,71,0.5)]">
            🌙
          </div>
          <h1 className="text-4xl font-black mb-4 text-[#FDE047]">
            حان وقت النوم والراحة يا بطل! 😴
          </h1>
          <p className="text-lg text-[#A1A1A6] leading-relaxed mb-8">
            انتهى وقت المشاهدة المخصص لليوم. لنأخذ قسطاً من الراحة والنوم لنستيقظ غداً بكل نشاط ومرح!
          </p>

          <div className="flex items-center gap-4">
            <button
              tabIndex={0}
              onClick={onClose}
              className="tv-focusable bg-[#FFFFFF] text-[#0B0B0D] font-black text-lg px-8 py-3.5 rounded-2xl shadow-xl hover:scale-105 transition"
            >
              إغلاق الشاشة الآن 😴
            </button>
            <button
              tabIndex={0}
              onClick={() => setDismissBedtime(true)}
              className="tv-focusable bg-white/10 hover:bg-white/20 text-white font-bold text-sm px-6 py-3.5 rounded-2xl transition border border-white/10"
            >
              إذن ولي الأمر (تخطي) 🔑
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div
      // نحصر البحث عن العناصر القابلة للتركيز داخل جذر المشغّل فقط (نفس ما تفعله
      // كل النوافذ الأخرى في التطبيق عبر [data-tv-modal-root]) — بدون هذا،
      // تبقى HomeScreen مُركّبة خلف الفيديو (لحفظ حالتها) وتدخل أزرارها ضمن
      // نتائج navigateSpatial فتخطف التركيز من أزرار المشغّل الظاهرة فعلياً.
      // في وضع PiP الصغير نترك الشاشة الرئيسية خلفه قابلة للتنقل بعمد (هذا هو
      // الغرض من الوضع المصغّر: تصفح الشاشة الرئيسية أثناء تشغيل صغير جانبي).
      {...(!isPipMode ? { 'data-tv-modal-root': '' } : {})}
      className={
        isPipMode
          ? "fixed bottom-8 left-8 w-[400px] h-[225px] z-50 rounded-2xl shadow-[0_20px_50px_rgba(0,0,0,0.9)] border-2 border-[#FFFFFF] overflow-hidden bg-black flex items-center justify-center select-none group"
          : "fixed inset-0 z-50 bg-black flex items-center justify-center select-none"
      }
      onClick={() => {
        if (isLocked) {
          setLockToast('🔒 الشاشة مقفلة (اضغط رجوع مرتين للفتح)');
          setTimeout(() => setLockToast(null), 2500);
          return;
        }
        if (showControls) {
          setShowControls(false);
        } else {
          resetControlsTimeout();
        }
      }}
    >
      {/* إشعار كتم الألفاظ غير الملائمة تلقائياً (Profanity Shield) */}
      {isProfanityMuted && (
        <AtvToast tone="ok" icon="🛡️" top={100}>درع الأمان: تم كتم لفظ غير ملائم</AtvToast>
      )}

      {/* شارة رقم القناة عند الضغط على الأرقام بالريموت (Channel Number Overlay) */}
      {channelNumberInput && (
        <div className="absolute top-10 right-10 z-50 atv-osd-glass px-8 py-4 rounded-3xl shadow-2xl flex items-center gap-4 atv-rise">
          <span className="text-3xl">📺</span>
          <span className="text-5xl font-mono font-black text-[#FFFFFF] tracking-widest">
            {channelNumberInput}
          </span>
          <span className="text-xs text-[#A1A1A6] font-bold">قناة</span>
        </div>
      )}

      {/* إشعار التنقل بين القنوات */}
      {channelZappingToast && (
        <AtvToast top={40}>{channelZappingToast}</AtvToast>
      )}

      {/* إشعار اختيار الصوت العربي التلقائي */}
      {audioToast && (
            <AtvToast tone="info" top={100}>{audioToast}</AtvToast>
          )}

      {/* إشعار التبديل وإعادة الاتصال */}
      {reconnectToast && (
            <AtvToast tone="ok" top={40}>{reconnectToast}</AtvToast>
          )}

      {/* شاشة الفحص الفني المباشر (Stream Diagnostics HUD) */}
      {showDiagnosticsHud && !isPipMode && (
        <div
          className="absolute top-24 left-8 z-40 bg-black/90 backdrop-blur-md border border-[#FFFFFF]/40 rounded-2xl p-5 text-white max-w-sm shadow-[0_10px_35px_rgba(0,0,0,0.85)] select-none text-right animate-in fade-in"
          dir="rtl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between pb-3 mb-3 border-b border-white/10">
            <div className="flex items-center gap-2">
              <span className="text-xl">📊</span>
              <h3 className="text-sm font-black text-[#FFFFFF]">فحص جودة وأداء البث (HUD)</h3>
            </div>
            <button
              onClick={() => setShowDiagnosticsHud(false)}
              className="text-[#A1A1A6] hover:text-white text-xs bg-white/10 hover:bg-white/20 px-2.5 py-1 rounded-md transition"
              title="إغلاق (الزر الأزرق)"
            >
              ✕
            </button>
          </div>

          <div className="space-y-2 text-xs font-mono">
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">الدقة الحالية:</span>
              <span className="font-bold text-emerald-400">{hudStats.resolution}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">معدل نقل البيانات:</span>
              <span className="font-bold text-[#FFFFFF]">{hudStats.bitrateMbps} Mbps</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">صحة التخزين المؤقت:</span>
              <span className="font-bold text-amber-400">{hudStats.bufferSec} ثانية</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">الإطارات المفقودة:</span>
              <span className={`font-bold ${hudStats.droppedFrames === 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                {hudStats.droppedFrames} frames
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">نوع البث:</span>
              <span className="font-bold text-[#D1D1D6]">{hudStats.streamProtocol}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#A1A1A6] font-sans">ترميز الفيديو:</span>
              <span className="font-bold text-[#D1D1D6]">{hudStats.videoCodec}</span>
            </div>
          </div>

          <div className="mt-4 pt-3 border-t border-white/10">
            <button
              onClick={() => handleSwitchStreamSource(false)}
              tabIndex={0}
              className="tv-focusable w-full bg-white/10 hover:bg-[#FFFFFF] hover:text-[#0B0B0D] text-white font-bold text-xs py-2 px-3 rounded-xl transition text-center"
            >
              🔄 تبديل السيرفر / المصدر الاحتياطي
            </button>
          </div>
          <div className="mt-2 text-[10px] text-[#6E6E73] text-center font-sans">
            اضغط الزر الأزرق 🔵 بالريموت للإظهار والإخفاء
          </div>
        </div>
      )}

      {/* تحكم وضع النافذة المصغرة (PiP Preview Overlay) */}
      {isPipMode && (
        <div
          className="absolute inset-0 z-40 bg-gradient-to-t from-black/85 via-transparent to-black/70 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity flex flex-col justify-between p-3"
          dir="rtl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between">
            <span className="bg-red-600/90 text-white text-[10px] font-black px-2 py-0.5 rounded-full flex items-center gap-1 animate-pulse">
              <span className="w-1.5 h-1.5 rounded-full bg-white" />
              {isLive ? 'بث مصغر' : 'تشغيل مصغر'}
            </span>
            <div className="flex items-center gap-1.5">
              <button
                tabIndex={0}
                onClick={() => setIsPipMode(false)}
                className="tv-focusable bg-[#FFFFFF] text-[#0B0B0D] font-black text-xs px-2.5 py-1 rounded-lg shadow hover:scale-105 transition flex items-center gap-1"
                title="تكبير الشاشة الكاملة (الزر الأصفر)"
              >
                <span>⛶</span>
                <span>تكبير</span>
              </button>
              <button
                tabIndex={0}
                onClick={handleSafeClose}
                className="tv-focusable bg-white/20 hover:bg-red-600 text-white font-bold text-xs p-1 rounded-lg transition"
                title="إغلاق"
              >
                ✕
              </button>
            </div>
          </div>
          <div className="text-white text-xs font-bold truncate drop-shadow">
            {item.title}
          </div>
        </div>
      )}
      {/* إشعار قفل الأطفال العائم */}
      {lockToast && (
            <AtvToast tone="info" top={40}>{lockToast}</AtvToast>
          )}


      {/* الترجمة المزدوجة لتعليم الإنجليزية (Bilingual Learning Caption) */}
      {bilingualMode && activeSubtitleText && (
        <div className="absolute bottom-24 left-1/2 -translate-x-1/2 z-30 bg-black/85 backdrop-blur-md px-8 py-3 rounded-2xl text-center border border-white/20 shadow-2xl max-w-2xl animate-in fade-in duration-150">
          <p className="text-lg font-bold text-[#FFFFFF] mb-0.5">{activeSubtitleText}</p>
          <span className="text-xs text-[#A1A1A6] font-bold">🌍 وضع التعلم اللغوي المزدوج</span>
        </div>
      )}

      {/* وضع الاستماع وإطفاء الشاشة لقصص النوم (Screen-Off Bedtime Audio) */}
      {isScreenOffMode && (
        <div
          className="fixed inset-0 z-50 bg-[#020408] flex flex-col items-center justify-center text-center p-8 select-none text-white cursor-pointer"
          onClick={() => setIsScreenOffMode(false)}
        >
          <div className="w-28 h-28 rounded-full bg-indigo-500/10 flex items-center justify-center text-6xl mb-6 animate-pulse opacity-40">
            🌙
          </div>
          <h2 className="text-3xl font-black text-[#A5B4FC]/90 mb-3">
            وضع الاستماع الهادئ (الشاشة مطفأة) 📻
          </h2>
          <p className="text-xl text-[#6E6E73] max-w-lg leading-relaxed mb-8">
            الصوت مستمر في الخلفية بدون إضاءة الشاشة لمساعدتك على النوم الهادئ وتوفير طاقة التلفاز 💤
          </p>
          <button
            tabIndex={0}
            onClick={() => setIsScreenOffMode(false)}
            className="tv-focusable bg-white/10 hover:bg-white/20 text-[#D1D1D6] font-bold text-lg px-8 py-4 rounded-2xl border border-white/10"
          >
            إعادة إضاءة الشاشة (اضغط أي زر بالريموت) 💡
          </button>
        </div>
      )}

      {youtubeVideoId ? (
        <div className="w-full h-full relative">
          <iframe
            src={`https://www.youtube.com/embed/${youtubeVideoId}?autoplay=1&controls=1&rel=0&modestbranding=1`}
            className="w-full h-full border-0"
            allow="autoplay; encrypted-media"
            allowFullScreen
          />
          <button
            onClick={onClose}
            className="absolute top-6 left-6 z-50 bg-black/80 text-white font-bold text-sm px-5 py-2.5 rounded-xl border border-white/20 hover:bg-black transition flex items-center gap-2"
          >
            <span>←</span>
            <span>الرجوع (الريموت)</span>
          </button>
        </div>
      ) : (
        <>
          <video
            ref={videoRef}
            className="w-full h-full object-contain transition-all duration-700"
            style={{
              filter: nightMode ? 'sepia(24%) saturate(92%) brightness(95%)' : 'none',
            }}
            onTimeUpdate={handleTimeUpdate}
            onWaiting={() => setIsBuffering(true)}
            onSeeking={() => setIsBuffering(true)}
            onSeeked={() => setIsBuffering(false)}
            onCanPlay={() => {
              if (videoRef.current && !videoRef.current.paused) setIsBuffering(false);
            }}
            onPause={() => setIsBuffering(false)}
            onEnded={() => {
              lastPositionRef.current = 0;
              if (onNext) onNext();
              else onClose();
            }}
            onError={() => {
              const now = Date.now();
              if (now - lastVideoErrorAtRef.current < 4000) return;
              lastVideoErrorAtRef.current = now;
              console.warn('HTML5 Video error, triggering automatic stream failover...');
              handleSwitchStreamSource(true);
            }}
            onPlaying={() => {
              setIsBuffering(false);
              // ضمان: أي تشغيل بعد توقف/إعادة اتصال يرجع بالصوت (إلا أثناء كتم الألفاظ)
              const v = videoRef.current;
              if (v && v.muted && !profanityMutedRef.current) v.muted = false;
            }}
          />

          {/* شاشة الانتظار بنمط Apple TV */}
          {isBuffering && !errorMessage && !isPipMode && (
            currentTime < 1 ? (
              <div className="absolute inset-0 z-[15] flex flex-col items-center justify-center atv-fade-in pointer-events-none">
                {(item.backdrop_url || item.thumbnail_url || item.poster_url) && (
                  <img
                    src={item.backdrop_url || item.thumbnail_url || item.poster_url || ''}
                    alt=""
                    className="absolute inset-0 w-full h-full object-cover opacity-35 blur-xl scale-110"
                  />
                )}
                <div className="absolute inset-0 bg-gradient-to-t from-black via-black/60 to-black/40" />
                <div className="relative flex flex-col items-center text-center px-16 atv-rise">
                  <AtvSpinner size={46} className="mb-7" />
                  <h2 dir="auto" className="text-[34px] font-extrabold text-white mb-2 max-w-[70vw] truncate">
                    {item.title}
                  </h2>
                  <p className="text-base font-medium text-white/55">
                    {isLive ? 'جاري الاتصال بالبث…' : lastPositionRef.current > 5 ? 'نكمل من حيث توقفت…' : 'جاري التحضير…'}
                  </p>
                </div>
              </div>
            ) : (
              <div className="absolute inset-0 z-[15] flex items-center justify-center pointer-events-none atv-fade-in">
                <div className="w-24 h-24 rounded-full bg-black/45 flex items-center justify-center atv-osd-glass">
                  <AtvSpinner size={40} />
                </div>
              </div>
            )
          )}

          {/* لافتة إعادة الاتصال التلقائي بالإنترنت */}
          {isReconnecting && (
            <AtvToast tone="warn" icon={<AtvSpinner size={20} />}>
              انقطع الاتصال بالإنترنت، نعيد المحاولة تلقائياً فور عودته…
            </AtvToast>
          )}

          {reconnectToast && (
            <AtvToast tone="ok" top={40}>{reconnectToast}</AtvToast>
          )}

          {/* رسالة الخطأ وشاشة الاسترداد */}
          {errorMessage && (
            <div className="absolute inset-0 z-40 flex items-center justify-center atv-fade-in" dir="rtl">
              <div className="absolute inset-0 bg-black/75" />
              <div className="relative atv-sheet w-[620px] rounded-[28px] px-10 pt-9 pb-8 text-center atv-rise">
                <div className="w-16 h-16 mx-auto mb-5 rounded-full bg-[#FF9F0A]/15 text-[#FF9F0A] flex items-center justify-center text-3xl font-black">!</div>
                <h2 className="text-[26px] font-extrabold text-white mb-2">تعذّر تشغيل المحتوى</h2>
                <p className="text-base text-white/60 mb-8 leading-relaxed">{errorMessage}</p>
                <div className="flex gap-3">
                  <button
                    onClick={() => {
                      setErrorMessage(null);
                      setRetryCount((c) => c + 1);
                    }}
                    tabIndex={0}
                    className="atv-btn-primary flex-1 h-14 rounded-xl text-lg font-bold"
                  >
                    إعادة المحاولة
                  </button>
                  <button onClick={onClose} tabIndex={0} className="atv-btn-glass flex-1 h-14 rounded-xl text-lg font-bold">
                    رجوع
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* زر تخطي المقدمة العائم */}
          {showSkipIntro && (
            <button
              onClick={skipIntro}
              tabIndex={0}
              className="absolute bottom-16 left-16 z-30 atv-osd-pill !h-14 !px-7 !text-lg atv-rise"
            >
              تخطي المقدمة
            </button>
          )}

          {/* موجه الانتقال التلقائي للحلقة التالية */}
          {nextCountdown !== null && onNext && (
            <div dir="rtl" className="absolute bottom-16 left-16 z-30 atv-sheet rounded-3xl p-5 pr-6 flex items-center gap-5 atv-rise">
              <div className="text-right">
                <span className="text-xs font-bold text-white/55 block mb-1">الحلقة التالية</span>
                <span className="text-lg font-bold text-white block">تبدأ خلال {nextCountdown} ثوانٍ</span>
              </div>
              <button
                tabIndex={0}
                onClick={() => {
                  lastPositionRef.current = 0;
                  onNext();
                }}
                className="atv-btn-primary h-12 px-6 rounded-xl text-base font-bold"
              >
                ▶ تشغيل الآن
              </button>
              <button
                tabIndex={0}
                onClick={() => setNextCountdown(null)}
                className="atv-btn-glass h-12 px-5 rounded-xl text-base font-bold"
              >
                إلغاء
              </button>
            </div>
          )}

          {/* درج القنوات السريع المباشر مع دليل البرامج (EPG Guide) */}
          {showChannelDrawer && liveChannels.length > 0 && onSelectChannel && (
            <div
              data-tv-modal-root
              className="absolute top-0 right-0 bottom-0 w-88 bg-[#0B0B0D]/95 backdrop-blur-md z-40 p-6 flex flex-col border-l border-white/10 animate-in slide-in-from-right duration-200"
            >
              <div className="flex items-center justify-between mb-4 border-b border-white/10 pb-3">
                <div>
                  <h3 className="text-lg font-black text-[#FFFFFF]">القنوات المباشرة</h3>
                  <p className="text-[11px] text-[#A1A1A6]">دليل البرامج التلفزيوني (EPG)</p>
                </div>
                <span className="text-xs bg-red-600 text-white font-bold px-2 py-0.5 rounded">LIVE</span>
              </div>

              <div className="flex-1 overflow-y-auto space-y-2.5 pr-1">
                {liveChannels.map((ch) => (
                  <button
                    key={ch.id}
                    tabIndex={0}
                    onClick={() => {
                      onSelectChannel(ch);
                      setShowChannelDrawer(false);
                    }}
                    className={`tv-focusable w-full text-right p-3 rounded-2xl flex flex-col gap-1 transition ${
                      ch.stream_url === item.url
                        ? 'bg-[#FFFFFF] text-[#0B0B0D] font-black shadow-lg shadow-[#FFFFFF]/30'
                        : 'bg-white/5 hover:bg-white/10 text-white border border-white/5'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      {ch.logo ? (
                        <img src={ch.logo} alt={ch.name} className="h-7 w-7 object-contain flex-shrink-0" />
                      ) : (
                        <span className="text-lg">📡</span>
                      )}
                      <span className="text-xs font-black truncate flex-1">{ch.name}</span>
                    </div>

                    {/* معلومات دليل البرامج EPG المباشر */}
                    <div className="flex items-center justify-between text-[10px] opacity-80 pt-1 border-t border-black/10">
                      <span>🔴 يبث الآن: {ch.group_name || 'بث تلفزيوني'}</span>
                      <span className="font-mono">مباشر</span>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* درج قائمة حلقات المسلسل السريع (Episodes Drawer) */}
          {showEpisodesDrawer && playlist && playlist.length > 1 && (
            <div
              data-tv-modal-root
              className="absolute top-0 right-0 bottom-0 w-96 bg-[#0B0B0D]/95 backdrop-blur-md z-40 p-6 flex flex-col border-l border-white/10 animate-in slide-in-from-right duration-200 text-white select-none"
            >
              <div className="flex items-center justify-between mb-4 border-b border-white/10 pb-3">
                <div>
                  <h3 className="text-lg font-black text-[#FFFFFF] flex items-center gap-2">
                    <span>📑</span> قائمة الحلقات
                  </h3>
                  <p className="text-xs text-[#A1A1A6] mt-0.5">اختر أي حلقة للانتقال إليها فوراً</p>
                </div>
                <span className="text-xs bg-[#1C1C1E] text-[#FFFFFF] px-2.5 py-1 rounded-full font-bold border border-[#FFFFFF]/20">
                  {playlist.length} حلقة
                </span>
              </div>

              <div className="flex-1 overflow-y-auto space-y-2.5 pr-1" dir="rtl">
                {playlist.map((ep, idx) => {
                  const isCurrent = ep.id === item.id;
                  return (
                    <button
                      key={ep.id}
                      tabIndex={0}
                      onClick={() => {
                        lastPositionRef.current = 0;
                        setShowEpisodesDrawer(false);
                        if (onSelectEpisode) {
                          onSelectEpisode(ep);
                        }
                      }}
                      className={`tv-focusable w-full text-right p-3 rounded-2xl flex items-center gap-3 transition ${
                        isCurrent
                          ? 'bg-[#FFFFFF] text-[#0B0B0D] font-black shadow-lg shadow-[#FFFFFF]/30 scale-[1.02]'
                          : 'bg-white/5 hover:bg-white/10 text-white border border-white/5'
                      }`}
                    >
                      <span
                        className={`w-8 h-8 rounded-xl flex items-center justify-center font-bold text-sm flex-shrink-0 ${
                          isCurrent ? 'bg-[#0B0B0D] text-[#FFFFFF]' : 'bg-white/10 text-[#A1A1A6]'
                        }`}
                      >
                        {idx + 1}
                      </span>

                      <div className="flex-1 truncate">
                        <span className="text-sm font-bold block truncate">{ep.title}</span>
                        {isCurrent && (
                          <span className="text-[10px] font-bold opacity-80 block">▶ جاري التشغيل الآن</span>
                        )}
                      </div>

                      {isCurrent && <span className="text-sm">🔊</span>}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* درج إعدادات الصوت والترجمة والجودة والسرعة (Audio, Subtitles & Quality) */}
          {showSettingsDrawer && (
            <div
              data-tv-modal-root
              className="absolute top-0 left-0 bottom-0 w-88 bg-[#0B0B0D]/95 backdrop-blur-md z-40 p-6 flex flex-col border-r border-white/10 animate-in slide-in-from-left duration-200 text-white overflow-y-auto"
            >
              <div className="flex items-center justify-between mb-5 border-b border-white/10 pb-3">
                <h3 className="text-lg font-black text-[#FFFFFF] flex items-center gap-2">
                  <span>⚙️</span> خيارات البث والترجمة
                </h3>
                <button
                  onClick={() => setShowSettingsDrawer(false)}
                  className="tv-focusable text-sm bg-white/10 px-3 py-1 rounded-xl"
                >
                  ✕
                </button>
              </div>

              {/* قسم سرعة التشغيل */}
              <div className="mb-6">
                <h4 className="text-xs font-black text-[#A1A1A6] mb-2.5">سرعة التشغيل (Playback Speed):</h4>
                <div className="grid grid-cols-4 gap-2">
                  {[0.75, 1.0, 1.25, 1.5].map((spd) => (
                    <button
                      key={spd}
                      tabIndex={0}
                      onClick={() => handleSpeedChange(spd)}
                      className={`tv-focusable py-2 rounded-xl text-xs font-bold transition ${
                        playbackSpeed === spd
                          ? 'bg-[#FFFFFF] text-[#0B0B0D] font-black'
                          : 'bg-white/5 hover:bg-white/10 text-white'
                      }`}
                    >
                      {spd === 1.0 ? '1.0x (عادي)' : `${spd}x`}
                    </button>
                  ))}
                </div>
              </div>

              {/* قسم مسار الصوت (Audio Track) */}
              <div className="mb-6">
                <h4 className="text-xs font-black text-[#A1A1A6] mb-2.5">مسار الصوت (Audio Language):</h4>
                <div className="space-y-1.5">
                  <button
                    tabIndex={0}
                    onClick={() => handleSelectAudio(-1)}
                    className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                      selectedAudioTrack === -1
                        ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                        : 'bg-white/5 hover:bg-white/10 text-white'
                    }`}
                  >
                    الصوت الافتراضي (تلقائي)
                  </button>
                  {audioTracks.map((tr) => (
                    <button
                      key={tr.id}
                      tabIndex={0}
                      onClick={() => handleSelectAudio(tr.id)}
                      className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                        selectedAudioTrack === tr.id
                          ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                          : 'bg-white/5 hover:bg-white/10 text-white'
                      }`}
                    >
                      {tr.name} {tr.lang ? `(${tr.lang})` : ''}
                    </button>
                  ))}
                </div>
              </div>

              {/* قسم الترجمة (Subtitles) */}
              <div className="mb-6">
                <h4 className="text-xs font-black text-[#A1A1A6] mb-2.5">الترجمة (Subtitles):</h4>
                <div className="space-y-1.5">
                  <button
                    tabIndex={0}
                    onClick={() => handleSelectSubtitle(-1)}
                    className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                      selectedSubtitleTrack === -1
                        ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                        : 'bg-white/5 hover:bg-white/10 text-white'
                    }`}
                  >
                    إيقاف الترجمة ✕
                  </button>
                  {subtitleTracks.map((sub) => (
                    <button
                      key={sub.id}
                      tabIndex={0}
                      onClick={() => handleSelectSubtitle(sub.id)}
                      className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                        selectedSubtitleTrack === sub.id
                          ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                          : 'bg-white/5 hover:bg-white/10 text-white'
                      }`}
                    >
                      {sub.name} {sub.lang ? `(${sub.lang})` : ''}
                    </button>
                  ))}
                </div>
              </div>

              {/* قسم الجودة (Quality) */}
              {qualityLevels.length > 0 && (
                <div>
                  <h4 className="text-xs font-black text-[#A1A1A6] mb-2.5">جودة الفيديو (Quality):</h4>
                  <div className="space-y-1.5">
                    <button
                      tabIndex={0}
                      onClick={() => handleSelectQuality(-1)}
                      className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                        selectedQuality === -1
                          ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                          : 'bg-white/5 hover:bg-white/10 text-white'
                      }`}
                    >
                      جودة تلقائية (Auto)
                    </button>
                    {qualityLevels.map((lvl) => (
                      <button
                        key={lvl.id}
                        tabIndex={0}
                        onClick={() => handleSelectQuality(lvl.id)}
                        className={`tv-focusable w-full text-right p-2.5 rounded-xl text-xs font-bold transition ${
                          selectedQuality === lvl.id
                            ? 'bg-[#FFFFFF] text-[#0B0B0D]'
                            : 'bg-white/5 hover:bg-white/10 text-white'
                        }`}
                      >
                        {lvl.height > 0 ? `${lvl.height}p` : `جودة ${lvl.id + 1}`}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* قسم هندسة الصوت وتعزيز الكلام وضغط الصوت الليلي */}
              <div className="mt-6 pt-5 border-t border-white/10">
                <h4 className="text-base font-black text-[#FFFFFF] mb-3">هندسة الصوت الذكية:</h4>
                <div className="space-y-2.5">
                  <button
                    tabIndex={0}
                    onClick={toggleSpeechEnhance}
                    className={`tv-focusable w-full text-right p-3.5 rounded-2xl text-base font-black transition flex items-center justify-between border ${
                      speechEnhanceMode
                        ? 'bg-[#FFFFFF]/20 text-[#FFFFFF] border-[#FFFFFF]/40'
                        : 'bg-white/5 hover:bg-white/10 text-white border-white/5'
                    }`}
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="text-xl">🎙️</span>
                      <span>معادل وضوح صوت الحوار (Speech Clarity EQ)</span>
                    </span>
                    <span className="text-xs opacity-80">{speechEnhanceMode ? 'مفعّل ✓' : 'متوقف'}</span>
                  </button>

                  <button
                    tabIndex={0}
                    onClick={toggleNightAudio}
                    className={`tv-focusable w-full text-right p-3.5 rounded-2xl text-base font-black transition flex items-center justify-between border ${
                      nightAudioMode
                        ? 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40'
                        : 'bg-white/5 hover:bg-white/10 text-white border-white/5'
                    }`}
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="text-xl">🎧</span>
                      <span>ضغط الصوت السينمائي الهادئ (Night Audio)</span>
                    </span>
                    <span className="text-xs opacity-80">{nightAudioMode ? 'مفعّل ✓' : 'متوقف'}</span>
                  </button>

                  <button
                    tabIndex={0}
                    onClick={() => {
                      setShowSettingsDrawer(false);
                      toggleScreenOffMode();
                    }}
                    className="tv-focusable w-full text-right p-3.5 rounded-2xl text-base font-black transition flex items-center justify-between border bg-white/5 hover:bg-white/10 text-white border-white/5"
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="text-xl">📻</span>
                      <span>وضع الاستماع وإطفاء الشاشة (قصص النوم)</span>
                    </span>
                    <span className="text-xs text-[#FFFFFF]">تشغيل ◀</span>
                  </button>
                </div>
              </div>

              {/* قسم الصحة البصرية والتعلم اللغوي */}
              <div className="mt-6 pt-5 border-t border-white/10 pb-6">
                <h4 className="text-base font-black text-[#A1A1A6] mb-3">حماية العين والتعلم الذكي:</h4>
                <div className="space-y-2.5">
                  <button
                    tabIndex={0}
                    onClick={() => setNightMode(!nightMode)}
                    className={`tv-focusable w-full text-right p-3.5 rounded-2xl text-base font-black transition flex items-center justify-between border ${
                      nightMode
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500/30'
                        : 'bg-white/5 hover:bg-white/10 text-white border-white/5'
                    }`}
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="text-xl">🌙</span> الوضع الليلي الدافئ لحماية العين
                    </span>
                    <span className="text-xs opacity-80">{nightMode ? 'مفعّل ✓' : 'متوقف'}</span>
                  </button>

                  <button
                    tabIndex={0}
                    onClick={() => {
                      const next = !bilingualMode;
                      setBilingualMode(next);
                      if (next) unlockBadge('bilingual');
                    }}
                    className={`tv-focusable w-full text-right p-3.5 rounded-2xl text-base font-black transition flex items-center justify-between border ${
                      bilingualMode
                        ? 'bg-[#FFFFFF]/20 text-[#FFFFFF] border-[#FFFFFF]/30'
                        : 'bg-white/5 hover:bg-white/10 text-white border-white/5'
                    }`}
                  >
                    <span className="flex items-center gap-2.5">
                      <span className="text-xl">🌍</span> وضع التعلم بالترجمة المزدوجة
                    </span>
                    <span className="text-xs opacity-80">{bilingualMode ? 'مفعّل ✓' : 'متوقف'}</span>
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* إشعار إضافة/إزالة المفضلة */}
          {favToast && (
            <AtvToast tone="ok" top={40}>{favToast}</AtvToast>
          )}

          {/* ═══ واجهة التحكم بنمط Apple TV ═══ */}
          {/* أعلى: رجوع + الوقت المتبقي للطفل */}
          <div
            ref={topBarRef}
            className={`absolute inset-x-0 top-0 px-12 pt-10 pb-24 bg-gradient-to-b from-black/60 to-transparent transition-opacity duration-500 z-30 flex items-start justify-between select-none ${
              showControls && !(isBuffering && currentTime < 1 && !errorMessage) ? 'opacity-100' : 'opacity-0 pointer-events-none'
            }`}
            onClick={(e) => e.stopPropagation()}
            dir="rtl"
            style={{ direction: 'rtl' }}
          >
            <div className="flex items-center gap-3">
              <button tabIndex={0} onClick={handleSafeClose} className="atv-icon-btn atv-osd-glass w-12 h-12" title="رجوع">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </button>
              {remainingMinutes != null && (
                <span className="atv-osd-glass h-9 px-4 rounded-full text-sm font-bold text-white/85 flex items-center gap-1.5">
                  وقت المشاهدة المتبقي: {remainingMinutes} د
                </span>
              )}
            </div>
            {isLive && (
              <span className="bg-[#FF453A] text-white text-sm font-bold h-8 px-3.5 rounded-md flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-white animate-pulse" /> مباشر
              </span>
            )}
          </div>

          {/* أسفل: العنوان + الأزرار + شريط التقدم + أزرار التشغيل */}
          <div
            ref={bottomBarRef}
            dir="rtl"
            className={`absolute inset-x-0 bottom-0 px-16 pt-40 pb-12 bg-gradient-to-t from-black/90 via-black/55 to-transparent transition-opacity duration-500 z-20 select-none ${
              showControls && !(isBuffering && currentTime < 1 && !errorMessage) ? 'opacity-100' : 'opacity-0 pointer-events-none'
            }`}
            onClick={(e) => e.stopPropagation()}
            style={{ direction: 'rtl' }}
          >
            {/* صف العنوان والأيقونات */}
            <div className="flex items-end justify-between gap-8 mb-6">
              <div className="min-w-0">
                <div className="text-[15px] font-bold text-white/60 mb-1.5">
                  {isLive
                    ? 'بث مباشر'
                    : playlist && playlist.length > 1
                    ? `الحلقة ${Math.max(1, playlist.findIndex((p) => p.id === item.id) + 1)} من ${playlist.length}`
                    : 'فيلم'}
                </div>
                <h2 dir="auto" className="text-[34px] leading-tight font-extrabold text-white truncate max-w-[60vw] atv-text-shadow">
                  {item.title}
                </h2>
              </div>

              <div className="flex items-center gap-2.5 flex-shrink-0">
                {playlist && playlist.length > 1 && (
                  <button tabIndex={0} onClick={() => setShowEpisodesDrawer(!showEpisodesDrawer)} className="atv-osd-pill" title="الحلقات">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
                    <span>الحلقات</span>
                  </button>
                )}
                {isLive && liveChannels.length > 0 && (
                  <button tabIndex={0} onClick={() => setShowChannelDrawer(!showChannelDrawer)} className="atv-osd-pill" title="القنوات">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="6" width="18" height="13" rx="2" /><path d="M8 2l4 4 4-4" /></svg>
                    <span>القنوات</span>
                  </button>
                )}
                <button tabIndex={0} onClick={() => setShowSettingsDrawer(!showSettingsDrawer)} className="atv-osd-pill" title="الصوت والترجمة">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" /><path d="M7 9h4M13 9h4M7 13h6" /></svg>
                  <span>الصوت والترجمة</span>
                </button>
                <button tabIndex={0} onClick={toggleFavorite} className="atv-icon-btn atv-osd-glass w-12 h-12" title={isFavorite ? 'في المفضلة' : 'أضف للمفضلة'}>
                  <svg width="20" height="20" viewBox="0 0 24 24" fill={isFavorite ? '#FF453A' : 'none'} stroke={isFavorite ? '#FF453A' : 'currentColor'} strokeWidth="2.2" strokeLinejoin="round"><path d="M12 21s-7.5-4.6-9.5-9.2C1.2 8.6 3.3 5 6.8 5c2 0 3.5 1.1 5.2 3 1.7-1.9 3.2-3 5.2-3 3.5 0 5.6 3.6 4.3 6.8C19.5 16.4 12 21 12 21z" /></svg>
                </button>
                <button tabIndex={0} onClick={toggleScreenOffMode} className="atv-icon-btn atv-osd-glass w-12 h-12" title="وضع الاستماع (قصص النوم)">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinejoin="round"><path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z" /></svg>
                </button>
                <button tabIndex={0} onClick={() => setIsPipMode(!isPipMode)} className="atv-icon-btn atv-osd-glass w-12 h-12" title="نافذة عائمة">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2" /><rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" /></svg>
                </button>
                <button
                  tabIndex={0}
                  onClick={() => setShowDiagnosticsHud(!showDiagnosticsHud)}
                  className={`atv-icon-btn atv-osd-glass w-12 h-12 ${showDiagnosticsHud ? 'atv-osd-on' : ''}`}
                  title="فحص البث"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M5 20V10M12 20V4M19 20v-7" /></svg>
                </button>
                <button
                  tabIndex={0}
                  onClick={() => {
                    setIsLocked(true);
                    setShowControls(false);
                  }}
                  className="atv-icon-btn atv-osd-glass w-12 h-12"
                  title="قفل الأطفال"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinejoin="round"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 018 0v4" /></svg>
                </button>
              </div>
            </div>

            {/* شريط التقدم (LTR دائماً) */}
            {!isLive && (
              <div dir="ltr" style={{ direction: 'ltr' }} className="mb-7">
                <div
                  className="atv-scrubber relative h-[7px] rounded-full cursor-pointer"
                  onClick={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                    if (videoRef.current && duration > 0) videoRef.current.currentTime = pos * duration;
                  }}
                >
                  <div
                    className="absolute left-0 top-0 bottom-0 bg-white rounded-full"
                    style={{ width: `${duration > 0 ? (currentTime / duration) * 100 : 0}%` }}
                  />
                  <div
                    className="absolute top-1/2 w-[3px] h-5 -translate-y-1/2 bg-white rounded-full shadow"
                    style={{ left: `calc(${duration > 0 ? (currentTime / duration) * 100 : 0}% - 1px)` }}
                  />
                </div>
                <div className="flex justify-between mt-2.5 text-[15px] font-semibold text-white/75 tabular-nums">
                  <span dir="ltr">{formatTime(currentTime)}</span>
                  <span dir="ltr">-{formatTime(Math.max(0, duration - currentTime))}</span>
                </div>
              </div>
            )}

            {/* التايم شفت للبث المباشر */}
            {isLive && (
              <div className="flex items-center justify-center gap-3 mb-7">
                {[
                  [900, '15 د'],
                  [1800, '30 د'],
                  [3600, 'ساعة'],
                ].map(([sec, label]) => (
                  <button
                    key={sec as number}
                    tabIndex={0}
                    onClick={() => timeshiftRewind(sec as number)}
                    className="atv-osd-pill"
                    title={`إرجاع ${label}`}
                  >
                    <span>↺ رجوع {label}</span>
                  </button>
                ))}
                <button tabIndex={0} onClick={returnToLiveEdge} className="atv-osd-pill atv-osd-live" title="العودة للمباشر">
                  <span className="w-2 h-2 rounded-full bg-white" />
                  <span>العودة للمباشر</span>
                </button>
              </div>
            )}

            {/* أزرار التشغيل */}
            <div dir="ltr" style={{ direction: 'ltr' }} className="flex items-center justify-center gap-5">
              {onPrevious && !isLive && (
                <button
                  onClick={() => {
                    lastPositionRef.current = 0;
                    onPrevious();
                  }}
                  tabIndex={0}
                  title="الحلقة السابقة"
                  className="atv-icon-btn atv-osd-glass w-14 h-14"
                >
                  <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z" /></svg>
                </button>
              )}
              {!isLive && (
                <button onClick={() => seek(-10)} tabIndex={0} title="إرجاع 10 ثوانٍ" className="atv-icon-btn atv-osd-glass w-14 h-14">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12a9 9 0 109-9 9.7 9.7 0 00-6.7 2.8L3 8" /><path d="M3 3v5h5" /><text x="12" y="15.5" fontSize="7.5" fontWeight="700" textAnchor="middle" fill="currentColor" stroke="none">10</text></svg>
                </button>
              )}
              <button
                ref={playPauseRef}
                onClick={togglePlay}
                tabIndex={0}
                title={isPlaying ? 'إيقاف مؤقت' : 'تشغيل'}
                className="atv-icon-btn atv-osd-glass w-[76px] h-[76px]"
              >
                {isPlaying ? (
                  <svg className="w-9 h-9" fill="currentColor" viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" /></svg>
                ) : (
                  <svg className="w-9 h-9 translate-x-0.5" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
                )}
              </button>
              {!isLive && (
                <button onClick={() => seek(10)} tabIndex={0} title="تقديم 10 ثوانٍ" className="atv-icon-btn atv-osd-glass w-14 h-14">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 11-9-9 9.7 9.7 0 016.7 2.8L21 8" /><path d="M21 3v5h-5" /><text x="12" y="15.5" fontSize="7.5" fontWeight="700" textAnchor="middle" fill="currentColor" stroke="none">10</text></svg>
                </button>
              )}
              {onNext && !isLive && (
                <button
                  onClick={() => {
                    lastPositionRef.current = 0;
                    onNext();
                  }}
                  tabIndex={0}
                  title="الحلقة التالية"
                  className="atv-icon-btn atv-osd-glass w-14 h-14"
                >
                  <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24"><path d="M5 4l10 8-10 8V4zm14 0h2v16h-2V4z" /></svg>
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
};
