import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import express from 'express';
import { createXiaolinSync } from '../src/xiaolin-sync.js';

const empty = () => ({ version: 1, full: {}, compact: {} });
const field = (clock, device, value) => ({ clock, device: device.repeat(32), value });
const url = 'https://www.xiaolincoding.com/network/tcp.html';

test('devices converge, preserve offline edits and revocations, isolate codes, and survive restart', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'xiaolin-sync-'));
  let sync;
  let server;
  let base;
  const start = async () => {
    sync = createXiaolinSync(dir);
    const app = express();
    app.use(sync.router);
    server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    base = `http://127.0.0.1:${server.address().port}`;
  };
  const stop = async () => {
    await new Promise(resolve => server.close(resolve));
    sync.close();
  };
  await start();
  t.after(async () => { await stop(); await fs.rm(dir, { recursive: true, force: true }); });
  const post = async (route, code, state = empty()) => {
    const response = await fetch(base + route, { method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${code || ''}` },
      body: JSON.stringify(state) });
    assert.equal(response.headers.get('cache-control'), 'no-store');
    return { status: response.status, body: await response.json() };
  };
  const created = await post('/spaces');
  assert.equal(created.status, 201);
  const { code } = created.body;
  assert.match(code, /^[a-f0-9]{32}$/);
  const a = empty();
  a.full[url] = { completion: field(1, 'a', { done: true, at: 100 }) };
  await post('/progress', code, a);
  const b = empty();
  b.full[url] = { position: field(1, 'b', { y: 400, fraction: 0.5 }) };
  b.compact[url] = { completion: field(1, 'b', { done: true, at: 200 }) };
  const combined = (await post('/progress', code, b)).body;
  assert.equal(combined.full[url].completion.value.done, true);
  assert.equal(combined.full[url].position.value.fraction, 0.5);
  const revoked = structuredClone(combined);
  revoked.full[url].completion = field(2, 'a', { done: false, at: 300 });
  await post('/progress', code, revoked);
  const stale = (await post('/progress', code, a)).body;
  assert.equal(stale.full[url].completion.value.done, false);
  assert.equal(stale.compact[url].completion.value.done, true);
  assert.deepEqual((await post('/progress', code, stale)).body, stale);
  const other = (await post('/spaces')).body.code;
  assert.deepEqual((await post('/progress', other)).body, empty());
  assert.equal((await post('/progress', 'c'.repeat(32))).status, 404);
  assert.equal((await post('/progress', '../../etc/passwd')).status, 401);
  const invalid = empty();
  invalid.full[url] = { position: field(1, 'a', { y: -1, fraction: 3 }) };
  assert.equal((await post('/progress', code, invalid)).status, 400);
  assert.equal((await post('/progress', code, { ...empty(), version: 2 })).status, 400);
  await stop();
  await start();
  assert.deepEqual((await post('/progress', code)).body, stale);
  const bytes = await fs.readFile(path.join(dir, 'xiaolin-progress.sqlite'));
  assert.equal(bytes.includes(Buffer.from(code)), false);
});

test('same-clock conflicts converge regardless of delivery order and oversized requests fail', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'xiaolin-sync-'));
  const sync = createXiaolinSync(dir);
  const app = express();
  app.use(sync.router);
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); sync.close(); await fs.rm(dir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (route, code, state) => fetch(base + route, { method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${code}` },
    body: JSON.stringify(state || empty()) });
  const code1 = (await (await post('/spaces')).json()).code;
  const code2 = (await (await post('/spaces')).json()).code;
  const a = empty();
  const b = empty();
  a.full[url] = { completion: field(5, 'a', { done: true, at: 100 }) };
  b.full[url] = { completion: field(5, 'b', { done: false, at: 100 }) };
  await post('/progress', code1, a);
  await post('/progress', code1, b);
  await post('/progress', code2, b);
  await post('/progress', code2, a);
  assert.deepEqual(await (await post('/progress', code1)).json(), await (await post('/progress', code2)).json());
  assert.equal((await post('/progress', code1, { ...empty(), padding: 'x'.repeat(2 * 1024 * 1024) })).status, 413);
  const many = empty();
  for (let i = 0; i < 2001; i++) many.full[`${url}?id=${i}`] = a.full[url];
  assert.equal((await post('/progress', code1, many)).status, 400);
});
