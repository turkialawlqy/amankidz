import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { spawnSync } from 'child_process';

// نفضّل أداة webOS CLI الرسمية (ares-package) عند توفرها على الجهاز بدل
// الباني اليدوي أدناه، لأنها الأداة المعتمدة من LG وتضمن توافقاً كاملاً
// يُرجع 'ok' | 'not-found' | 'failed' حتى تكون رسالة التراجع دقيقة (أداة غير
// مثبّتة هو سبب مختلف تماماً عن أداة موجودة لكنها فشلت لسبب آخر في appinfo.json)
function tryOfficialAresPackage() {
  const check = spawnSync('ares-package', ['--version'], { stdio: 'ignore', shell: true });
  if (check.error || check.status !== 0) return 'not-found';

  console.log('🔧 وُجدت أداة ares-package الرسمية — سيتم استخدامها بدل الباني اليدوي...');
  const res = spawnSync('ares-package', ['dist', '-o', '.'], { stdio: 'inherit', shell: true });
  return !res.error && res.status === 0 ? 'ok' : 'failed';
}

// دالة لكتابة ترويسة TAR (USTAR format)
function createTarHeader(name, size, type = '0', mode = 0o644) {
  const buf = Buffer.alloc(512);

  // name (100)
  buf.write(name, 0, 100, 'utf8');
  // mode (8)
  buf.write(mode.toString(8).padStart(7, '0') + ' ', 100, 8, 'utf8');
  // uid (8)
  buf.write('0000000 ', 108, 8, 'utf8');
  // gid (8)
  buf.write('0000000 ', 116, 8, 'utf8');
  // size (12)
  buf.write(size.toString(8).padStart(11, '0') + ' ', 124, 12, 'utf8');
  // mtime (12)
  const mtime = Math.floor(Date.now() / 1000);
  buf.write(mtime.toString(8).padStart(11, '0') + ' ', 136, 12, 'utf8');
  // chksum (8 spaces initially)
  buf.write('        ', 148, 8, 'utf8');
  // typeflag (1)
  buf.write(type, 156, 1, 'utf8');
  // magic (6) + version (2)
  buf.write('ustar  \0', 257, 8, 'utf8');
  // uname (32)
  buf.write('root', 265, 32, 'utf8');
  // gname (32)
  buf.write('root', 297, 32, 'utf8');

  // calculate checksum
  let sum = 0;
  for (let i = 0; i < 512; i++) {
    sum += buf[i];
  }
  buf.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'utf8');

  return buf;
}

// إنشاء ملف TAR في الذاكرة
function createTar(entries) {
  const chunks = [];
  for (const entry of entries) {
    const isDir = entry.type === '5';
    const dataBuf = isDir ? Buffer.alloc(0) : entry.data;
    const header = createTarHeader(entry.name, dataBuf.length, entry.type || '0', entry.mode);
    chunks.push(header);
    if (!isDir && dataBuf.length > 0) {
      chunks.push(dataBuf);
      const pad = 512 - (dataBuf.length % 512);
      if (pad < 512) {
        chunks.push(Buffer.alloc(pad));
      }
    }
  }
  // نهايتين فارغتين بحجم 512 بايت
  chunks.push(Buffer.alloc(1024));
  return Buffer.concat(chunks);
}

// دالة لكتابة ترويسة AR (Debian package archive)
function createArHeader(filename, size) {
  const buf = Buffer.alloc(60);
  buf.write(filename.padEnd(16, ' '), 0, 16, 'utf8');
  buf.write(Math.floor(Date.now() / 1000).toString().padEnd(12, ' '), 16, 12, 'utf8');
  buf.write('0'.padEnd(6, ' '), 28, 6, 'utf8'); // uid
  buf.write('0'.padEnd(6, ' '), 34, 6, 'utf8'); // gid
  buf.write('100644'.padEnd(8, ' '), 40, 8, 'utf8'); // mode
  buf.write(size.toString().padEnd(10, ' '), 48, 10, 'utf8'); // size
  buf.write('\x60\n', 58, 2, 'utf8'); // magic end
  return buf;
}

function createAr(files) {
  const chunks = [Buffer.from('!<arch>\n', 'utf8')];
  for (const f of files) {
    chunks.push(createArHeader(f.name, f.data.length));
    chunks.push(f.data);
    if (f.data.length % 2 !== 0) {
      chunks.push(Buffer.from('\n', 'utf8')); // محاذاة زوجية
    }
  }
  return Buffer.concat(chunks);
}

