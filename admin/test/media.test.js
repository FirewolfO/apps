import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createApplication } from '../src/app.js';

test('published media supports seeking and never exposes unrelated data or an HTML fallback', async t => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'film-media-'));
  const media = path.join(dataDir, 'media/friends/20260916-v1/S01E01');
  await fs.mkdir(media, { recursive: true });
  await fs.writeFile(path.join(media, 'audio.m4a'), '0123456789');
  await fs.writeFile(path.join(media, 'transcript.txt'), 'Hello\n你好\fBye\n再见');
  await fs.writeFile(path.join(media, '.private'), 'private');
  const app = await createApplication({ dataDir, maxApkMb: 5 });
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    app.locals.closeXiaolinSync();
    await fs.rm(dataDir, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${server.address().port}/media/friends/20260916-v1/S01E01`;
  const audio = await fetch(`${base}/audio.m4a`, { headers: { Range: 'bytes=2-5' } });
  assert.equal(audio.status, 206);
  assert.equal(audio.headers.get('content-type'), 'audio/mp4');
  assert.equal(audio.headers.get('content-range'), 'bytes 2-5/10');
  assert.match(audio.headers.get('cache-control'), /immutable/);
  assert.equal(await audio.text(), '2345');
  const head = await fetch(`${base}/audio.m4a`, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), '10');
  assert.equal(await head.text(), '');
  const subtitle = await fetch(`${base}/transcript.txt`);
  assert.match(subtitle.headers.get('content-type'), /^text\/plain; charset=utf-8$/);
  assert.equal(await subtitle.text(), 'Hello\n你好\fBye\n再见');
  assert.equal((await fetch(`${base}/audio.m4a`, { headers: { Range: 'bytes=20-30' } })).status, 416);
  for (const suffix of ['/missing.m4a', '/', '/.private', '/..%2f..%2f..%2f..%2findex.json']) {
    const response = await fetch(base + suffix, { redirect: 'manual' });
    assert.ok([403, 404].includes(response.status), `${suffix}: ${response.status}`);
    assert.doesNotMatch(await response.text(), /App Center|private|"apps"/);
  }
});
