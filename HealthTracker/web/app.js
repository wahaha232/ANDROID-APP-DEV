import {
  TYPES, RANGES, rangeStart, normalizeBaseUrl, buildRecordsQuery, sessionNeedsRefresh,
  formatValue, formatTime, sourceLabel, summarize, isSafePhotoPath,
} from './lib.js';

const CONFIG_KEY = 'ht.config'; // { url, key }：只存在這個瀏覽器
const SESSION_KEY = 'ht.session'; // sessionStorage：關掉分頁就登出

const $ = (id) => document.getElementById(id);
const show = (id, on) => { $(id).hidden = !on; };
const setMsg = (id, text, isErr = false) => {
  const el = $(id);
  el.textContent = text;
  el.classList.toggle('err', isErr);
};

const state = { type: 'WEIGHT', range: '90', photoUrl: null, loadSeq: 0 };

const loadConfig = () => {
  try { return JSON.parse(localStorage.getItem(CONFIG_KEY)); } catch { return null; }
};
const loadSession = () => {
  try { return JSON.parse(sessionStorage.getItem(SESSION_KEY)); } catch { return null; }
};
const saveSession = (s) => (s ? sessionStorage.setItem(SESSION_KEY, JSON.stringify(s)) : sessionStorage.removeItem(SESSION_KEY));

class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

function toSession(body) {
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    expiresAt: Date.now() + (body.expires_in ?? 3600) * 1000,
    email: body.user?.email ?? '',
  };
}

