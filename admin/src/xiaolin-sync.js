import crypto from 'node:crypto';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import express from 'express';

const MAX_PAGES = 2000;
const MAX_CLOCK = 1_000_000_000_000;
const fields = ['visit', 'completion', 'position'];
const emptyState = () => ({ version: 1, full: {}, compact: {} });
const fail = (status, message) => Object.assign(new Error(message), { status });
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const integer = (value, max) => Number.isSafeInteger(value) && value >= 0 && value <= max;

function validField(name, field) {
  if (!object(field) || !integer(field.clock, MAX_CLOCK)
      || typeof field.device !== 'string' || !/^[a-f0-9]{32}$/.test(field.device)
      || !object(field.value)) return false;
  const value = field.value;
  if (name === 'visit') return typeof value.title === 'string' && value.title.length <= 500
    && integer(value.at, 8_640_000_000_000_000);
  if (name === 'completion') return typeof value.done === 'boolean'
    && integer(value.at, 8_640_000_000_000_000);
  return integer(value.y, 100_000_000) && typeof value.fraction === 'number'
    && Number.isFinite(value.fraction) && value.fraction >= -1 && value.fraction <= 1;
}

export function validateState(state) {
  if (!object(state) || state.version !== 1) throw fail(400, '不支持的进度格式，请更新应用');
  for (const mode of ['full', 'compact']) {
    if (!object(state[mode]) || Object.keys(state[mode]).length > MAX_PAGES) throw fail(400, '学习记录数量超限');
    for (const [url, record] of Object.entries(state[mode])) {
      let parsed;
      try { parsed = new URL(url); } catch { throw fail(400, '学习页面地址无效'); }
      if (url.length > 2048 || !['http:', 'https:'].includes(parsed.protocol)
          || parsed.username || parsed.password || !object(record)) throw fail(400, '学习页面地址无效');
      if (Object.keys(record).some(name => !fields.includes(name))
          || !Object.keys(record).length) throw fail(400, '学习记录无效');
      for (const name of fields) {
        if (record[name] !== undefined && !validField(name, record[name])) throw fail(400, '学习记录无效');
      }
    }
  }
  return state;
}

// Independent registers keep a visit or scroll from overwriting completion.
// Lamport clocks also work offline and are independent of device wall clocks.
export function mergeState(local, incoming) {
  const merged = structuredClone(local);
  for (const mode of ['full', 'compact']) {
    for (const [url, record] of Object.entries(incoming[mode])) {
      const target = merged[mode][url] ??= {};
      for (const name of fields) {
        const next = record[name];
        const prior = target[name];
        if (next && (!prior || next.clock > prior.clock
            || (next.clock === prior.clock && next.device > prior.device))) {
          target[name] = structuredClone(next);
        }
      }
    }
    if (Object.keys(merged[mode]).length > MAX_PAGES) throw fail(413, '同步空间学习记录已达上限');
  }
  if (Buffer.byteLength(JSON.stringify(merged)) > 2 * 1024 * 1024) throw fail(413, '同步空间容量已达上限');
  return merged;
}

export function createXiaolinSync(dataDir) {
  const db = new DatabaseSync(path.join(dataDir, 'xiaolin-progress.sqlite'));
  db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; CREATE TABLE IF NOT EXISTS spaces (key TEXT PRIMARY KEY, state TEXT NOT NULL)');
  const router = express.Router();
  const limits = new Map();
  router.use((_request, response, next) => {
    response.set('Cache-Control', 'no-store');
    next();
  });
  // Deliberately use the socket peer: untrusted forwarded headers cannot bypass limits.
  const rateLimit = (bucket, maximum, window) => (request, response, next) => {
    const now = Date.now();
    for (const [key, value] of limits) if (value.until <= now) limits.delete(key);
    const key = `${bucket}:${request.ip}`;
    const value = limits.get(key) || { count: 0, until: now + window };
    if (++value.count > maximum || limits.size > 10_000) {
      response.set('Retry-After', String(Math.max(1, Math.ceil((value.until - now) / 1000))));
      return response.status(429).json({ error: '同步请求较多，请稍后重试' });
    }
    limits.set(key, value);
    next();
  };
  router.use(rateLimit('requests', 600, 60_000));
  router.use(express.json({ limit: '2mb' }));
  router.post('/spaces', rateLimit('create', 20, 3_600_000), (_request, response) => {
    if (db.prepare('SELECT COUNT(*) AS count FROM spaces').get().count >= 10_000) {
      return response.status(503).json({ error: '暂时无法创建同步空间，请稍后重试' });
    }
    const code = crypto.randomBytes(16).toString('hex');
    const key = crypto.createHash('sha256').update(code).digest('hex');
    const state = emptyState();
    db.prepare('INSERT INTO spaces (key, state) VALUES (?, ?)').run(key, JSON.stringify(state));
    response.status(201).json({ code, state });
  });
  router.post('/progress', (request, response) => {
    const code = String(request.get('authorization') || '').replace(/^Bearer\s+/i, '');
    if (!/^[a-f0-9]{32}$/.test(code)) return response.status(401).json({ error: '同步码格式无效' });
    const key = crypto.createHash('sha256').update(code).digest('hex');
    const incoming = validateState(request.body);
    db.exec('BEGIN IMMEDIATE');
    try {
      const row = db.prepare('SELECT state FROM spaces WHERE key = ?').get(key);
      if (!row) throw fail(404, '同步码不存在，请检查后重试');
      const state = mergeState(JSON.parse(row.state), incoming);
      const encoded = JSON.stringify(state);
      if (encoded !== row.state) db.prepare('UPDATE spaces SET state = ? WHERE key = ?').run(encoded, key);
      db.exec('COMMIT');
      response.json(state);
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  });
  router.use((error, _request, response, _next) => {
    // Do not log request bodies, learning URLs or pairing credentials.
    const status = error.status || 500;
    response.status(status).json({ error: status >= 500 ? '进度同步暂不可用，请稍后重试'
      : status === 413 ? '同步数据过大' : status === 400 ? '同步数据无效，请检查或更新应用'
        : error.message });
  });
  return { router, close: () => db.close() };
}
