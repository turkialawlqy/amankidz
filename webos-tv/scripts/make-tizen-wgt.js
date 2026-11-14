import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { spawnSync } from 'child_process';

/**
 * منشئ حزمة Tizen Smart TV (.wgt) بدون أي اعتماديات خارجية — يُستخدم فقط
 * كبديل احتياطي حين لا تتوفر أداة Tizen CLI الرسمية (انظر tryOfficialTizenCli
 * أدناه)، لأن الحزمة الناتجة هنا غير موقّعة ولا تُثبَّت على جهاز حقيقي.
 * صيغة .wgt هي حزمة ZIP قياسية تحتوي على config.xml وملفات الويب
 */

// نفضّل أداة Tizen Studio CLI الرسمية عند توفرها، لأنها الوحيدة القادرة على
// توقيع الحزمة فعلياً (باستخدام بروفايل التوقيع الافتراضي المُعد مسبقاً)
function tryOfficialTizenCli() {
  const check = spawnSync('tizen', ['version'], { stdio: 'ignore', shell: true });
  if (check.error || check.status !== 0) return false;

  console.log('🔧 وُجدت أداة Tizen CLI الرسمية — سيتم تجربة التعبئة والتوقيع بها...');
  const res = spawnSync('tizen', ['package', '-t', 'wgt', '--', 'dist'], {
    stdio: 'inherit',
    shell: true,
  });
  if (res.error || res.status !== 0) {
    console.warn(
      '⚠️  فشلت Tizen CLI (على الأغلب لا يوجد بروفايل توقيع افتراضي مُعدّ في Tizen Studio).'
    );
    console.warn('   سيُستخدم الباني اليدوي البديل (حزمة غير موقّعة للمرجع فقط).');
    return false;
  }
  return true;
}

function createZip(entries) {
  const localChunks = [];
  const cdChunks = [];
  let offset = 0;

  for (const entry of entries) {
    const isDir = entry.isDir;
    const nameBuf = Buffer.from(entry.name, 'utf8');
    const rawData = isDir ? Buffer.alloc(0) : entry.data;

    let compData = rawData;
    let method = 0; // Store (no compress) for small or dirs
    if (!isDir && rawData.length > 32) {
      compData = zlib.deflateRawSync(rawData);
      method = 8; // Deflated
    }

    const crc = crc32(rawData);

    // Local file header (30 bytes + name)
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); // signature
    lh.writeUInt16LE(20, 4); // version needed
    lh.writeUInt16LE(0, 6); // general flags
    lh.writeUInt16LE(method, 8); // compression method
    lh.writeUInt16LE(0, 10); // file time
    lh.writeUInt16LE(0, 12); // file date
    lh.writeUInt32LE(crc, 14); // crc32
    lh.writeUInt32LE(compData.length, 18); // comp size
    lh.writeUInt32LE(rawData.length, 22); // uncomp size
    lh.writeUInt16LE(nameBuf.length, 26); // name length
    lh.writeUInt16LE(0, 28); // extra field len

    localChunks.push(lh, nameBuf, compData);

    // Central directory header (46 bytes + name)
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0); // signature
    cd.writeUInt16LE(20, 4); // version made by
    cd.writeUInt16LE(20, 6); // version needed
    cd.writeUInt16LE(0, 8); // flags
    cd.writeUInt16LE(method, 10); // method
    cd.writeUInt16LE(0, 12); // time
    cd.writeUInt16LE(0, 14); // date
    cd.writeUInt32LE(crc, 16); // crc32
    cd.writeUInt32LE(compData.length, 20); // comp size
    cd.writeUInt32LE(rawData.length, 24); // uncomp size
    cd.writeUInt16LE(nameBuf.length, 28); // name len
    cd.writeUInt16LE(0, 30); // extra len
    cd.writeUInt16LE(0, 32); // comment len
    cd.writeUInt16LE(0, 34); // disk start
    cd.writeUInt16LE(0, 36); // internal attrs
    cd.writeUInt32LE(isDir ? 0x10 : 0x20, 38); // external attrs
    cd.writeUInt32LE(offset, 42); // offset of local header

    cdChunks.push(cd, nameBuf);

    offset += lh.length + nameBuf.length + compData.length;
  }

  const cdTotalSize = cdChunks.reduce((acc, c) => acc + c.length, 0);
  const cdOffset = offset;

  // End of central directory record (22 bytes)
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4); // disk num
  eocd.writeUInt16LE(0, 6); // cd disk num
  eocd.writeUInt16LE(entries.length, 8); // entries on disk
  eocd.writeUInt16LE(entries.length, 10); // total entries
  eocd.writeUInt32LE(cdTotalSize, 12); // cd size
  eocd.writeUInt32LE(cdOffset, 16); // cd offset
  eocd.writeUInt16LE(0, 20); // comment len

  return Buffer.concat([...localChunks, ...cdChunks, eocd]);
}

