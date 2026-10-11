// 紀錄管理：瀏覽所有已上傳紀錄，支援下載、編輯備註（overlay meta.json）、刪除。
// 讀取：無 Token 時走同源相對路徑；有 Token 時走 GitHub API（較即時）。
// 寫入（編輯/刪除）：需要 GitHub Token（存於 localStorage），使用 GitHub Contents API。
(function () {
  "use strict";

  var GPST_CONF = GPST.resolveConfig();
  var LS = { repo: "gpst_gh_repo", branch: "gpst_gh_branch", data: "gpst_gh_data", token: "gpst_gh_token" };
  var DEFAULTS = { repo: "wahaha232/ANDROID-APP-DEV", branch: "main", data: "gpstracker-web/data", token: "" };

  var MODE_EMOJI = { WALKING: "🚶", RUNNING: "🏃", BICYCLE: "🚲", CYCLING: "🚲", VEHICLE: "🚗", DRIVING: "🚗", CAR: "🚗", MOTORCYCLE: "🏍️", TRAIN: "🚆", BUS: "🚌", BOAT: "⛵", STATIONARY: "🧍", UNKNOWN: "❓" };
  var MODE_NAME = { WALKING: "步行", RUNNING: "跑步", BICYCLE: "單車", CYCLING: "單車", VEHICLE: "車輛", DRIVING: "開車", CAR: "開車", MOTORCYCLE: "機車", TRAIN: "火車", BUS: "公車", BOAT: "船", STATIONARY: "靜止", UNKNOWN: "未知" };

  var cfg = loadCfg();
  var tracks = [];
  var meta = { tracks: {} };
  var selected = {};
  var editingId = null;

  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function fmtDate(ms) { var d = new Date(ms); return d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function fmtDur(ms) { var s = Math.floor((ms || 0) / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60); return (h > 0 ? h + "h " : "") + m + "m"; }
  function kmOf(t) { return (t.distanceMeters || 0) / 1000; }
  function fmtKm(v) { return v.toLocaleString("zh-Hant", { maximumFractionDigits: 3 }); }
  function modeEmoji(m) { return MODE_EMOJI[m] || "❓"; }
  function modeName(m) { return MODE_NAME[m] || m || "未知"; }

  // ---- 設定 ----
  function loadCfg() {
    function g(k, d) { try { return localStorage.getItem(k) || d; } catch (e) { return d; } }
    return {
      repo: g(LS.repo, DEFAULTS.repo),
      branch: g(LS.branch, DEFAULTS.branch),
      data: g(LS.data, DEFAULTS.data),
      token: g(LS.token, "")
    };
  }
  function saveCfg() {
    cfg = {
      repo: $("cfgRepo").value.trim() || DEFAULTS.repo,
      branch: $("cfgBranch").value.trim() || DEFAULTS.branch,
      data: $("cfgData").value.trim() || DEFAULTS.data,
      token: $("cfgToken").value.trim()
    };
    try {
      localStorage.setItem(LS.repo, cfg.repo); localStorage.setItem(LS.branch, cfg.branch);
      localStorage.setItem(LS.data, cfg.data); localStorage.setItem(LS.token, cfg.token);
    } catch (e) {}
    applyCfgToForm();
    render();
  }
  function applyCfgToForm() {
    $("cfgRepo").value = cfg.repo; $("cfgBranch").value = cfg.branch; $("cfgData").value = cfg.data; $("cfgToken").value = cfg.token;
    $("cfgState").textContent = cfg.token ? "✅ 已設定 Token（可編輯/刪除）" : "（未設定 Token，僅能瀏覽/下載）";
  }
  function canWrite() { return !!cfg.token; }

  // ---- GitHub API ----
  function apiContents(path) { return "https://api.github.com/repos/" + cfg.repo + "/contents/" + path; }
  function headers() {
    var h = { "Accept": "application/vnd.github+json", "User-Agent": "gpstracker-records" };
    if (cfg.token) h["Authorization"] = "Bearer " + cfg.token;
    return h;
  }
  function b64ToStr(b64) {
    var bin = atob((b64 || "").replace(/\s/g, ""));
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  }
  function strToB64(s) {
    var bytes = new TextEncoder().encode(s), bin = "", chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    return btoa(bin);
  }
  async function ghGet(path) {
    var r = await fetch(apiContents(path) + "?ref=" + encodeURIComponent(cfg.branch) + "&t=" + Date.now(), { headers: headers(), cache: "no-store" });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error("GET " + path + " -> " + r.status);
    var j = await r.json();
    return { text: b64ToStr(j.content), sha: j.sha };
  }
  async function putFile(path, text, message) {
    var existing = await ghGet(path);
    var body = { message: message, content: strToB64(text), branch: cfg.branch };
    if (existing) body.sha = existing.sha;
    var r = await fetch(apiContents(path), { method: "PUT", headers: headers(), body: JSON.stringify(body) });
    if (!r.ok) throw new Error("PUT " + path + " -> " + r.status + " " + (await r.text()).slice(0, 160));
  }
  async function deleteFile(path, message) {
    var existing = await ghGet(path);
    if (!existing) return false;
    var r = await fetch(apiContents(path), { method: "DELETE", headers: headers(), body: JSON.stringify({ message: message, sha: existing.sha, branch: cfg.branch }) });
    if (!r.ok) throw new Error("DELETE " + path + " -> " + r.status + " " + (await r.text()).slice(0, 160));
    return true;
  }

  // ---- 讀取 ----
  function readBase() { return cfg.data.replace(/\/+$/, "") + "/"; }
  function relFetch(name) {
    // 無 token 時走同源相對路徑（快速）；有 token 時走 raw 以反映最新（跨網域）。
    var url = cfg.token
      ? "https://raw.githubusercontent.com/" + cfg.repo + "/" + cfg.branch + "/" + readBase() + name + "?_=" + Date.now()
      : (GPST_CONF.dataBase + name);
    return fetch(url, { cache: "no-cache" }).then(function (r) { if (!r.ok) throw new Error(name + " " + r.status); return r.json(); });
  }

  async function load() {
    setNote("載入中…", "");
    try {
      var man;
      if (cfg.token) {
        var f = await ghGet(readBase() + "manifest.json");
        man = f ? JSON.parse(f.text) : { tracks: [] };
      } else {
        man = await relFetch("manifest.json");
      }
      tracks = ((man && man.tracks) || []).slice();
      // meta overlay（選用，可能不存在）
      try {
        if (cfg.token) { var mf = await ghGet(readBase() + "meta.json"); meta = mf ? JSON.parse(mf.text) : { tracks: {} }; }
        else { meta = await relFetch("meta.json"); }
        if (!meta || !meta.tracks) meta = { tracks: {} };
      } catch (e) { meta = { tracks: {} }; }
      setNote("共 " + tracks.length + " 筆紀錄。" + (canWrite() ? "可編輯/刪除。" : "未設定 Token：僅能瀏覽/下載。"), "ok");
      buildModeFilter();
      render();
    } catch (e) {
      setNote("載入失敗：" + e.message, "err");
      $("tableWrap").innerHTML = '<div class="rec-empty">無法載入紀錄：' + e.message + "</div>";
    }
  }
  function setNote(text, cls) { var n = $("statusNote"); n.className = "rec-note " + (cls || ""); n.textContent = text; }

  function buildModeFilter() {
    var set = {};
    tracks.forEach(function (t) { if (t.dominantMode) set[t.dominantMode] = true; });
    var sel = $("modeFilter");
    sel.length = 1;
    Object.keys(set).forEach(function (m) { var o = document.createElement("option"); o.value = m; o.textContent = modeEmoji(m) + " " + modeName(m); sel.appendChild(o); });
  }

  function filteredSorted() {
    var q = $("search").value.trim().toLowerCase();
    var mf = $("modeFilter").value;
    var list = tracks.filter(function (t) {
      var note = (meta.tracks[t.trackId] && (meta.tracks[t.trackId].title || meta.tracks[t.trackId].notes)) || "";
      if (mf && t.dominantMode !== mf) return false;
      if (q && (fmtDate(t.startTimeMs).toLowerCase().indexOf(q) < 0) && String(t.dominantMode).toLowerCase().indexOf(q) < 0 && note.toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    var s = $("sortSel").value;
    list.sort(function (a, b) {
      if (s === "date_asc") return a.startTimeMs - b.startTimeMs;
      if (s === "dist_desc") return kmOf(b) - kmOf(a);
      if (s === "dur_desc") return (b.durationMs || 0) - (a.durationMs || 0);
      return b.startTimeMs - a.startTimeMs;
    });
    return list;
  }

  function render() {
    var list = filteredSorted();
    var w = canWrite();
    var html = '<div class="rec-table-wrap"><table class="rec-table"><thead><tr>' +
      '<th><input type="checkbox" id="chkHead" /></th><th>日期</th><th>模式</th><th>距離 (km)</th><th>時間</th>' +
      '<th>步數</th><th>照片</th><th>定位點</th><th>備註</th><th>操作</th></tr></thead><tbody>';
    if (!list.length) {
      html += '<tr><td colspan="10"><div class="rec-empty">沒有符合條件的紀錄。</div></td></tr>';
    }
    list.forEach(function (t) {
      var m = meta.tracks[t.trackId] || {};
      var noteText = [m.title, m.notes].filter(Boolean).join(" · ");
      html += '<tr data-id="' + t.trackId + '">' +
        '<td><input type="checkbox" class="rowsel" data-id="' + t.trackId + '"' + (selected[t.trackId] ? " checked" : "") + ' /></td>' +
        '<td>' + fmtDate(t.startTimeMs) + '</td>' +
        '<td>' + modeEmoji(t.dominantMode) + " " + modeName(t.dominantMode) + '</td>' +
        '<td class="num">' + fmtKm(kmOf(t)) + '</td>' +
        '<td>' + fmtDur(t.durationMs) + '</td>' +
        '<td class="num">' + (t.stepCount || 0) + '</td>' +
        '<td class="num">' + (t.photoCount || 0) + '</td>' +
        '<td class="num">' + (t.gpsPointCount || 0) + '</td>' +
        '<td class="note">' + (noteText ? escapeHtml(noteText) : "") + '</td>' +
        '<td><div class="rec-actions">' +
        '<button data-act="gpx" data-id="' + t.trackId + '">GPX</button>' +
        '<button data-act="json" data-id="' + t.trackId + '">JSON</button>' +
        '<button data-act="edit" data-id="' + t.trackId + '"' + (w ? "" : " disabled") + '>編輯</button>' +
        '<button class="danger" data-act="del" data-id="' + t.trackId + '"' + (w ? "" : " disabled") + '>刪除</button>' +
        '</div></td></tr>';
    });
    html += "</tbody></table></div>";
    $("tableWrap").innerHTML = html;
    $("deleteSel").disabled = !w;
    updateSelCount();
  }
  function escapeHtml(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function updateSelCount() { var n = Object.keys(selected).filter(function (k) { return selected[k]; }).length; $("selCount").textContent = n ? ("已選 " + n + " 筆") : ""; }

  // ---- 下載 ----
  async function fetchTrack(id) {
    var url = (cfg.token ? ("https://raw.githubusercontent.com/" + cfg.repo + "/" + cfg.branch + "/" + readBase() + id + ".json") : (GPST_CONF.dataBase + id + ".json"));
    var r = await fetch(url + (cfg.token ? "?_=" + Date.now() : ""), { cache: "no-cache" });
    if (!r.ok) throw new Error("無法讀取 " + id);
    return r.json();
  }
  function saveBlob(name, text, type) {
    var blob = new Blob([text], { type: type || "application/octet-stream" });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
  }
  async function downloadJson(id) { var t = await fetchTrack(id); saveBlob(id + ".json", JSON.stringify(t), "application/json"); }
  async function downloadGpx(id) {
    var t = await fetchTrack(id);
    var pts = t.points || [], base = t.startTimeMs || 0;
    var gpx = '<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="GPS Tracker" xmlns="http://www.topografix.com/GPX/1/1">\n' +
      "<trk><name>" + fmtDate(t.startTimeMs) + "</name><trkseg>\n";
    pts.forEach(function (p) {
      var time = new Date(base + (p[4] || 0) * 1000).toISOString();
      gpx += '<trkpt lat="' + p[1] + '" lon="' + p[0] + '"><ele>' + (p[2] || 0) + "</ele><time>" + time + "</time></trkpt>\n";
    });
    gpx += "</trkseg></trk></gpx>\n";
    saveBlob(id + ".gpx", gpx, "application/gpx+xml");
  }

  // ---- 編輯（備註 overlay） ----
  function openEdit(id) {
    editingId = id;
    var m = meta.tracks[id] || {};
    var back = document.createElement("div");
    back.className = "modal-back"; back.id = "editModal";
    back.innerHTML = '<div class="modal"><h3>編輯備註</h3>' +
      '<label>標題<input id="editTitle" value="' + escapeHtml(m.title || "") + '" /></label>' +
      '<label>備註<textarea id="editNotes">' + escapeHtml(m.notes || "") + "</textarea></label>" +
      '<div class="btn-row"><button class="btn secondary" id="editCancel">取消</button><button class="btn" id="editSave">儲存</button></div>' +
      '<div class="rec-note" style="margin-top:10px">只儲存標題/備註到 <code>' + escapeHtml(readBase()) + 'meta.json</code>，原始 GPS 紀錄檔不會被修改。</div></div>';
    document.body.appendChild(back);
    $("editCancel").onclick = function () { document.body.removeChild(back); editingId = null; };
    $("editSave").onclick = saveEdit;
  }
  async function saveEdit() {
    var id = editingId;
    var title = $("editTitle").value.trim();
    var notes = $("editNotes").value.trim();
    setNote("儲存中…", "");
    try {
      if (!meta.tracks[id]) meta.tracks[id] = {};
      meta.tracks[id].title = title; meta.tracks[id].notes = notes;
      await putFile(readBase() + "meta.json", JSON.stringify({ tracks: meta.tracks }, null, 2), "GPS Tracker: update notes for " + id);
      var back = $("editModal"); if (back) document.body.removeChild(back);
      editingId = null;
      setNote("已儲存備註（GitHub Pages 約 1 分鐘後生效）。", "ok");
      render();
    } catch (e) { setNote("儲存失敗：" + e.message, "err"); }
  }

  // ---- 刪除 ----
  async function deleteOne(id, manifest) {
    await deleteFile(readBase() + id + ".json", "GPS Tracker: delete track " + id);
    var i = manifest.tracks.findIndex(function (t) { return t.trackId === id; });
    if (i >= 0) manifest.tracks.splice(i, 1);
    if (meta.tracks[id]) { delete meta.tracks[id]; }
  }
  async function deleteRecords(ids) {
    if (!ids.length) return;
    if (!confirm("確定要刪除 " + ids.length + " 筆紀錄？此動作會從 GitHub repo 永久移除（無法復原）。")) return;
    setNote("刪除中…", "");
    try {
      var f = await ghGet(readBase() + "manifest.json");
      var manifest = f ? JSON.parse(f.text) : { tracks: [] };
      if (!manifest.tracks) manifest.tracks = [];
      var failed = 0;
      for (var k = 0; k < ids.length; k++) {
        setNote("刪除中… " + (k + 1) + " / " + ids.length, "");
        try { await deleteOne(ids[k], manifest); selected[ids[k]] = false; } catch (e) { failed++; }
      }
      manifest.updatedAt = Date.now();
      await putFile(readBase() + "manifest.json", JSON.stringify(manifest), "GPS Tracker: update manifest after delete");
      await putFile(readBase() + "meta.json", JSON.stringify({ tracks: meta.tracks }, null, 2), "GPS Tracker: prune notes after delete");
      setNote("已刪除 " + (ids.length - failed) + " 筆" + (failed ? "，失敗 " + failed + " 筆" : "") + "（GitHub Pages 約 1 分鐘後生效）。", failed ? "warn" : "ok");
      await load();
    } catch (e) { setNote("刪除失敗：" + e.message, "err"); }
  }

  // ---- 事件 ----
  function selectedIds() { return Object.keys(selected).filter(function (k) { return selected[k]; }); }
  function toggleAll(checked) { filteredSorted().forEach(function (t) { selected[t.trackId] = checked; }); render(); }

  $("tableWrap").addEventListener("click", function (e) {
    var b = e.target.closest("button[data-act]");
    if (b) {
      var id = b.dataset.id, act = b.dataset.act;
      if (act === "json") downloadJson(id).catch(function (er) { setNote("下載失敗：" + er.message, "err"); });
      else if (act === "gpx") downloadGpx(id).catch(function (er) { setNote("下載失敗：" + er.message, "err"); });
      else if (act === "edit") openEdit(id);
      else if (act === "del") deleteRecords([id]);
      return;
    }
    if (e.target.classList.contains("rowsel")) { selected[e.target.dataset.id] = e.target.checked; updateSelCount(); }
    if (e.target.id === "chkHead") toggleAll(e.target.checked);
  });

  $("search").addEventListener("input", render);
  $("modeFilter").addEventListener("change", render);
  $("sortSel").addEventListener("change", render);
  $("selectAll").addEventListener("click", function () { toggleAll(true); });
  $("downloadSel").addEventListener("click", async function () {
    var ids = selectedIds(); if (!ids.length) { setNote("請先選取紀錄。", "warn"); return; }
    setNote("下載中… 0 / " + ids.length, "");
    for (var i = 0; i < ids.length; i++) { try { await downloadJson(ids[i]); } catch (e) {} setNote("下載中… " + (i + 1) + " / " + ids.length, ""); await new Promise(function (r) { setTimeout(r, 250); }); }
    setNote("已下載 " + ids.length + " 筆 JSON。", "ok");
  });
  $("deleteSel").addEventListener("click", function () { deleteRecords(selectedIds()); });
  $("refresh").addEventListener("click", load);
  $("saveCfg").addEventListener("click", saveCfg);
  $("clearCfg").addEventListener("click", function () { try { localStorage.removeItem(LS.token); } catch (e) {} cfg.token = ""; applyCfgToForm(); render(); setNote("已清除 Token。", "ok"); });

  applyCfgToForm();
  load();
})();
