import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createApplication } from '../src/app.js';

test('administrator manages grouped APK releases and public latest version', async t => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'app-center-'));
  const app = await createApplication({
    dataDir, adminUsername: 'admin', adminPassword: 'admin123!', sessionHours: 1, maxApkMb: 5,
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
    const body = await response.json();
    return { response, body };
  };

  const denied = await request('/api/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'wrong' }),
  });
  assert.equal(denied.response.status, 401);
  const login = await request('/api/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123!' }),
  });
  assert.equal(login.response.status, 200);
  const authorization = { authorization: `Bearer ${login.body.token}` };

  const upload = async version => {
    const form = new FormData();
    form.set('appId', 'linkup-im');
    form.set('name', '连线 IM');
    form.set('description', '企业即时通讯');
    form.set('version', version);
    form.set('versionCode', version === '1.6.0' ? '10' : '9');
    form.set('notes', `版本 ${version}`);
    form.set('apk', new Blob([Buffer.from('PK\u0003\u0004test')], { type: 'application/vnd.android.package-archive' }), `linkup-v${version}.apk`);
    return request('/api/admin/releases', { method: 'POST', headers: authorization, body: form });
  };
  const first = await upload('1.5.1');
  const second = await upload('1.6.0');
  assert.equal(first.response.status, 201);
  assert.equal(second.response.status, 201);

  const listing = await request('/api/apps');
  assert.equal(listing.body.apps.length, 1);
  assert.equal(listing.body.apps[0].releases.length, 2);
  assert.equal(listing.body.apps[0].latest.version, '1.6.0');
  const latest = await request('/api/apps/linkup-im/latest');
  assert.equal(latest.body.release.versionCode, 10);
  const download = await fetch(base + latest.body.release.downloadUrl);
  assert.equal(download.status, 200);
  assert.equal(download.headers.get('content-type'), 'application/vnd.android.package-archive');

  const deletedSelected = await request('/api/admin/releases', {
    method: 'DELETE', headers: { ...authorization, 'content-type': 'application/json' },
    body: JSON.stringify({ releaseIds: [first.body.release.id] }),
  });
  assert.deepEqual(deletedSelected.body.deleted, [first.body.release.id]);
  const deletedApp = await request('/api/admin/apps/linkup-im', { method: 'DELETE', headers: authorization });
  assert.equal(deletedApp.body.deleted, 1);
  assert.deepEqual((await request('/api/apps')).body.apps, []);
});
