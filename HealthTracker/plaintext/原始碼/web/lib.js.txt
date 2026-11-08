// 純函式（不碰 DOM、不連網），方便用 node --test 測試。
export const TYPES = {
  WEIGHT: { label: '體重', unit: 'kg', digits: 1 },
  BODY_FAT: { label: '體脂率', unit: '%', digits: 1 },
  BLOOD_GLUCOSE: { label: '血糖', unit: 'mg/dL', digits: 0 },
  BLOOD_PRESSURE: { label: '血壓', unit: 'mmHg', digits: 0 },
};

export const SOURCE_LABELS = {
  MANUAL: '手動輸入',
  OCR: '拍照辨識',
  MIJIA: '米家畫面',
  IMPORT: '匯入',
  HEALTH_CONNECT: 'Health Connect',
};

export const CONTEXT_LABELS = { FASTING: '空腹', BEFORE_MEAL: '餐前', AFTER_MEAL: '餐後', BEDTIME: '睡前' };

export function contextLabel(code) {
  return CONTEXT_LABELS[code] ?? '';
}

export const RANGES = [
  { key: '30', label: '30 天', days: 30 },
  { key: '90', label: '90 天', days: 90 },
  { key: '365', label: '1 年', days: 365 },
  { key: 'all', label: '全部', days: null },
];

const DAY_MS = 86400000;

/** 起算時間（epoch 毫秒）；null 表示不限。 */
export function rangeStart(key, now = Date.now()) {
  const r = RANGES.find((x) => x.key === key);
  if (!r || r.days == null) return null;
  return now - r.days * DAY_MS;
}

/** 只接受 https://*.supabase.co（或在 localhost 開發時的 http://localhost）。回傳去掉結尾斜線的網址，不合法就丟錯。 */
export function normalizeBaseUrl(input, pageHost = '') {
  let u;
  try {
    u = new URL(String(input).trim());
  } catch {
    throw new Error('網址格式不對');
  }
  const isSupabase = u.protocol === 'https:' && /^[a-z0-9-]+\.supabase\.co$/i.test(u.hostname);
  const isLoopback = (h) => h === 'localhost' || h === '127.0.0.1';
  const isLocalDev = u.protocol === 'http:' && isLoopback(u.hostname) && isLoopback(pageHost);
  if (!isSupabase && !isLocalDev) throw new Error('只接受 https://<專案>.supabase.co 的網址');
  return u.origin;
}

/** 組 PostgREST 查詢字串。type 為 null 表示全部類型。 */
export function buildRecordsQuery({ type = null, since = null, limit = 1000, offset = 0 } = {}) {
  const p = new URLSearchParams();
  p.set('select', '*'); // 用 * 才不會在雲端還沒有 context 欄位時整個查詢失敗
  p.set('deleted_at', 'is.null');
  if (type) p.set('type', `eq.${type}`);
  if (since != null) p.set('measured_at', `gte.${Math.floor(since)}`);
  // 同一時間有多筆時再以 id 排序，offset 分頁才不會跨頁重複或漏筆
  p.set('order', 'measured_at.desc,id.desc');
  p.set('limit', String(limit));
  if (offset > 0) p.set('offset', String(offset));
  return p.toString();
}

export function sessionNeedsRefresh(session, now = Date.now(), marginMs = 60000) {
  return !session || session.expiresAt - marginMs <= now;
}

export function formatValue(r) {
  const t = TYPES[r.type];
  if (!t) return String(r.value);
  if (r.type === 'BLOOD_PRESSURE') return `${Math.round(r.value)}/${Math.round(r.value2 ?? 0)} ${t.unit}`;
  return `${Number(r.value).toFixed(t.digits)} ${t.unit}`;
}

const pad = (n) => String(n).padStart(2, '0');

/** 以台北時間顯示，不受瀏覽器所在時區影響。 */
export function formatTime(ms) {
  const d = new Date(ms + 8 * 3600000);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

export function sourceLabel(source) {
  return SOURCE_LABELS[source] ?? source ?? '';
}

function diastolicStats(records) {
  const v = records.map((r) => r.value2).filter((x) => x != null);
  if (!v.length) return null;
  return { min: Math.min(...v), max: Math.max(...v), avg: v.reduce((a, b) => a + b, 0) / v.length };
}

/** 統計（單一數值類型）。沒有資料回傳 null。records 需為新到舊。 */
export function summarize(records) {
  if (!records.length) return null;
  const values = records.map((r) => r.value);
  const sum = values.reduce((a, b) => a + b, 0);
  return {
    count: records.length,
    latest: records[0],
    min: Math.min(...values),
    max: Math.max(...values),
    avg: sum / values.length,
    // 區間變化：最新一筆減最早一筆（records 新到舊）。只有一筆時為 0。
    change: records[0].value - records[records.length - 1].value,
    // 血壓：舒張壓（value2）的統計；沒有任何 value2 時為 null
    diastolic: diastolicStats(records),
  };
}

/** 照片物件路徑必須是「<uuid>/<uuid>.jpg」的形狀，避免被塞入奇怪路徑後拿去組網址。 */
/**
 * 分頁抓完所有資料。fetchPage(offset, limit) 回傳該頁的列。
 * 超過 maxPages 頁仍有資料就標示 truncated（呼叫端必須告知使用者，不能默默匯出不完整的檔案）。
 * 剛好滿 maxPages 頁且後面沒有資料時不算截斷（多探一筆確認）。
 */
export async function fetchAllPages(fetchPage, { pageSize = 1000, maxPages = 50 } = {}) {
  const rows = [];
  for (let page = 0; page < maxPages; page++) {
    const batch = await fetchPage(page * pageSize, pageSize);
    rows.push(...batch);
    if (batch.length < pageSize) return { rows, truncated: false };
  }
  const probe = await fetchPage(maxPages * pageSize, 1);
  return { rows, truncated: probe.length > 0 };
}

export function isSafePhotoPath(path) {
  return typeof path === 'string' && /^[0-9a-f-]{36}\/[0-9a-f-]{36}\.jpg$/i.test(path);
}
