/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL: string;
  readonly VITE_SUPABASE_ANON_KEY: string;
  readonly VITE_PARENT_SITE_URL: string;
  // اختياري تماماً: بدونه تختفي شارات التقييم وصور طاقم التمثيل بأمان (انظر src/services/tmdb.ts)
  readonly VITE_TMDB_API_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
