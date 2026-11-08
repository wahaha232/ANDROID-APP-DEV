// 血糖機照片的 OCR：辨識在瀏覽器本機完成（tesseract.js，檔案放在 vendor/，照片不會送到第三方）。
// 挑數值的規則與手機 App 的 GlucoseOcrParser 相同。

export const MIN_MGDL = 20;
export const MAX_MGDL = 800;

const NUMBER = /(?<![\d:/.\-])(\d{2,3})(?![\d:/.\-])/g;
const UNIT = /mg\s*\/?\s*d\s*l/i;

/** 七段顯示器常被讀成字母：在含數字的 2～3 字元片段內，把 O→0、I/l→1 修回來。 */
export function fixDigitLookalikes(text) {
  return text.replace(/[0-9OoIl]{2,3}/g, (t) => (/\d/.test(t) ? t.replace(/[Oo]/g, '0').replace(/[Il]/g, '1') : t));
}

/** lines: [{ text, height }]。回傳候選 [{ value, score, lineText }]，分數高者在前（行越高、靠近 mg/dL 字樣越可信）。 */
export function glucoseCandidates(lines) {
  if (!lines.length) return [];
  const maxH = Math.max(1, ...lines.map((l) => l.height));
  const best = new Map();
  lines.forEach((line, i) => {
    const text = fixDigitLookalikes(line.text);
    const nearUnit = [i - 1, i, i + 1].some((j) => lines[j] && UNIT.test(lines[j].text));
    for (const m of text.matchAll(NUMBER)) {
      const v = Number(m[1]);
      if (v < MIN_MGDL || v > MAX_MGDL) continue;
      const score = line.height / maxH + (nearUnit ? 0.5 : 0);
      const prev = best.get(v);
      if (!prev || score > prev.score) best.set(v, { value: v, score, lineText: line.text });
    }
  });
  return [...best.values()].sort((a, b) => b.score - a.score);
}

const BASE = new URL('./vendor/tesseract/', import.meta.url).href;
let enginePromise = null;

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error('OCR 元件載入失敗'));
    document.head.append(s);
  });
}

/** 第一次呼叫才載入（約 7 MB），之後重複使用同一個 worker。 */
export function getEngine() {
  enginePromise ??= (async () => {
    await loadScript(`${BASE}tesseract.min.js`);
    const worker = await globalThis.Tesseract.createWorker('eng', 1, {
      workerPath: `${BASE}worker.min.js`,
      corePath: BASE, // 目錄：自動挑 simd / 一般版本
      langPath: BASE,
      workerBlobURL: false, // 直接用同網域的 worker 檔，符合 CSP
      gzip: true,
    });
    return worker;
  })().catch((e) => { enginePromise = null; throw e; });
  return enginePromise;
}

/** 對一張圖（Blob / canvas）做 OCR，回傳 [{ text, height }]。不設字元白名單（實測會讓 LSTM 引擎整張讀不出來）。 */
export async function recognizeLines(image, psm = '3') {
  const worker = await getEngine();
  await worker.setParameters({ tessedit_pageseg_mode: psm }); // 3 自動分頁；11 稀疏文字（散落的數字）
  const { data } = await worker.recognize(image, {}, { blocks: true });
  const lines = [];
  for (const block of data.blocks ?? []) {
    for (const p of block.paragraphs ?? []) {
      for (const l of p.lines ?? []) {
        const text = (l.text ?? '').trim();
        if (text) lines.push({ text, height: Math.max(1, (l.bbox?.y1 ?? 0) - (l.bbox?.y0 ?? 0)) });
      }
    }
  }
  return lines;
}
