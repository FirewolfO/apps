const state = { token: sessionStorage.getItem('app_center_token') || '', apps: [], selected: new Set() };
const byId = id => document.getElementById(id);

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

function showLogin() {
  byId('loginView').hidden = false;
  byId('workspace').hidden = true;
}

function showWorkspace() {
  byId('loginView').hidden = true;
  byId('workspace').hidden = false;
}

function render() {
  const list = byId('appList');
  list.replaceChildren();
  byId('emptyState').hidden = state.apps.length > 0;
  byId('deleteSelected').disabled = state.selected.size === 0;
  for (const app of state.apps) {
    const section = document.createElement('section');
    section.className = 'app-group';
    const header = document.createElement('header');
    header.innerHTML = `<div class="app-identity"><span>${app.name.slice(0, 1).toUpperCase()}</span><div><h2></h2><p></p></div></div><div class="group-actions"><small>${app.releases.length} 个版本</small><button class="quiet select-app" type="button">全选</button><button class="danger delete-app" type="button">删除 App</button></div>`;
    header.querySelector('h2').textContent = app.name;
    header.querySelector('p').textContent = `${app.id}${app.description ? ` · ${app.description}` : ''}`;
    header.querySelector('.select-app').addEventListener('click', () => {
      const allSelected = app.releases.every(item => state.selected.has(item.id));
      for (const item of app.releases) allSelected ? state.selected.delete(item.id) : state.selected.add(item.id);
      render();
    });
    header.querySelector('.delete-app').addEventListener('click', () => deleteApp(app));
    section.append(header);
    const table = document.createElement('div');
    table.className = 'release-table';
    for (const release of app.releases) {
      const row = document.createElement('label');
      row.className = 'release-row';
      row.innerHTML = `<input type="checkbox"><span class="version"></span><span class="release-note"></span><span class="release-meta"></span><a>下载</a>`;
      const checkbox = row.querySelector('input');
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

byId('loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  byId('loginError').textContent = '';
  try {
    const body = await api('/api/auth/login', {
      method: 'POST', body: JSON.stringify({ username: byId('username').value, password: byId('password').value }),
    });
    state.token = body.token;
    sessionStorage.setItem('app_center_token', state.token);
    byId('identity').textContent = body.username;
    showWorkspace();
    await loadApps();
  } catch (error) { byId('loginError').textContent = error.message; }
});
byId('logoutButton').addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch {}
  state.token = '';
  sessionStorage.removeItem('app_center_token');
  showLogin();
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

if (state.token) {
  api('/api/auth/me').then(body => {
    byId('identity').textContent = body.username;
    showWorkspace();
    return loadApps();
  }).catch(() => {
    state.token = '';
    sessionStorage.removeItem('app_center_token');
    showLogin();
  });
} else showLogin();