async function tokenRequest(grantType, payload) {
  const cfg = loadConfig();
  let resp;
  try {
    resp = await fetch(`${cfg.url}/auth/v1/token?grant_type=${grantType}`, {
      method: 'POST',
      headers: { apikey: cfg.key, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch {
    throw new ApiError('無法連線，請檢查網路或連線設定', 0);
  }
  if (!resp.ok) {
    const expected = resp.status === 400 || resp.status === 401 || resp.status === 422;
    throw new ApiError(expected ? '帳號或密碼錯誤，或登入已失效' : `登入失敗（HTTP ${resp.status}）`, resp.status);
  }
  return toSession(await resp.json());
}

async function validSession() {
  let s = loadSession();
  if (!s) throw new ApiError('尚未登入', 401);
  if (sessionNeedsRefresh(s)) {
    try {
      s = await tokenRequest('refresh_token', { refresh_token: s.refreshToken });
      saveSession(s);
    } catch (e) {
      if (e.status === 400 || e.status === 401 || e.status === 422) saveSession(null);
      throw e;
    }
  }
  return s;
}

/** 帶登入憑證的請求；伺服器說過期（401）就續期後重試一次。 */
async function authed(path, init = {}, retried = false) {
  const cfg = loadConfig();
  const s = await validSession();
  let resp;
  try {
    resp = await fetch(`${cfg.url}${path}`, {
      ...init,
      headers: { apikey: cfg.key, Authorization: `Bearer ${s.accessToken}`, ...(init.headers ?? {}) },
    });
  } catch {
    throw new ApiError('無法連線到雲端，請檢查網路', 0);
  }
  if (resp.status === 401 && !retried) {
    saveSession({ ...s, expiresAt: 0 }); // 強制下一輪續期
    return authed(path, init, true);
  }
  if (resp.status === 401) { saveSession(null); throw new ApiError('登入已過期，請重新登入', 401); }
  if (resp.status === 403) throw new ApiError('沒有權限（請確認已執行 schema.sql 並用正確帳號登入）', 403);
  if (resp.status === 404) throw new ApiError('找不到資料表或照片（請確認已執行 schema.sql）', 404);
  if (!resp.ok) throw new ApiError(`伺服器錯誤（HTTP ${resp.status}）`, resp.status);
  return resp;
}

// ---------- 畫面切換 ----------
function route() {
  const cfg = loadConfig();
  const s = loadSession();
  show('setup', !cfg);
  show('login', !!cfg && !s);
  show('records', !!cfg && !!s);
  show('logout', !!s);
  if (cfg && s) loadRecords();
}

function buildTabs(containerId, items, current, onPick) {
  const box = $(containerId);
  box.replaceChildren();
  for (const [key, label] of items) {
    const b = document.createElement('button');
    b.type = 'button';
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-selected', String(key === current));
    b.textContent = label;
    b.addEventListener('click', () => onPick(key));
    box.append(b);
  }
}

function renderTabs() {
  buildTabs('type-tabs', Object.entries(TYPES).map(([k, v]) => [k, v.label]), state.type, (k) => { state.type = k; renderTabs(); loadRecords(); });
  buildTabs('range-tabs', RANGES.map((r) => [r.key, r.label]), state.range, (k) => { state.range = k; renderTabs(); loadRecords(); });
}

function renderSummary(records) {
  const box = $('summary');
  box.replaceChildren();
  const s = summarize(records);
  if (!s) return;
  const t = TYPES[state.type];
  const item = (label, text) => {
    const span = document.createElement('span');
    span.append(`${label} `);
    const b = document.createElement('b');
    b.textContent = text;
    span.append(b);
    box.append(span);
  };
  item('最新', formatValue(s.latest));
  item('筆數', String(s.count));
  if (state.type !== 'BLOOD_PRESSURE') {
    const f = (v) => `${v.toFixed(t.digits)} ${t.unit}`;
    item('平均', f(s.avg));
    item('最低', f(s.min));
    item('最高', f(s.max));
  }
}

function renderRows(records) {
  const tbody = $('rows');
  tbody.replaceChildren();
  for (const r of records) {
    const tr = document.createElement('tr');
    const cell = (text, cls) => {
      const td = document.createElement('td');
      td.textContent = text;
      if (cls) td.className = cls;
      return td;
    };
    tr.append(cell(formatTime(r.measured_at)), cell(formatValue(r), 'val'), cell(sourceLabel(r.source)), cell(r.note ?? ''));
    const photoTd = document.createElement('td');
    if (isSafePhotoPath(r.photo_object)) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = '查看';
      b.addEventListener('click', () => openPhoto(r.photo_object));
      photoTd.append(b);
    }
    tr.append(photoTd);
    tbody.append(tr);
  }
}

async function loadRecords() {
  const seq = ++state.loadSeq; // 快速切換分頁時，只採用最後一次請求的結果
  setMsg('list-msg', '載入中…');
  try {
    const q = buildRecordsQuery({ type: state.type, since: rangeStart(state.range) });
    const resp = await authed(`/rest/v1/health_records?${q}`);
    const records = await resp.json();
    if (seq !== state.loadSeq) return;
    renderSummary(records);
    renderRows(records);
    setMsg('list-msg', records.length ? '' : '這段期間沒有資料');
  } catch (e) {
    if (seq !== state.loadSeq) return;
    renderSummary([]);
    renderRows([]);
    if (e.status === 401) { route(); setMsg('login-msg', e.message, true); return; }
    setMsg('list-msg', e.message, true);
  }
}

// ---------- 照片 ----------
function closePhoto() {
  if (state.photoUrl) URL.revokeObjectURL(state.photoUrl);
  state.photoUrl = null;
  $('photo-img').removeAttribute('src');
  show('photo-img', false);
  show('photo-modal', false);
}

async function openPhoto(path) {
  show('photo-modal', true);
  setMsg('photo-msg', '載入照片…');
  try {
    // 用登入憑證抓成 blob 再顯示：照片是私人 bucket，網址本身不含任何可分享的權限。
    const resp = await authed(`/storage/v1/object/authenticated/meter-photos/${path}`);
    const blob = await resp.blob();
    if ($('photo-modal').hidden) return; // 使用者已關掉
    state.photoUrl = URL.createObjectURL(blob);
    $('photo-img').src = state.photoUrl;
    show('photo-img', true);
    setMsg('photo-msg', '');
  } catch (e) {
    setMsg('photo-msg', e.status === 404 ? '照片不存在（可能只存在手機上，尚未上傳）' : e.message, true);
  }
}

// ---------- 事件 ----------
$('cfg-save').addEventListener('click', () => {
  try {
    const url = normalizeBaseUrl($('cfg-url').value, location.hostname);
    const key = $('cfg-key').value.trim();
    if (key.length < 20) throw new Error('金鑰看起來太短');
    if (key.split('.').length === 3) {
      // anon 與 service_role 都是 JWT；service_role 絕不能放在網頁，簡單檢查 payload 內的 role。
      try {
        const role = JSON.parse(atob(key.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).role;
        if (role === 'service_role') throw new Error('這是 service_role 金鑰，絕對不能放在網頁，請改用 anon 金鑰');
      } catch (inner) {
        if (inner.message.includes('service_role')) throw inner;
      }
    }
    localStorage.setItem(CONFIG_KEY, JSON.stringify({ url, key }));
    $('cfg-key').value = '';
    setMsg('cfg-msg', '');
    route();
  } catch (e) {
    setMsg('cfg-msg', e.message, true);
  }
});

$('cfg-reset').addEventListener('click', () => {
  localStorage.removeItem(CONFIG_KEY);
  saveSession(null);
  route();
});

$('login-go').addEventListener('click', async () => {
  setMsg('login-msg', '登入中…');
  try {
    const s = await tokenRequest('password', { email: $('login-email').value.trim(), password: $('login-pass').value });
    saveSession(s);
    $('login-pass').value = '';
    setMsg('login-msg', '');
    route();
  } catch (e) {
    setMsg('login-msg', e.message, true);
  }
});

$('login-pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('login-go').click(); });
$('logout').addEventListener('click', () => { saveSession(null); closePhoto(); route(); });
$('reload').addEventListener('click', loadRecords);
$('photo-close').addEventListener('click', closePhoto);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePhoto(); });

renderTabs();
route();
