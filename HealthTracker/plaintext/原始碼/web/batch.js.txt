// 批次匯入血糖機照片：讀照片資訊（EXIF 拍攝時間）、本機 OCR 數值，使用者確認後寫入雲端。
import { readExifTime } from './exif.js';
import { glucoseCandidates, recognizeLines, MIN_MGDL, MAX_MGDL } from './ocr.js';

const MAX_FILES = 60;
const MAX_SIDE = 1800;
const DUP_WINDOW_MS = 2 * 60000;
const TAIPEI_MIN = 8 * 60;

/** 毫秒 → <input type="datetime-local"> 的值（台北時間）。 */
export const toLocalInput = (ms) => new Date(ms + TAIPEI_MIN * 60000).toISOString().slice(0, 16);
/** datetime-local 的值（台北時間）→ 毫秒；不合法回傳 NaN。 */
export const fromLocalInput = (text) => Date.parse(`${text}:00+08:00`);

/** 是否與既有紀錄重複：數值相同且時間差在 ±2 分鐘內（與手機 App 相同）。 */
export function isDuplicate(existing, value, ms) {
  return existing.some((r) => Math.abs(r.value - value) < 0.05 && Math.abs(r.measured_at - ms) <= DUP_WINDOW_MS);
}

/** 檢查一列要寫入的資料；回傳錯誤訊息或 null。 */
export function validateRow(value, ms, now) {
  if (!Number.isFinite(value) || value < MIN_MGDL || value > MAX_MGDL) return `數值需在 ${MIN_MGDL}–${MAX_MGDL}`;
  if (!Number.isFinite(ms)) return '時間不正確';
  if (ms > now + 5 * 60000) return '時間不能在未來';
  return null;
}

async function shrink(file) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const f = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bmp.width * f));
  canvas.height = Math.max(1, Math.round(bmp.height * f));
  canvas.getContext('2d').drawImage(bmp, 0, 0, canvas.width, canvas.height);
  bmp.close?.();
  const blob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.85));
  if (!blob) throw new Error('無法處理這張照片');
  return { canvas, blob };
}

/**
 * deps: { authed(path, init), userId(): Promise<string>, onSaved() }
 * 畫面元素（index.html 的 #batch 區塊）由這裡接上。
 */
