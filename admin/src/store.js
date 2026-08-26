import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const APP_ID_PATTERN = /^[a-z][a-z0-9-]{1,47}$/;
const VERSION_PATTERN = /^\d+(?:\.\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?$/;

function versionParts(value) {
  return String(value).split(/[.+-]/, 4).map(part => Number.parseInt(part, 10) || 0);
}
function compareReleases(left, right) {
  const a = versionParts(left.version);
  const b = versionParts(right.version);
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index += 1) {
    if ((a[index] || 0) !== (b[index] || 0)) return (b[index] || 0) - (a[index] || 0);
  }
  return right.createdAt.localeCompare(left.createdAt);
}

function releaseView(appId, release) {
  return {
    id: release.id,
    version: release.version,
    versionCode: release.versionCode,
    filename: release.filename,
    size: release.size,
    notes: release.notes,
    createdAt: release.createdAt,
    downloadUrl: `/downloads/${encodeURIComponent(appId)}/${encodeURIComponent(release.filename)}`,
  };
}

export class AppStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.downloadDir = path.join(dataDir, 'downloads');
    this.indexPath = path.join(dataDir, 'index.json');
    this.state = { apps: {} };
    this.queue = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.downloadDir, { recursive: true });
    try {
      const parsed = JSON.parse(await fs.readFile(this.indexPath, 'utf8'));
      if (parsed && typeof parsed.apps === 'object') this.state = parsed;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await this.persist();
    }
  }

  list() {
    return Object.values(this.state.apps)
      .map(app => {
        const releases = [...app.releases].sort(compareReleases).map(release => releaseView(app.id, release));
        return {
          id: app.id,
          name: app.name,
          description: app.description,
          latest: releases[0] || null,
          releases,
        };
      })
      .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'));
  }

  latest(appId) {
    return this.list().find(app => app.id === appId)?.latest || null;
  }

  async add(input) {
    return this.mutate(async () => {
      const appId = String(input.appId || '').trim().toLowerCase();
      const name = String(input.name || '').trim();
      const description = String(input.description || '').trim();
      const version = String(input.version || '').trim();
      const notes = String(input.notes || '').trim();
      const versionCode = Number.parseInt(input.versionCode, 10) || 0;
      if (!APP_ID_PATTERN.test(appId)) throw Object.assign(new Error('App ID 需为 2-48 位小写字母、数字或横线'), { status: 400 });
      if (!name || name.length > 80) throw Object.assign(new Error('App 名称需为 1-80 个字符'), { status: 400 });
      if (!VERSION_PATTERN.test(version)) throw Object.assign(new Error('版本号格式无效'), { status: 400 });
      if (description.length > 300 || notes.length > 1000) throw Object.assign(new Error('描述或更新说明过长'), { status: 400 });
      const current = this.state.apps[appId];
      if (current?.releases.some(item => item.version === version)) {
        throw Object.assign(new Error(`版本 ${version} 已存在`), { status: 409 });
      }
      const id = crypto.randomUUID();
      const filename = `${appId}-v${version}-${id.slice(0, 8)}.apk`;
      const appDir = path.join(this.downloadDir, appId);
      await fs.mkdir(appDir, { recursive: true });
      await fs.rename(input.tempPath, path.join(appDir, filename));
      const release = {
        id,
        version,
        versionCode,
        filename,
        size: input.size,
        notes,
        createdAt: new Date().toISOString(),
      };
      this.state.apps[appId] = {
        id: appId,
        name,
        description,
        releases: [...(current?.releases || []), release],
      };
      await this.persist();
      return releaseView(appId, release);
    });
  }

  async deleteReleases(ids) {
    return this.mutate(async () => {
      const selected = new Set(ids);
      const found = [];
      for (const app of Object.values(this.state.apps)) {
        for (const release of app.releases) {
          if (selected.has(release.id)) found.push({ app, release });
        }
      }
      if (found.length !== selected.size) throw Object.assign(new Error('部分安装包不存在，请刷新后重试'), { status: 404 });
      for (const { app, release } of found) {
        await fs.rm(path.join(this.downloadDir, app.id, release.filename), { force: true });
        app.releases = app.releases.filter(item => item.id !== release.id);
        if (!app.releases.length) delete this.state.apps[app.id];
      }
      await this.persist();
      return found.map(item => item.release.id);
    });
  }

  async deleteApp(appId) {
    return this.mutate(async () => {
      const app = this.state.apps[appId];
      if (!app) throw Object.assign(new Error('App 不存在'), { status: 404 });
      await fs.rm(path.join(this.downloadDir, appId), { recursive: true, force: true });
      delete this.state.apps[appId];
      await this.persist();
      return app.releases.length;
    });
  }

  async persist() {
    const temporary = `${this.indexPath}.${process.pid}.tmp`;
    await fs.writeFile(temporary, `${JSON.stringify(this.state, null, 2)}\n`, { mode: 0o600 });
    await fs.rename(temporary, this.indexPath);
  }

  mutate(operation) {
    const result = this.queue.then(operation);
    this.queue = result.catch(() => {});
    return result;
  }
}
