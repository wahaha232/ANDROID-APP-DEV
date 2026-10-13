// 分享圖片：把一筆活動畫成一張 1080×1350 的 PNG（路線＋統計），可用系統分享面板送出，或直接下載。
// 路線可依速度／海拔上色（與活動頁 3D 預覽同一套色階，來自 gt-core.js）。只畫資料裡實際存在的欄位。
// 依賴：gt-core.js（GTCore）。需要瀏覽器的 canvas；沒有任何網路請求，圖片只在本機產生。
(function () {
  "use strict";

  var C = GTCore;
  var W = 1080, H = 1350;
  var FONT = '"Noto Sans TC","Microsoft JhengHei","PingFang TC",system-ui,sans-serif';

  function fitText(ctx, text, maxW, size, weight) {
    var s = size;
    ctx.font = weight + " " + s + "px " + FONT;
    while (s > 30 && ctx.measureText(text).width > maxW) { s -= 2; ctx.font = weight + " " + s + "px " + FONT; }
    return s;
  }

  // opts: { points, title, whenText, modeText, fields[{label,value,unit}], colorMode ("" | "speed" | "altitude") }
  function drawCard(opts) {
    var cv = document.createElement("canvas");
    cv.width = W; cv.height = H;
    var ctx = cv.getContext("2d");

    var bg = ctx.createLinearGradient(0, 0, W, H);
    bg.addColorStop(0, "#070b14"); bg.addColorStop(1, "#10233f");
    ctx.fillStyle = bg; ctx.fillRect(0, 0, W, H);
    ctx.strokeStyle = "rgba(34,211,238,0.06)"; ctx.lineWidth = 1;
    for (var g = 0; g <= W; g += 54) { ctx.beginPath(); ctx.moveTo(g, 0); ctx.lineTo(g, H); ctx.stroke(); }
    for (var h = 0; h <= H; h += 54) { ctx.beginPath(); ctx.moveTo(0, h); ctx.lineTo(W, h); ctx.stroke(); }

    // 標題
    ctx.textBaseline = "alphabetic"; ctx.textAlign = "left";
    ctx.fillStyle = "#eaf0fb";
    fitText(ctx, opts.title, W - 120, 68, "800");
    ctx.fillText(opts.title, 60, 130);
    ctx.fillStyle = "#8c9ab3"; ctx.font = "500 34px " + FONT;
    ctx.fillText(opts.whenText + (opts.modeText ? "・" + opts.modeText : ""), 60, 190);

    // 路線
    var rx = 60, ry = 240, rw = W - 120, rh = 700;
    ctx.fillStyle = "rgba(15,24,40,0.85)"; ctx.strokeStyle = "#2c3f63"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.roundRect ? ctx.roundRect(rx, ry, rw, rh, 28) : ctx.rect(rx, ry, rw, rh); ctx.fill(); ctx.stroke();
    var proj = C.routeProject(opts.points, rw, rh, 56, 700);
    var legend = null;
    if (proj) {
      var values = opts.colorMode ? C.valueSeries(opts.points, opts.colorMode) : null;
      var range = values ? C.valueRange(values) : null;
      ctx.save(); ctx.translate(rx, ry);
      ctx.lineCap = "round"; ctx.lineJoin = "round";
      // 光暈
      ctx.strokeStyle = "rgba(34,211,238,0.25)"; ctx.lineWidth = 22; ctx.beginPath();
      proj.pts.forEach(function (q, i) { if (i === 0) ctx.moveTo(q.x, q.y); else ctx.lineTo(q.x, q.y); });
      ctx.stroke();
      ctx.lineWidth = 9;
      if (values) {
        for (var i = 1; i < proj.pts.length; i++) {
          var a = proj.pts[i - 1], b = proj.pts[i];
          var v = (values[a.i] + values[b.i]) / 2;
          ctx.strokeStyle = C.rampColor(opts.colorMode, (v - range.lo) / (range.hi - range.lo));
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        }
        legend = { range: range, mode: opts.colorMode };
      } else {
        ctx.strokeStyle = "#22d3ee"; ctx.beginPath();
        proj.pts.forEach(function (q, i) { if (i === 0) ctx.moveTo(q.x, q.y); else ctx.lineTo(q.x, q.y); });
        ctx.stroke();
      }
      function dot(q, color) { ctx.fillStyle = color; ctx.strokeStyle = "#04121f"; ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(q.x, q.y, 15, 0, Math.PI * 2); ctx.fill(); ctx.stroke(); }
      dot(proj.pts[0], "#34d399"); dot(proj.pts[proj.pts.length - 1], "#ff7a45");
      ctx.restore();
    } else {
      ctx.fillStyle = "#8c9ab3"; ctx.font = "500 34px " + FONT; ctx.textAlign = "center";
      ctx.fillText("沒有可顯示的路線資料", W / 2, ry + rh / 2); ctx.textAlign = "left";
    }
    // 色階圖例
    if (legend) {
      var lx = rx + 28, ly = ry + rh - 52, lw = 360, grad = ctx.createLinearGradient(lx, 0, lx + lw, 0);
      for (var t = 0; t <= 1.001; t += 0.1) grad.addColorStop(Math.min(1, t), C.rampColor(legend.mode, t));
      ctx.fillStyle = grad; ctx.fillRect(lx, ly, lw, 12);
      ctx.fillStyle = "#8c9ab3"; ctx.font = "500 24px " + FONT;
      var unit = legend.mode === "speed" ? " km/h" : " m";
      ctx.fillText(Math.round(legend.range.lo) + unit, lx, ly + 38);
      ctx.textAlign = "right"; ctx.fillText(Math.round(legend.range.hi) + unit, lx + lw, ly + 38); ctx.textAlign = "left";
    }

    // 統計（最多 4 項，2×2）
    var fields = (opts.fields || []).slice(0, 4), cw = (W - 120 - 24) / 2, chh = 140;
    fields.forEach(function (f, n) {
      var cx = 60 + (n % 2) * (cw + 24), cy = 976 + Math.floor(n / 2) * (chh + 20);
      ctx.fillStyle = "rgba(15,24,40,0.9)"; ctx.strokeStyle = "#1f2d47"; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.roundRect ? ctx.roundRect(cx, cy, cw, chh, 22) : ctx.rect(cx, cy, cw, chh); ctx.fill(); ctx.stroke();
      ctx.fillStyle = "#eaf0fb"; ctx.font = "800 54px " + FONT;
      ctx.fillText(f.value, cx + 28, cy + 70);
      var vw = ctx.measureText(f.value).width;
      ctx.fillStyle = "#8c9ab3"; ctx.font = "600 28px " + FONT;
      if (f.unit) ctx.fillText(f.unit, cx + 28 + vw + 10, cy + 70);
      ctx.font = "500 26px " + FONT; ctx.fillText(f.label, cx + 28, cy + 114);
    });

    ctx.fillStyle = "#4c8dff"; ctx.font = "700 30px " + FONT; ctx.textAlign = "right";
    ctx.fillText("GPS Tracker", W - 60, H - 36);
    return cv;
  }

  function download(blob, name) {
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
  }

  // 產生圖片並分享（支援就叫出系統分享面板，否則下載）。回傳要顯示給使用者的訊息（空字串＝不顯示）。
  function shareCard(opts, fileName) {
    var cv;
    try { cv = drawCard(opts); } catch (e) { return Promise.resolve("無法產生圖片：" + e.message); }
    return new Promise(function (resolve) { cv.toBlob(resolve, "image/png"); }).then(function (blob) {
      if (!blob) return "無法產生圖片";
      var file = typeof File === "function" ? new File([blob], fileName, { type: "image/png" }) : null;
      if (file && navigator.canShare && navigator.canShare({ files: [file] })) {
        return navigator.share({ files: [file], title: opts.title }).then(function () { return "已分享"; }, function (e) {
          if (e && e.name === "AbortError") return "";
          download(blob, fileName); return "已下載圖片";
        });
      }
      download(blob, fileName);
      return "已下載圖片";
    });
  }

  window.GTShare = { drawCard: drawCard, shareCard: shareCard };
})();