export function initBatch({ authed, userId, onSaved }) {
  const $ = (id) => document.getElementById(id);
  const input = $('batch-files');
  const body = $('batch-rows');
  const saveBtn = $('batch-save');
  let rows = [];
  let busy = false;

  const say = (text, err = false) => { const el = $('batch-msg'); el.textContent = text; el.classList.toggle('err', err); };

  function render() {
    body.replaceChildren();
    rows.forEach((r) => {
      const tr = document.createElement('tr');
      const td = (...kids) => { const c = document.createElement('td'); c.append(...kids); tr.append(c); return c; };

      const chk = document.createElement('input');
      chk.type = 'checkbox'; chk.checked = r.include; chk.disabled = r.saved;
      chk.addEventListener('change', () => { r.include = chk.checked; });
      td(chk);

      const img = document.createElement('img');
      img.src = r.thumb; img.alt = r.file.name; img.className = 'thumb';
      td(img);

      const val = document.createElement('input');
      val.type = 'number'; val.step = '1'; val.value = r.value ?? ''; val.className = 'num'; val.disabled = r.saved;
      val.setAttribute('aria-label', '血糖數值');
      val.addEventListener('input', () => { r.value = val.value === '' ? null : Number(val.value); r.include = r.value != null; chk.checked = r.include; });
      td(val);

      const when = document.createElement('input');
      when.type = 'datetime-local'; when.value = toLocalInput(r.ms); when.disabled = r.saved;
      when.setAttribute('aria-label', '測量時間');
      when.addEventListener('change', () => { const v = fromLocalInput(when.value); if (Number.isFinite(v)) { r.ms = v; r.timeFrom = 'edit'; } });
      td(when);

      td(document.createTextNode(r.status));
      body.append(tr);
    });
    saveBtn.disabled = busy || !rows.some((r) => r.include && !r.saved);
  }

  async function fetchExisting(from, to) {
    const q = `/rest/v1/health_records?select=value,measured_at&type=eq.BLOOD_GLUCOSE&deleted_at=is.null&measured_at=gte.${from}&measured_at=lte.${to}&limit=1000`;
    return (await authed(q)).json();
  }

  async function analyse(files) {
    busy = true; rows = []; render();
    const list = [...files].slice(0, MAX_FILES);
    if (files.length > MAX_FILES) say(`一次最多 ${MAX_FILES} 張，只處理前 ${MAX_FILES} 張。`, true);
    for (let i = 0; i < list.length; i++) {
      const file = list[i];
      say(`辨識中 ${i + 1} / ${list.length}（第一次會先載入辨識元件，需要幾秒）…`);
      const row = { file, include: false, saved: false, value: null, ms: file.lastModified, timeFrom: 'file', status: '', blob: null, thumb: '' };
      try {
        const ex = readExifTime(await file.arrayBuffer());
        if (ex != null) { row.ms = ex; row.timeFrom = 'exif'; }
        const { canvas, blob } = await shrink(file);
        row.blob = blob;
        row.thumb = URL.createObjectURL(blob);
        let c = glucoseCandidates(await recognizeLines(canvas, '3'))[0];
        if (!c) c = glucoseCandidates(await recognizeLines(canvas, '11'))[0]; // 自動分頁讀不到時，改用稀疏文字再試一次
        row.value = c ? c.value : null;
        row.include = c != null && row.timeFrom === 'exif';
        row.status = [c ? '' : '認不出數值', row.timeFrom === 'exif' ? '' : '無拍攝時間（用檔案時間，請確認）'].filter(Boolean).join('；') || '待確認';
      } catch (e) {
        row.status = `處理失敗：${e.message}`;
        row.thumb = row.thumb || 'data:,';
      }
      rows.push(row);
      render();
    }
    // 與雲端既有紀錄比對，重複的預設不勾
    try {
      const times = rows.map((r) => r.ms).filter(Number.isFinite);
      if (times.length) {
        const existing = await fetchExisting(Math.min(...times) - DUP_WINDOW_MS, Math.max(...times) + DUP_WINDOW_MS);
        for (const r of rows) {
          if (r.value != null && isDuplicate(existing, r.value, r.ms)) { r.include = false; r.status = '雲端已有相同紀錄'; }
        }
      }
    } catch (e) {
      say(`無法比對重複：${e.message}`, true);
    }
    busy = false; render();
    say(`已辨識 ${rows.length} 張。請核對數值與時間，勾選要寫入的列後按「寫入雲端」。辨識可能有誤。`);
  }

  async function save() {
    busy = true; render();
    const uid = await userId();
    const now = Date.now();
    let ok = 0; let fail = 0;
    const todo = rows.filter((r) => r.include && !r.saved);
    for (let i = 0; i < todo.length; i++) {
      const r = todo[i];
      say(`寫入中 ${i + 1} / ${todo.length}…`);
      const err = validateRow(r.value, r.ms, now);
      if (err) { r.status = err; fail++; continue; }
      try {
        const id = crypto.randomUUID();
        const objectPath = `${uid}/${id}.jpg`;
        let photoObject = null;
        try {
          await authed(`/storage/v1/object/meter-photos/${objectPath}`, { method: 'POST', headers: { 'Content-Type': 'image/jpeg', 'x-upsert': 'true' }, body: r.blob });
          photoObject = objectPath;
        } catch { /* 照片上傳失敗仍寫入數值，狀態欄會註明 */ }
        const rec = { id, type: 'BLOOD_GLUCOSE', value: r.value, value2: null, measured_at: r.ms, source: 'OCR', note: '', created_at: now, updated_at: now };
        if (photoObject) rec.photo_object = photoObject;
        await authed('/rest/v1/health_records', { method: 'POST', headers: { 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates,return=minimal' }, body: JSON.stringify(rec) });
        r.saved = true; r.include = false; r.status = photoObject ? '已寫入' : '已寫入（照片上傳失敗）';
        ok++;
      } catch (e) {
        r.status = `寫入失敗：${e.message}`; fail++;
      }
      render();
    }
    busy = false; render();
    say(`寫入完成：成功 ${ok} 筆${fail ? `、失敗 ${fail} 筆` : ''}。`, fail > 0);
    if (ok > 0) onSaved();
  }

  input.addEventListener('change', () => { if (input.files.length && !busy) analyse(input.files).catch((e) => { busy = false; say(`批次匯入失敗：${e.message}`, true); render(); }); });
  saveBtn.addEventListener('click', () => { if (!busy) save().catch((e) => { busy = false; say(`寫入失敗：${e.message}`, true); render(); }); });
}
