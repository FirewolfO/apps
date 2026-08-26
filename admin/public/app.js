const state = {
  token: readSessionToken(),
  user: null,
  apps: [],
  selected: new Set(),
};
const byId = id => document.getElementById(id);
const isAdministrator = () => state.user?.role === 'admin';

function readSessionToken() {
  try { return sessionStorage.getItem('app_center_token') || ''; }
  catch { return ''; }
}

function writeSessionToken(token) {
  try { sessionStorage.setItem('app_center_token', token); }
  catch {}
}

function removeSessionToken() {
  try { sessionStorage.removeItem('app_center_token'); }
  catch {}
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set('Authorization', `Bearer ${state.token}`);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(body.error || '请求失败'), { status: response.status });
  return body;
}

function formatSize(size) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function routeAppId() {
  let path = '';
  try { path = decodeURIComponent(location.pathname).replace(/^\/+|\/+$/g, ''); }
  catch { return null; }
  if (!path || path === 'ALL' || path === 'oauth/callback') return '';
  return /^[a-z][a-z0-9-]{1,47}$/.test(path) ? path : null;
}

function updateIdentity() {
  const authenticated = Boolean(state.user);
  byId('identity').hidden = !authenticated;
  byId('identity').textContent = authenticated ? state.user.displayName : '';
  byId('loginButton').hidden = authenticated;
  byId('logoutButton').hidden = !authenticated;
  byId('adminActions').hidden = !isAdministrator();
}

function render() {
  const selectedAppId = routeAppId();
  const visibleApps = selectedAppId === '' ? state.apps : state.apps.filter(app => app.id === selectedAppId);
  const list = byId('appList');
  list.replaceChildren();
  byId('deleteSelected').disabled = state.selected.size === 0;

  if (selectedAppId === '') {
    byId('pageEyebrow').textContent = 'ALL RELEASES';
    byId('pageTitle').textContent = '全部应用';
  } else {
    const current = visibleApps[0];
    byId('pageEyebrow').textContent = current ? current.id.toUpperCase() : 'APP NOT FOUND';
    byId('pageTitle').textContent = current ? `${current.name}版本` : '应用不存在';
  }
  const isEmpty = visibleApps.length === 0;
  byId('emptyState').hidden = !isEmpty;
  byId('emptyTitle').textContent = selectedAppId !== '' && !visibleApps.length ? '未找到该应用' : '暂无应用版本';
  byId('emptyDescription').textContent = selectedAppId !== '' && !visibleApps.length ? '请返回全部应用查看可用版本' : '应用发布后会在这里展示';

  for (const app of visibleApps) {
    const section = document.createElement('section');
    section.className = 'app-group';
    const header = document.createElement('header');
    header.innerHTML = '<div class="app-identity"><span></span><div><h2><a></a></h2><p></p></div></div><div class="group-actions"><small></small><button class="quiet select-app" type="button">全选</button><button class="danger delete-app" type="button">删除 App</button></div>';
    header.querySelector('.app-identity > span').textContent = app.name.slice(0, 1).toUpperCase();
    const appLink = header.querySelector('h2 a');
    appLink.textContent = app.name;
    appLink.href = `/${encodeURIComponent(app.id)}`;
    header.querySelector('.app-identity p').textContent = `${app.id}${app.description ? ` · ${app.description}` : ''}`;
    header.querySelector('.group-actions small').textContent = `${app.releases.length} 个版本`;
    const selectApp = header.querySelector('.select-app');
    const deleteAppButton = header.querySelector('.delete-app');
    selectApp.hidden = !isAdministrator();
    deleteAppButton.hidden = !isAdministrator();
    selectApp.addEventListener('click', () => {
      const allSelected = app.releases.every(item => state.selected.has(item.id));
      for (const item of app.releases) allSelected ? state.selected.delete(item.id) : state.selected.add(item.id);
      render();
    });
    deleteAppButton.addEventListener('click', () => deleteApp(app));
    section.append(header);

    const table = document.createElement('div');
    table.className = 'release-table';
    for (const release of app.releases) {
      const row = document.createElement('label');
      row.className = `release-row${isAdministrator() ? ' manageable' : ''}`;
      row.innerHTML = '<input type="checkbox"><span class="version"></span><span class="release-note"></span><span class="release-meta"></span><a>下载</a>';
      const checkbox = row.querySelector('input');
      checkbox.hidden = !isAdministrator();
      checkbox.checked = state.selected.has(release.id);
      checkbox.addEventListener('change', () => {
        checkbox.checked ? state.selected.add(release.id) : state.selected.delete(release.id);
        byId('deleteSelected').disabled = state.selected.size === 0;
      });
      row.querySelector('.version').textContent = `v${release.version}${release.id === app.latest?.id ? ' · 最新' : ''}`;
      row.querySelector('.release-note').textContent = release.notes || '无更新说明';
      row.querySelector('.release-meta').textContent = `${formatSize(release.size)} · ${formatTime(release.createdAt)}`;
      const link = row.querySelector('a');
      link.href = release.downloadUrl;
      link.setAttribute('download', release.filename);
      table.append(row);
    }
    section.append(table);
    list.append(section);
  }
  updateIdentity();
}

