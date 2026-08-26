import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { AppStore } from './store.js';

const oauthStateCookie = 'APP_CENTER_OAUTH_STATE';

function equal(left, right) {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function cookie(request, name) {
  for (const item of String(request.get('cookie') || '').split(';')) {
    const [key, ...value] = item.trim().split('=');
    if (key === name) return decodeURIComponent(value.join('='));
  }
  return '';
}

function stateCookie(value, secure, maxAge) {
  return `${oauthStateCookie}=${encodeURIComponent(value)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure ? '; Secure' : ''}`;
}

function redirectPath(value) {
  const candidate = String(value || '');
  if (candidate === '/ALL' || /^\/[a-z][a-z0-9-]{1,47}$/.test(candidate)) return candidate;
  return '/ALL';
}

async function exchangePeopleIdentity(config, code) {
  const form = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: config.peopleRedirectUri,
  });
  const tokenResponse = await fetch(`${config.peopleApiBaseUrl}/oauth/token`, {
    method: 'POST',
    headers: {
      authorization: `Basic ${Buffer.from(`${config.peopleClientId}:${config.peopleClientSecret}`).toString('base64')}`,
      'content-type': 'application/x-www-form-urlencoded',
    },
    body: form,
    signal: AbortSignal.timeout(15_000),
  });
  const token = await tokenResponse.json().catch(() => ({}));
  if (!tokenResponse.ok || !token.access_token) throw Object.assign(new Error('People OAuth 授权码无效'), { status: 401 });

  const userResponse = await fetch(`${config.peopleApiBaseUrl}/oauth/userinfo`, {
    headers: { authorization: `Bearer ${token.access_token}` },
    signal: AbortSignal.timeout(15_000),
  });
  const employee = await userResponse.json().catch(() => ({}));
  if (!userResponse.ok || !employee.username || employee.status !== 'enabled') {
    throw Object.assign(new Error('People 用户不可用'), { status: 401 });
  }
  return {
    username: employee.username,
    displayName: employee.displayName || employee.username,
    role: employee.role || 'employee',
  };
}

export async function createApplication(config) {
  const store = new AppStore(config.dataDir);
  await store.init();
  const sessions = new Map();
  const oauthStates = new Map();
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
    request.identity = session.user;
    request.sessionToken = token;
    next();
  };
  const requireAdministrator = (request, response, next) => authenticate(request, response, () => {
    if (request.identity.role !== 'admin') return response.status(403).json({ error: '仅系统管理员可以管理应用' });
    next();
  });

  app.get('/api/health', (_request, response) => response.json({ ok: true, service: 'app-center' }));
  app.get('/api/apps', (_request, response) => response.set('Cache-Control', 'no-store').json({ apps: store.list() }));
  app.get('/api/apps/:appId/latest', (request, response) => {
    const release = store.latest(String(request.params.appId || '').toLowerCase());
    if (!release) return response.status(404).json({ error: '暂无可用版本' });
    response.set('Cache-Control', 'no-store').json({ release });
  });

  app.get('/api/auth/oauth/url', (request, response, next) => {
    try {
      const now = Date.now();
      for (const [state, record] of oauthStates) if (record.expiresAt <= now) oauthStates.delete(state);
      const state = crypto.randomBytes(32).toString('base64url');
      oauthStates.set(state, { expiresAt: now + 10 * 60 * 1000, redirect: redirectPath(request.query.redirect) });
      const target = new URL(config.peopleAuthorizeUrl);
      target.search = new URLSearchParams({
        client_id: config.peopleClientId,
        redirect_uri: config.peopleRedirectUri,
        response_type: 'code',
        scope: 'openid profile',
        state,
      }).toString();
      response.set('Set-Cookie', stateCookie(state, config.cookieSecure, 600));
      response.json({ url: target.toString() });
    } catch (error) { next(error); }
  });
  app.post('/api/auth/oauth/callback', async (request, response, next) => {
    const code = String(request.body?.code || '').trim();
    const state = String(request.body?.state || '').trim();
    const saved = oauthStates.get(state);
    if (!code || !saved || saved.expiresAt <= Date.now() || !equal(cookie(request, oauthStateCookie), state)) {
      return response.status(400).json({ error: 'OAuth 登录状态无效，请重新登录' });
    }
    oauthStates.delete(state);
    response.set('Set-Cookie', stateCookie('', config.cookieSecure, 0));
    try {
      const user = await exchangePeopleIdentity(config, code);
      const token = crypto.randomBytes(32).toString('base64url');
      sessions.set(token, { user, expiresAt: Date.now() + config.sessionHours * 60 * 60 * 1000 });
      response.json({ token, user, redirect: saved.redirect });
    } catch (error) { next(error); }
  });
  app.get('/api/auth/me', authenticate, (request, response) => response.json({ user: request.identity }));
  app.post('/api/auth/logout', authenticate, (request, response) => {
    sessions.delete(request.sessionToken);
    response.json({ loggedOut: true });
  });

  app.post('/api/admin/releases', requireAdministrator, upload.single('apk'), async (request, response, next) => {
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
  app.delete('/api/admin/releases', requireAdministrator, async (request, response, next) => {
    const releaseIds = Array.isArray(request.body?.releaseIds) ? [...new Set(request.body.releaseIds.map(String))] : [];
    if (!releaseIds.length || releaseIds.length > 100) return response.status(400).json({ error: '请选择 1-100 个安装包' });
    try {
      response.json({ deleted: await store.deleteReleases(releaseIds) });
    } catch (error) { next(error); }
  });
  app.delete('/api/admin/apps/:appId', requireAdministrator, async (request, response, next) => {
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
  app.get('/', (_request, response) => response.redirect('/ALL'));
  const publicDirectory = new URL('../public', import.meta.url).pathname;
  app.use(express.static(publicDirectory, {
    extensions: ['html'],
    maxAge: 0,
    index: false,
    setHeaders(response) {
      response.set('Cache-Control', 'no-store');
    },
  }));
  app.get('/{*path}', (_request, response) => {
    response.set('Cache-Control', 'no-store');
    response.sendFile(path.join(publicDirectory, 'index.html'));
  });

  app.use((error, _request, response, _next) => {
    if (error instanceof multer.MulterError && error.code === 'LIMIT_FILE_SIZE') return response.status(413).json({ error: `APK 不能超过 ${config.maxApkMb} MB` });
    console.error(error);
    response.status(error.status || 500).json({ error: error.status ? error.message : '服务器处理失败' });
  });
  return app;
}
