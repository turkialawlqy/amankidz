import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import legacy from '@vitejs/plugin-legacy';

// شاشات LG webOS القديمة (3.x/4.x تعتمد على Chromium 38-53) لا تدعم
// <script type="module"> إطلاقاً، فبدون نسخة احتياطية (nomodule) يبقى التطبيق
// شاشة سوداء فارغة عليها رغم أنه يعمل بشكل طبيعي في متصفح حديث أثناء التطوير
const legacyWebosTargets = ['chrome >= 38', 'safari >= 9'];

// شاشات webOS تُحمّل التطبيق من ملف محلي (Origin: null)، وبعض نسخ الـ WebKit
// المدمجة ترفض تنفيذ <script type="module" crossorigin> في هذا السياق؛
// نزيل الخاصية من الناتج النهائي لأنها غير ضرورية أصلاً لتطبيق محلي بالكامل
function stripCrossorigin(): Plugin {
  return {
    name: 'strip-crossorigin-for-webos',
    // لازم تعمل بعد plugin-legacy حتى تشمل وسوم <script nomodule crossorigin>
    // التي يضيفها ذلك الإضافة بنفسها في مرحلة "post"
    enforce: 'post',
    transformIndexHtml(html) {
      return html.replace(/\s+crossorigin(="[^"]*")?/g, '');
    },
  };
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    legacy({ targets: legacyWebosTargets }),
    stripCrossorigin(),
  ],
  base: './', // مهم جداً لشاشات webOS لتعمل المسارات المحلية النسبية بشكل صحيح
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    // plugin-legacy يضبط الهدف المناسب للحزمتين (الحديثة والقديمة) بنفسه
  },
  server: {
    port: 3000,
  },
});
