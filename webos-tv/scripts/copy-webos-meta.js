import fs from 'fs';
import path from 'path';

const filesToCopy = ['appinfo.json', 'icon.png', 'largeIcon.png'];

filesToCopy.forEach((file) => {
  const src = path.resolve(file);
  const dest = path.resolve('dist', file);
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dest);
    console.log(`Copied ${file} to dist/`);
  }
});