async function loadApps() {
  const body = await api('/api/apps');
  state.apps = body.apps;
  const known = new Set(body.apps.flatMap(app => app.releases.map(item => item.id)));
  state.selected = new Set([...state.selected].filter(id => known.has(id)));
  render();
}

async function deleteSelected() {
  if (!state.selected.size || !confirm(`确定删除所选 ${state.selected.size} 个版本吗？`)) return;
  await api('/api/admin/releases', { method: 'DELETE', body: JSON.stringify({ releaseIds: [...state.selected] }) });
  state.selected.clear();
  await loadApps();
}

async function deleteApp(app) {
  if (!confirm(`确定删除“${app.name}”及其全部 ${app.releases.length} 个版本吗？`)) return;
  await api(`/api/admin/apps/${encodeURIComponent(app.id)}`, { method: 'DELETE' });
  await loadApps();
}

async function startLogin() {
  byId('loginButton').disabled = true;
  try {
    const body = await api(`/api/auth/oauth/url?redirect=${encodeURIComponent(location.pathname)}`);
    location.assign(body.url);
  } catch (error) {
    byId('pageError').textContent = error.message;
    byId('pageError').hidden = false;
    byId('loginButton').disabled = false;
  }
}

async function completeOAuth() {
  const query = new URLSearchParams(location.search);
  const body = await api('/api/auth/oauth/callback', {
    method: 'POST',
    body: JSON.stringify({ code: query.get('code'), state: query.get('state') }),
  });
  state.token = body.token;
  state.user = body.user;
  writeSessionToken(state.token);
  history.replaceState({}, '', body.redirect || '/ALL');
}

byId('loginButton').addEventListener('click', startLogin);
byId('logoutButton').addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch {}
  state.token = '';
  state.user = null;
  state.selected.clear();
  removeSessionToken();
  render();
});
byId('deleteSelected').addEventListener('click', () => deleteSelected().catch(error => alert(error.message)));
byId('openUpload').addEventListener('click', () => byId('uploadDialog').showModal());
byId('closeUpload').addEventListener('click', () => byId('uploadDialog').close());
byId('cancelUpload').addEventListener('click', () => byId('uploadDialog').close());
byId('uploadForm').addEventListener('submit', async event => {
  event.preventDefault();
  byId('uploadError').textContent = '';
  byId('submitUpload').disabled = true;
  try {
    await api('/api/admin/releases', { method: 'POST', body: new FormData(event.currentTarget) });
    event.currentTarget.reset();
    byId('uploadDialog').close();
    await loadApps();
  } catch (error) { byId('uploadError').textContent = error.message; }
  finally { byId('submitUpload').disabled = false; }
});

async function bootstrap() {
  try {
    if (location.pathname === '/oauth/callback') await completeOAuth();
    else if (state.token) {
      try { state.user = (await api('/api/auth/me')).user; }
      catch {
        state.token = '';
        removeSessionToken();
      }
    }
    await loadApps();
  } catch (error) {
    byId('pageError').textContent = error.message;
    byId('pageError').hidden = false;
    updateIdentity();
  }
}

bootstrap();
