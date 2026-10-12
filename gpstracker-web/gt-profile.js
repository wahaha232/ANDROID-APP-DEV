// 可互動的剖面圖（海拔／速度）：canvas，支援滑鼠、觸控與鍵盤。
// 用法：var p = GTProfile.create(canvas, { color, unit, label, onHover(f|null), onSelect(f) });
//       p.setData(xs01, ys)  // xs01：0~1 遞增（水平位置），ys：數值
//       p.setCursor(f)       // 0~1 或 null
(function () {
  "use strict";

  function create(canvas, opt) {
    opt = opt || {};
    var color = opt.color || "#22d3ee", unit = opt.unit || "";
    var xs = [], ys = [], min = 0, max = 1, cursor = null, hover = null, W = 0, H = 0, raf = 0;
    var PAD_L = 8, PAD_R = 8, PAD_T = 18, PAD_B = 16;

    canvas.setAttribute("tabindex", "0");
    canvas.setAttribute("role", "slider");
    canvas.setAttribute("aria-label", opt.label || "剖面圖");
    canvas.setAttribute("aria-valuemin", "0"); canvas.setAttribute("aria-valuemax", "100");

    function resize() {
      var dpr = window.devicePixelRatio || 1;
      W = canvas.clientWidth || 300; H = canvas.clientHeight || 120;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      canvas.getContext("2d").setTransform(dpr, 0, 0, dpr, 0, 0);
      schedule();
    }
    function schedule() { if (!raf) raf = requestAnimationFrame(function () { raf = 0; draw(); }); }

    function X(f) { return PAD_L + (W - PAD_L - PAD_R) * f; }
    function Y(v) { return H - PAD_B - (H - PAD_T - PAD_B) * ((v - min) / (max - min)); }

    function draw() {
      var ctx = canvas.getContext("2d");
      ctx.clearRect(0, 0, W, H);
      if (!ys.length) return;
      var i;
      ctx.beginPath();
      for (i = 0; i < ys.length; i++) { if (i === 0) ctx.moveTo(X(xs[i]), Y(ys[i])); else ctx.lineTo(X(xs[i]), Y(ys[i])); }
      ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.stroke();
      ctx.lineTo(X(xs[xs.length - 1]), H - PAD_B); ctx.lineTo(X(xs[0]), H - PAD_B); ctx.closePath();
      var g = ctx.createLinearGradient(0, PAD_T, 0, H - PAD_B);
      g.addColorStop(0, color + "55"); g.addColorStop(1, color + "05");
      ctx.fillStyle = g; ctx.fill();

      ctx.fillStyle = "#9fb0cb"; ctx.font = "11px -apple-system, 'Segoe UI', sans-serif";
      ctx.textBaseline = "alphabetic";
      ctx.fillText(Math.round(max) + " " + unit, PAD_L, 12);
      ctx.fillText(Math.round(min) + "", PAD_L, H - 3);

      function line(f, c, dash) {
        var x = X(f);
        ctx.save(); ctx.strokeStyle = c; ctx.lineWidth = 1.5; if (dash) ctx.setLineDash([3, 3]);
        ctx.beginPath(); ctx.moveTo(x, PAD_T - 6); ctx.lineTo(x, H - PAD_B); ctx.stroke(); ctx.restore();
        var v = valueAt(f);
        ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.arc(x, Y(v), 4, 0, 6.2832); ctx.fill();
        ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.stroke();
        return { x: x, v: v };
      }
      if (hover != null) {
        var h = line(hover, "rgba(255,255,255,.45)", true);
        var text = Math.round(h.v) + " " + unit;
        var tw = ctx.measureText(text).width + 10, tx = Math.min(Math.max(h.x - tw / 2, 2), W - tw - 2);
        ctx.fillStyle = "rgba(9,14,25,.9)"; ctx.fillRect(tx, 2, tw, 16);
        ctx.fillStyle = "#fff"; ctx.fillText(text, tx + 5, 14);
      }
      if (cursor != null) line(cursor, "#ff7a45", false);
    }

    function valueAt(f) {
      if (!ys.length) return 0;
      var lo = 0, hi = xs.length - 1;
      while (lo < hi) { var mid = (lo + hi) >> 1; if (xs[mid] < f) lo = mid + 1; else hi = mid; }
      if (lo === 0) return ys[0];
      var a = xs[lo - 1], b = xs[lo], t = b > a ? (f - a) / (b - a) : 0;
      return ys[lo - 1] + (ys[lo] - ys[lo - 1]) * Math.min(1, Math.max(0, t));
    }

    function fracOf(ev) {
      var r = canvas.getBoundingClientRect();
      return Math.min(1, Math.max(0, (ev.clientX - r.left - PAD_L) / Math.max(1, r.width - PAD_L - PAD_R)));
    }

    var pressed = false;
    canvas.addEventListener("pointerdown", function (e) {
      pressed = true;
      try { canvas.setPointerCapture(e.pointerId); } catch (x) {}
      var f = fracOf(e); hover = f; schedule(); if (opt.onSelect) opt.onSelect(f);
    });
    canvas.addEventListener("pointermove", function (e) {
      var f = fracOf(e); hover = f; schedule();
      if (pressed && opt.onSelect) opt.onSelect(f); else if (opt.onHover) opt.onHover(f);
    });
    function up() { pressed = false; }
    canvas.addEventListener("pointerup", up);
    canvas.addEventListener("pointercancel", up);
    canvas.addEventListener("pointerleave", function () { if (pressed) return; hover = null; schedule(); if (opt.onHover) opt.onHover(null); });
    canvas.addEventListener("keydown", function (e) {
      var step = e.shiftKey ? 0.1 : 0.02, base = cursor == null ? 0 : cursor, n = null;
      if (e.key === "ArrowRight" || e.key === "ArrowUp") n = base + step;
      else if (e.key === "ArrowLeft" || e.key === "ArrowDown") n = base - step;
      else if (e.key === "Home") n = 0; else if (e.key === "End") n = 1;
      if (n == null) return;
      e.preventDefault();
      if (opt.onSelect) opt.onSelect(Math.min(1, Math.max(0, n)));
    });

    if (window.ResizeObserver) new ResizeObserver(resize).observe(canvas); else window.addEventListener("resize", resize);

    return {
      setData: function (x, y) {
        xs = x || []; ys = y || [];
        min = Infinity; max = -Infinity;
        for (var i = 0; i < ys.length; i++) { if (ys[i] < min) min = ys[i]; if (ys[i] > max) max = ys[i]; }
        if (!isFinite(min)) { min = 0; max = 1; }
        if (max === min) max = min + 1;
        resize();
      },
      setCursor: function (f) {
        cursor = f;
        canvas.setAttribute("aria-valuenow", f == null ? "0" : String(Math.round(f * 100)));
        schedule();
      },
      valueAt: valueAt
    };
  }

  window.GTProfile = { create: create };
})();
