import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createApplication } from '../src/app.js';

async function startPeople(t) {
  const server = http.createServer(async (request, response) => {
    response.setHeader('content-type', 'application/json');
    if (request.url === '/oauth/token' && request.method === 'POST') {
      assert.equal(request.headers.authorization, `Basic ${Buffer.from('app-center:people-secret').toString('base64')}`);
      let body = '';
      for await (const chunk of request) body += chunk;
      const form = new URLSearchParams(body);
      assert.equal(form.get('grant_type'), 'authorization_code');
      assert.equal(form.get('redirect_uri'), 'http://app-center.test/oauth/callback');
      response.end(JSON.stringify({ access_token: `${form.get('code')}-token`, token_type: 'Bearer' }));
      return;
    }
    if (request.url === '/oauth/userinfo' && request.method === 'GET') {
      const isAdmin = request.headers.authorization === 'Bearer admin-code-token';
      response.end(JSON.stringify({
        id: isAdmin ? 1 : 2,
        username: isAdmin ? 'admin' : 'alice',
        displayName: isAdmin ? '系统管理员' : 'Alice',
        role: isAdmin ? 'admin' : 'employee',
        status: 'enabled',
      }));
      return;
    }
    response.statusCode = 404;
    response.end(JSON.stringify({ error: 'not found' }));
  });
  server.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}`;
}

test('public visitors browse releases and only People administrators manage them', async t => {
  const peopleBase = await startPeople(t);
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'app-center-'));
  const app = await createApplication({
    dataDir,
    sessionHours: 1,
    maxApkMb: 5,
    peopleApiBaseUrl: peopleBase,
    peopleAuthorizeUrl: `${peopleBase}/authorize`,
    peopleClientId: 'app-center',
    peopleClientSecret: 'people-secret',
    peopleRedirectUri: 'http://app-center.test/oauth/callback',
    cookieSecure: false,
  });
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    await fs.rm(dataDir, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (route, options = {}) => {
    const response = await fetch(base + route, options);
    const contentType = response.headers.get('content-type') || '';
    const body = contentType.includes('application/json') ? await response.json() : await response.text();
    return { response, body };
  };
  const oauthLogin = async code => {
    const started = await request('/api/auth/oauth/url?redirect=/ALL');
    assert.equal(started.response.status, 200);
    const target = new URL(started.body.url);
    assert.equal(target.searchParams.get('client_id'), 'app-center');
    assert.equal(target.searchParams.get('redirect_uri'), 'http://app-center.test/oauth/callback');
    const cookie = started.response.headers.get('set-cookie').split(';', 1)[0];
    const callback = await request('/api/auth/oauth/callback', {
      method: 'POST',
      headers: { cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ code, state: target.searchParams.get('state') }),
    });
    assert.equal(callback.response.status, 200);
    assert.equal(callback.body.redirect, '/ALL');
    return callback.body;
  };

  const root = await fetch(base, { redirect: 'manual' });
  assert.equal(root.status, 200);
  assert.equal(await root.text(), '');
  assert.equal(root.headers.get('cache-control'), 'no-store');
  const allPage = await request('/ALL');
  assert.match(allPage.body, /App Center/);
  assert.match(allPage.body, />管理APP</);
  assert.match(allPage.body, /\/app\.js\?v=20260826\.4/);
  assert.doesNotMatch(allPage.body, /class="brand"[^>]*href=/);
  assert.equal(allPage.response.headers.get('cache-control'), 'no-store');
  const appScript = await request('/app.js?v=20260826.4');
  assert.equal(appScript.response.headers.get('cache-control'), 'no-store');
  assert.match(appScript.body, /function readSessionToken/);
  assert.match((await request('/yuque')).body, /App Center/);
  assert.match((await request('/ai')).body, /App Center/);
  const oldAiPage = await fetch(`${base}/ai-workbench`, { redirect: 'manual' });
  assert.equal(oldAiPage.status, 302);
  assert.equal(oldAiPage.headers.get('location'), '/ai');
  assert.equal((await request('/api/apps')).body.apps.length, 0);
  assert.equal((await request('/api/auth/login', { method: 'POST' })).response.status, 404);
  assert.equal((await request('/api/admin/apps/yuque', { method: 'DELETE' })).response.status, 401);

  const employee = await oauthLogin('employee-code');
  assert.equal(employee.user.role, 'employee');
  const employeeAuthorization = { authorization: `Bearer ${employee.token}` };
  assert.equal((await request('/api/admin/apps/yuque', { method: 'DELETE', headers: employeeAuthorization })).response.status, 403);

  const administrator = await oauthLogin('admin-code');
  assert.equal(administrator.user.role, 'admin');
  const authorization = { authorization: `Bearer ${administrator.token}` };
  const me = await request('/api/auth/me', { headers: authorization });
  assert.equal(me.body.user.username, 'admin');

  const upload = async (version, versionCode) => {
    const form = new FormData();
    form.set('appId', 'yuque');
    form.set('name', '语雀');
    form.set('description', '企业即时通讯');
    form.set('version', version);
    form.set('versionCode', String(versionCode));
    form.set('notes', `版本 ${version}`);
    form.set('apk', new Blob([Buffer.from('PK\u0003\u0004test')], { type: 'application/vnd.android.package-archive' }), `yuque-v${version}.apk`);
    return request('/api/admin/releases', { method: 'POST', headers: authorization, body: form });
  };
  const first = await upload('1.6.1', 9);
  const second = await upload('1.7.0', 10);
  const third = await upload('1.8.0', 11);
  const fourth = await upload('1.9.0', 12);
  assert.equal(first.response.status, 201);
  assert.equal(second.response.status, 201);
  assert.equal(third.response.status, 201);
  assert.equal(fourth.response.status, 201);

  const listing = await request('/api/apps');
  assert.equal(listing.body.apps[0].id, 'yuque');
  assert.equal(listing.body.apps[0].name, '语雀');
  assert.equal(listing.body.apps[0].releases.length, 4);
  assert.equal(listing.body.apps[0].latest.version, '1.9.0');
  const latest = await request('/api/apps/yuque/latest');
  assert.equal(latest.body.release.versionCode, 12);
  const download = await fetch(base + latest.body.release.downloadUrl);
  assert.equal(download.status, 200);
  assert.equal(download.headers.get('content-type'), 'application/vnd.android.package-archive');

  const deletedSelected = await request('/api/admin/releases', {
    method: 'DELETE', headers: { ...authorization, 'content-type': 'application/json' },
    body: JSON.stringify({ releaseIds: [first.body.release.id, third.body.release.id, fourth.body.release.id] }),
  });
  assert.deepEqual(deletedSelected.body.deleted, [first.body.release.id, third.body.release.id, fourth.body.release.id]);
  const afterSelectedDelete = await request('/api/apps');
  assert.equal(afterSelectedDelete.body.apps.length, 1);
  assert.deepEqual(afterSelectedDelete.body.apps[0].releases.map(release => release.id), [second.body.release.id]);
  assert.equal(afterSelectedDelete.body.apps[0].latest.version, '1.7.0');
  const survivingDownload = await fetch(base + afterSelectedDelete.body.apps[0].latest.downloadUrl);
  assert.equal(survivingDownload.status, 200);
  const deletedApp = await request('/api/admin/apps/yuque', { method: 'DELETE', headers: authorization });
  assert.equal(deletedApp.body.deleted, 1);
});
