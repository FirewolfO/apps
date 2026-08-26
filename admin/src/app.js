import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { AppStore } from './store.js';

function equal(left, right) {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
export async function createApplication(config) {
  const store = new AppStore(config.dataDir);
  await store.init();
  const sessions = new Map();
  const attempts = new Map();
  const tempDir = path.join(config.dataDir, 'temporary');
  await fs.mkdir(tempDir, { recursive: true });

  const upload = multer({
    dest: tempDir,
    limits: { files: 1, fileSize: config.maxApkMb * 1024 * 1024 },
    fileFilter: (_request, file, callback) => callback(null,
      file.originalname.toLowerCase().endsWith('.apk')
      && ['application/vnd.android.package-archive', 'application/octet-stream'].includes(file.mimetype)),
  });
  const app = express();
  app.disable('x-powered-by');
  app.use((request, response, next) => {
    response.set({
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'",
    });
    next();
  });
  app.use(express.json({ limit: '128kb' }));

  const authenticate = (request, response, next) => {
    const token = String(request.get('authorization') || '').replace(/^Bearer\s+/i, '');
    const session = sessions.get(token);
    if (!session || session.expiresAt <= Date.now()) {
      if (token) sessions.delete(token);
      return response.status(401).json({ error: '登录已失效，请重新登录' });
    }
    request.sessionToken = token;
    next();
  };

  app.get('/api/health', (_request, response) => response.json({ ok: true, service: 'app-center' }));
  app.get('/api/apps', (_request, response) => response.set('Cache-Control', 'no-store').json({ apps: store.list() }));
  app.get('/api/apps/:appId/latest', (request, response) => {
    const release = store.latest(String(request.params.appId || '').toLowerCase());
    if (!release) return response.status(404).json({ error: '暂无可用版本' });
    response.set('Cache-Control', 'no-store').json({ release });
  });

  app.post('/api/auth/login', (request, response) => {
    const username = String(request.body?.username || '').trim();
    const password = String(request.body?.password || '');
    const key = `${request.ip}:${username.toLowerCase()}`;
    const attempt = attempts.get(key);
    if (attempt?.resetAt > Date.now() && attempt.count >= 8) return response.status(429).json({ error: '尝试次数过多，请稍后再试' });
    if (!equal(username, config.adminUsername) || !equal(password, config.adminPassword)) {
      attempts.set(key, { count: (attempt?.count || 0) + 1, resetAt: Date.now() + 10 * 60 * 1000 });
      return response.status(401).json({ error: '账号或密码错误' });
    }
    attempts.delete(key);
    const token = crypto.randomBytes(32).toString('base64url');
    sessions.set(token, { expiresAt: Date.now() + config.sessionHours * 60 * 60 * 1000 });
    response.json({ token, username: config.adminUsername });
  });
  app.get('/api/auth/me', authenticate, (_request, response) => response.json({ username: config.adminUsername }));
  app.post('/api/auth/logout', authenticate, (request, response) => {
    sessions.delete(request.sessionToken);
    response.json({ loggedOut: true });
  });

  app.post('/api/admin/releases', authenticate, upload.single('apk'), async (request, response, next) => {
    if (!request.file) return response.status(400).json({ error: '请选择 APK 安装包' });
    try {
      const handle = await fs.open(request.file.path, 'r');
      const signature = Buffer.alloc(2);
      await handle.read(signature, 0, 2, 0);
      await handle.close();
      if (signature.toString('ascii') !== 'PK') throw Object.assign(new Error('文件不是有效的 APK'), { status: 400 });
      const release = await store.add({ ...request.body, tempPath: request.file.path, size: request.file.size });
      response.status(201).json({ release });
    } catch (error) {
      await fs.rm(request.file.path, { force: true });
      next(error);
    }
  });
  app.delete('/api/admin/releases', authenticate, async (request, response, next) => {
    const releaseIds = Array.isArray(request.body?.releaseIds) ? [...new Set(request.body.releaseIds.map(String))] : [];
    if (!releaseIds.length || releaseIds.length > 100) return response.status(400).json({ error: '请选择 1-100 个安装包' });
    try {
      response.json({ deleted: await store.deleteReleases(releaseIds) });
    } catch (error) { next(error); }
  });
  app.delete('/api/admin/apps/:appId', authenticate, async (request, response, next) => {
    try {
      response.json({ deleted: await store.deleteApp(String(request.params.appId || '').toLowerCase()) });
    } catch (error) { next(error); }
  });

  app.use('/downloads', express.static(store.downloadDir, {
    fallthrough: false,
    cacheControl: true,
    maxAge: '7d',
    immutable: true,
    setHeaders(response, filename) {
      response.set('Content-Type', 'application/vnd.android.package-archive');
      response.set('Content-Disposition', `attachment; filename="${path.basename(filename).replace(/[^A-Za-z0-9._-]/g, '_')}"`);
    },
  }));
  app.use(express.static(new URL('../public', import.meta.url).pathname, { extensions: ['html'], maxAge: '1h' }));
  app.get('/{*path}', (_request, response) => response.sendFile(new URL('../public/index.html', import.meta.url).pathname));

  app.use((error, _request, response, _next) => {
    if (error instanceof multer.MulterError && error.code === 'LIMIT_FILE_SIZE') return response.status(413).json({ error: `APK 不能超过 ${config.maxApkMb} MB` });
    console.error(error);
    response.status(error.status || 500).json({ error: error.status ? error.message : '服务器处理失败' });
  });
  return app;
}
