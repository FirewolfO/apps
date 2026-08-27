import fs from 'node:fs';
import { chromium } from '/data00/home/liuxing.110/code/ai-workbench/frontend/node_modules/playwright-core/index.mjs';

const target = process.env.TTY_URL;
const auth = process.env.TTY_AUTH || '';
const [username, ...passwordParts] = auth.split(':');
const password = passwordParts.join(':');
const [localFile, remoteFile] = process.argv.slice(2);

if (!target || !username || !password || !localFile || !remoteFile) {
  console.error('Usage: TTY_URL=https://... TTY_AUTH=user:pass node remote-upload.mjs local remote');
  process.exit(2);
}

const encoded = fs.readFileSync(localFile).toString('base64');
const chunks = encoded.match(/.{1,2000}/g) || [];
const tempFile = `/tmp/linkup-upload-${Date.now()}.b64`;
const browser = await chromium.launch({
  headless: true,
  executablePath: '/data00/home/liuxing.110/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
  args: ['--no-sandbox']
});

try {
  const context = await browser.newContext({ httpCredentials: { username, password } });
  const page = await context.newPage();
  let output = '';
  page.on('websocket', socket => socket.on('framereceived', frame => {
    const payload = Buffer.isBuffer(frame.payload) ? frame.payload : Buffer.from(frame.payload);
    if (payload.length > 1) output += payload.subarray(1).toString('utf8');
  }));
  await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  const input = page.locator('.xterm-helper-textarea');
  await input.waitFor({ timeout: 15_000 });
  await input.focus();
  await page.keyboard.insertText('stty -echo');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  output = '';
  await page.keyboard.insertText(`: > ${tempFile}`);
  await page.keyboard.press('Enter');
  for (const chunk of chunks) {
    await page.keyboard.insertText(`printf '%s' '${chunk}' >> ${tempFile}`);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(30);
  }
  const marker = `__UPLOAD_DONE_${Date.now()}__`;
  await page.keyboard.insertText(`openssl base64 -d -A -in ${tempFile} -out ${remoteFile} && rm -f ${tempFile}; rc=$?; stty echo; printf '\\n${marker}:%s\\n' "$rc"`);
  await page.keyboard.press('Enter');
  const deadline = Date.now() + 120_000;
  while (!output.includes(marker) && Date.now() < deadline) await page.waitForTimeout(100);
  if (!output.includes(`${marker}:0`)) throw new Error('Remote upload failed');
  console.log(`Uploaded ${fs.statSync(localFile).size} bytes to ${remoteFile}`);
} finally {
  await browser.close();
}