// امتدادات ملفات حزم التطبيق النهائية التي يجب استبعادها من المحتوى حتى لا تحشر
// نسخة من حزمة (ipk/wgt) القديمة داخل الحزمة الجديدة عند إعادة البناء دون تنظيف dist/
const PACKAGE_FILE_EXTENSIONS = ['.ipk', '.wgt'];

// جمع ملفات مجلد dist
function walkDir(dir, baseDir = '') {
  let results = [];
  const list = fs.readdirSync(dir);
  for (const file of list) {
    if (PACKAGE_FILE_EXTENSIONS.some((ext) => file.endsWith(ext))) continue;
    const filePath = path.join(dir, file);
    const relPath = path.join(baseDir, file).replace(/\\/g, '/');
    const stat = fs.statSync(filePath);
    if (stat.isDirectory()) {
      results.push({ name: relPath + '/', type: '5', mode: 0o755 });
      results = results.concat(walkDir(filePath, relPath));
    } else {
      results.push({ name: relPath, data: fs.readFileSync(filePath), type: '0', mode: 0o644 });
    }
  }
  return results;
}

async function buildIpk() {
  const appinfo = JSON.parse(fs.readFileSync('appinfo.json', 'utf8'));
  const appId = appinfo.id;
  const appVersion = appinfo.version || '1.0.0';
  const ipkName = `${appId}_${appVersion}_all.ipk`;

  const aresResult = tryOfficialAresPackage();
  if (aresResult === 'ok') {
    console.log('✅ تم إنشاء حزمة IPK عبر ares-package الرسمية.');
    return;
  }

  console.log(`Packaging webOS IPK for ${appId} v${appVersion}...`);
  console.log(
    aresResult === 'failed'
      ? '   (ares-package مثبّتة لكنها فشلت أعلاه؛ سيُستخدم الباني اليدوي البديل — راجع رسالة الخطأ أعلاه)'
      : '   (ares-package غير مثبّتة على هذا الجهاز؛ سيُستخدم الباني اليدوي البديل)'
  );

  // 1) debian-binary
  const debianBinary = Buffer.from('2.0\n', 'utf8');

  // 2) control.tar.gz
  const controlContent = `Package: ${appId}
Version: ${appVersion}
Section: misc
Priority: optional
Architecture: all
Maintainer: AmanKidz <support@nafasoud.com>
Description: AmanKidz Smart TV App for webOS
Installed-Size: 5000
`;
  const controlTar = createTar([
    { name: './', type: '5', mode: 0o755 },
    { name: './control', data: Buffer.from(controlContent, 'utf8'), type: '0', mode: 0o644 },
  ]);
  const controlTarGz = zlib.gzipSync(controlTar);

  // 3) data.tar.gz
  const targetPrefix = `./usr/palm/applications/${appId}/`;
  const distEntries = walkDir('dist');
  const dataTarEntries = [
    { name: './', type: '5', mode: 0o755 },
    { name: './usr/', type: '5', mode: 0o755 },
    { name: './usr/palm/', type: '5', mode: 0o755 },
    { name: './usr/palm/applications/', type: '5', mode: 0o755 },
    { name: targetPrefix, type: '5', mode: 0o755 },
  ];

  for (const entry of distEntries) {
    dataTarEntries.push({
      ...entry,
      name: targetPrefix + entry.name,
    });
  }

  const dataTar = createTar(dataTarEntries);
  const dataTarGz = zlib.gzipSync(dataTar);

  // 4) تجميع ملف .ipk كأرشيف AR
  const ipkData = createAr([
    { name: 'debian-binary', data: debianBinary },
    { name: 'control.tar.gz', data: controlTarGz },
    { name: 'data.tar.gz', data: dataTarGz },
  ]);

  // حفظ الملف في مجلد webos-tv ومجلد dist
  fs.writeFileSync(ipkName, ipkData);
  fs.writeFileSync(path.join('dist', ipkName), ipkData);

  const stats = fs.statSync(ipkName);
  console.log(`✅ Successfully generated IPK package:`);
  console.log(`   📁 File: ${ipkName}`);
  console.log(`   📏 Size: ${(stats.size / 1024).toFixed(1)} KB`);
  console.log(`   📍 Copied to: ${path.resolve(ipkName)}`);
  console.log(`   📍 Also in: ${path.resolve('dist', ipkName)}`);
}

buildIpk().catch((err) => {
  console.error('Packaging failed:', err);
  process.exit(1);
});
