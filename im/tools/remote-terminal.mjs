import fs from 'node:fs';
import { chromium } from '/data00/home/liuxing.110/code/ai-workbench/frontend/node_modules/playwright-core/index.mjs';

const target = process.env.TTY_URL;
const auth = process.env.TTY_AUTH || '';
const [username, ...passwordParts] = auth.split(':');
const password = passwordParts.join(':');
const command = process.argv.slice(2).join(' ');

if (!target || !username || !password || !command) {
  console.error('Usage: TTY_URL=https://... TTY_AUTH=user:pass node remote-terminal.mjs command');
  process.exit(2);
}

const browser = await chromium.launch({
  headless: true,
  executablePath: '/data00/home/liuxing.110/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
  args: ['--no-sandbox']
});

try {
  const context = await browser.newContext({
    httpCredentials: { username, password },
    viewport: { width: 1400, height: 900 }
  });
  const page = await context.newPage();
  let terminalOutput = '';
  page.on('websocket', socket => {
    socket.on('framereceived', frame => {
      const payload = Buffer.isBuffer(frame.payload) ? frame.payload : Buffer.from(frame.payload);
      if (payload.length > 1) terminalOutput += payload.subarray(1).toString('utf8');
    });
  });
  await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.locator('.xterm-helper-textarea').waitFor({ timeout: 15_000 });
  await page.waitForTimeout(500);
  const marker = `__LINKUP_DONE_${Date.now()}__`;
  const input = page.locator('.xterm-helper-textarea');
  await input.focus();
  await page.keyboard.press('Control+C');
  await page.waitForTimeout(200);
  await page.keyboard.insertText('stty -echo');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  terminalOutput = '';
  await page.keyboard.insertText(`${command}; rc=$?; stty echo; printf '\\n${marker}:%s\\n' "$rc"`);
  await page.keyboard.press('Enter');

  const deadline = Date.now() + Number(process.env.TTY_TIMEOUT_MS || 120_000);
  while (!terminalOutput.includes(marker) && Date.now() < deadline) await page.waitForTimeout(100);
  if (!terminalOutput.includes(marker)) throw new Error('Remote command timed out');

  const clean = terminalOutput
    .replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g, '')
    .replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\r/g, '');
  const markerIndex = clean.indexOf(marker);
  process.stdout.write(clean.slice(0, markerIndex));
  const status = Number(clean.slice(markerIndex + marker.length + 1).match(/^\d+/)?.[0] || 1);
  process.exitCode = status;
} finally {
  await browser.close();
}