// CRC-32 حساب
function crc32(buf) {
  let crc = -1;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

const crcTable = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  crcTable[i] = c;
}

// امتدادات ملفات حزم التطبيق النهائية التي يجب استبعادها من المحتوى حتى لا تحشر
// نسخة من حزمة (ipk/wgt) القديمة داخل الحزمة الجديدة عند إعادة البناء دون تنظيف dist/
const PACKAGE_FILE_EXTENSIONS = ['.ipk', '.wgt'];

function walkDir(dir, baseDir = '') {
  let results = [];
  const list = fs.readdirSync(dir);
  for (const file of list) {
    if (PACKAGE_FILE_EXTENSIONS.some((ext) => file.endsWith(ext))) continue;
    const filePath = path.join(dir, file);
    const relPath = path.join(baseDir, file).replace(/\\/g, '/');
    const stat = fs.statSync(filePath);
    if (stat.isDirectory()) {
      results.push({ name: relPath + '/', isDir: true });
      results = results.concat(walkDir(filePath, relPath));
    } else {
      results.push({ name: relPath, isDir: false, data: fs.readFileSync(filePath) });
    }
  }
  return results;
}

export function buildTizenPackage() {
  const rootDir = process.cwd();
  const distDir = path.join(rootDir, 'dist');
  const configXml = path.join(rootDir, 'config.xml');
  const appinfo = JSON.parse(fs.readFileSync(path.join(rootDir, 'appinfo.json'), 'utf8'));

  if (!fs.existsSync(distDir)) {
    console.error('dist/ does not exist. Run build first.');
    return;
  }

  // نسخ config.xml إلى dist/ (مطلوب أيضاً قبل تجربة Tizen CLI الرسمية)
  if (fs.existsSync(configXml)) {
    fs.copyFileSync(configXml, path.join(distDir, 'config.xml'));
  }

  if (tryOfficialTizenCli()) {
    console.log('✅ تم إنشاء حزمة Tizen موقّعة عبر Tizen CLI الرسمية.');
    return;
  }

  // ملف "mimetype" يجب أن يكون أول عنصر في الأرشيف وبدون ضغط، وفق مواصفة
  // W3C Widget Packaging التي تعتمد عليها حزم Tizen (.wgt) — كانت مفقودة سابقاً
  const mimetypeEntry = {
    name: 'mimetype',
    isDir: false,
    data: Buffer.from('application/widget', 'utf8'),
  };

  const entries = [mimetypeEntry, ...walkDir(distDir)];
  const zipBuffer = createZip(entries);

  const wgtName = `${appinfo.id}_${appinfo.version}.wgt`;
  const outPath1 = path.join(rootDir, wgtName);
  const outPath2 = path.join(distDir, wgtName);

  fs.writeFileSync(outPath1, zipBuffer);
  fs.writeFileSync(outPath2, zipBuffer);

  console.log(`✅ Successfully generated Samsung Tizen package:`);
  console.log(`   📁 File: ${wgtName}`);
  console.log(`   📏 Size: ${(zipBuffer.length / 1024).toFixed(1)} KB`);
  console.log(`   📍 Copied to: ${outPath1}`);
  console.log(`   ⚠️  هذه الحزمة غير موقّعة (unsigned) — Tizen الحقيقي على تلفزيونات Samsung`);
  console.log(`      يرفض تثبيت أي حزمة بدون شهادة توقيع. لتثبيتها فعلياً استخدم Tizen Studio`);
  console.log(`      أو أداة "tizen package" الرسمية مع شهادة مطوّر (Author Certificate)،`);
  console.log(`      هذا الملف صالح فقط كمرجع لمحتوى الحزمة وليس للتثبيت المباشر.`);
}

buildTizenPackage();
