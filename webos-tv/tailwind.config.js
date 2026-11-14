/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    // نطاق على .tsx/.jsx فقط: ملفات src/services/*.ts منطق خالص بدون JSX،
    // ومسحها كان يجعل Tailwind يلتقط بالخطأ نصوص Regex عادية مثل "[-:–|.]"
    // ويولّد منها قاعدة CSS فاسدة تسبب تحذير أثناء البناء
    "./src/**/*.{jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        tajawal: ['Tajawal', 'sans-serif'],
      },
      colors: {
        // ثيم شاهد (افتراضي)
        shahid: {
          bg: '#181C2B',
          surface: '#212639',
          surface2: '#2E3449',
          accent: '#2FD3BF',
          accentDark: '#0E1A24',
          textMain: '#FFFFFF',
          textDim: '#A7ADBF',
        },
        // ثيم نتفلكس
        netflix: {
          bg: '#141414',
          surface: '#1F1F1F',
          surface2: '#333333',
          accent: '#E50914',
          textMain: '#FFFFFF',
          textDim: '#B3B3B3',
        },
        // ألوان الهب الحديثة
        hub: {
          bg: '#0C111D',
          rail: '#111827',
          card: '#182033',
          card2: '#222C44',
          pink: '#E23C7E',
          purple: '#8B5CF6',
          dim: '#98A2B8',
        }
      },
    },
  },
  plugins: [],
}
