package com.startinsnow.gpstracker.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 對應規格「47~50、79. Browser Generator / TrackHtmlGenerator」。
 * 產生完全獨立可離線開啟的 index.html（地圖圖磚仍需要網路，其餘資料與互動全部內嵌，不需伺服器）。
 * 使用 Leaflet（不是 Google Maps），Playback 邏輯與 [com.startinsnow.gpstracker.playback.TrackPlaybackEngine] 對齊。
 */
object TrackHtmlGenerator {

    fun generate(data: ExportTrackData, tileProvider: TileProviderConfig = TileProviderConfig.DEFAULT_OSM): String {
        val trustedPoints = data.points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
        val pointsJs = trustedPoints.joinToString(",") { p ->
            "[${p.latitude},${p.longitude},${p.timestampMs},${p.speedMps ?: 0f},\"${p.movementMode.name}\"]"
        }
        val photosJs = data.photos.filter { it.latitude != null && it.longitude != null }.joinToString(",") { photo ->
            "{id:\"${photo.photoId}\",lat:${photo.latitude},lon:${photo.longitude},t:${photo.timestampMs},type:\"${photo.type.name}\",file:\"photos/${photo.filePath.substringAfterLast('/')}\"}"
        }
        val outagesJs = data.outages.joinToString(",") { outage ->
            "{start:${outage.startTimestampMs},end:${outage.endTimestampMs ?: outage.startTimestampMs}}"
        }

        val dateFmt = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val start = data.track.startTimeMs
        val end = data.track.endTimeMs ?: start
        val durationLabel = formatDuration(data.track.durationMs)

        return """
<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>GPS TRACKER - ${dateFmt.format(Date(start))}</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<style>
  body { margin:0; font-family: -apple-system, "Noto Sans TC", sans-serif; background:#0b1220; color:#eaf0f7; }
  #map { height: 60vh; width: 100%; }
  .panel { padding: 12px 16px; }
  .stats { display:flex; flex-wrap:wrap; gap:12px; }
  .stat { background:#131c2e; border-radius:10px; padding:10px 14px; min-width:110px; }
  .stat b { display:block; font-size:18px; }
  .controls { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  button { background:#1565C0; color:#fff; border:none; border-radius:8px; padding:8px 14px; cursor:pointer; }
  input[type=range] { flex:1; min-width:150px; }
  select { padding:6px; border-radius:6px; }
  h1 { font-size:20px; margin:0 0 6px 0; }
</style>
</head>
<body>
<div id="map"></div>
<div class="panel">
  <h1>🛰️ GPS TRACKER｜${dateFmt.format(Date(start))}</h1>
  <div class="stats">
    <div class="stat">Start<b>${timeFmt.format(Date(start))}</b></div>
    <div class="stat">End<b>${timeFmt.format(Date(end))}</b></div>
    <div class="stat">Distance<b>${"%.1f".format(data.track.distanceMeters / 1000.0)} km</b></div>
    <div class="stat">Duration<b>$durationLabel</b></div>
    <div class="stat">Average<b>${"%.1f".format(data.track.avgSpeedMps * 3.6)} km/h</b></div>
    <div class="stat">Maximum<b>${"%.1f".format(data.track.maxSpeedMps * 3.6)} km/h</b></div>
    <div class="stat">Steps<b>${data.track.stepCount}</b></div>
    <div class="stat">Photos<b>${data.photos.size}</b></div>
  </div>
  <div class="controls" style="margin-top:14px;">
    <button id="playBtn">▶ Play</button>
    <button id="pauseBtn">⏸ Pause</button>
    <button id="stopBtn">⏹ Stop</button>
    <select id="speedSelect">
      <option value="1">1×</option>
      <option value="2">2×</option>
      <option value="5" selected>5×</option>
      <option value="10">10×</option>
      <option value="20">20×</option>
    </select>
    <input type="range" id="slider" min="0" max="1000" value="0" />
    <span id="progressLabel">00:00:00</span>
  </div>
</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const TRACK_POINTS = [$pointsJs]; // [lat, lon, timestampMs, speedMps, mode]
const PHOTOS = [$photosJs];
const OUTAGES = [$outagesJs];

const map = L.map('map');
L.tileLayer('${tileProvider.tileUrlTemplate}', {
  maxZoom: ${tileProvider.maxZoom},
  attribution: '${tileProvider.attributionHtml.replace("'", "\\'")}'
}).addTo(map);

if (TRACK_POINTS.length > 0) {
  const latlngs = TRACK_POINTS.map(p => [p[0], p[1]]);
  const poly = L.polyline(latlngs, { color: '#00C853', weight: 4 }).addTo(map);
  map.fitBounds(poly.getBounds(), { padding: [24, 24] });

  L.marker(latlngs[0]).addTo(map).bindPopup('🟢 Start');
  L.marker(latlngs[latlngs.length - 1]).addTo(map).bindPopup('🔴 End');
} else {
  map.setView([25.03, 121.56], 13);
}

PHOTOS.forEach(p => {
  const marker = L.marker([p.lat, p.lon]).addTo(map);
  marker.bindPopup('📷 ' + p.type + '<br/><img src="' + p.file + '" style="max-width:200px;max-height:200px;" />');
});

OUTAGES.forEach(o => {
  // 找出中斷前後最近的可信點，畫出 GPS outage 提示，而不是直接連一條假的直線。
  const before = TRACK_POINTS.filter(p => p[2] <= o.start).pop();
  if (before) {
    L.marker([before[0], before[1]], {
      icon: L.divIcon({ html: '⚠️', className: 'outage-icon', iconSize: [20, 20] })
    }).addTo(map).bindPopup('GPS 訊號中斷 ' + Math.round((o.end - o.start) / 60000) + ' 分鐘');
  }
});

// ---- Playback engine：邏輯對齊 TrackPlaybackEngine.kt 的線性內插規則 ----
let playing = false;
let speedMultiplier = 5;
let elapsedMs = 0;
let lastTick = null;
let marker = null;
const totalDurationMs = TRACK_POINTS.length > 1 ? (TRACK_POINTS[TRACK_POINTS.length - 1][2] - TRACK_POINTS[0][2]) : 0;

function frameAt(elapsed) {
  if (TRACK_POINTS.length === 0) return null;
  if (TRACK_POINTS.length === 1) return { lat: TRACK_POINTS[0][0], lon: TRACK_POINTS[0][1], mode: TRACK_POINTS[0][4] };
  const target = TRACK_POINTS[0][2] + Math.min(elapsed, totalDurationMs);
  let lo = 0, hi = TRACK_POINTS.length - 1;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (TRACK_POINTS[mid][2] <= target) lo = mid; else hi = mid - 1;
  }
  const before = TRACK_POINTS[lo];
  if (lo >= TRACK_POINTS.length - 1) return { lat: before[0], lon: before[1], mode: before[4] };
  const after = TRACK_POINTS[lo + 1];
  const segDur = Math.max(1, after[2] - before[2]);
  const frac = Math.min(1, Math.max(0, (target - before[2]) / segDur));
  return {
    lat: before[0] + (after[0] - before[0]) * frac,
    lon: before[1] + (after[1] - before[1]) * frac,
    mode: before[4]
  };
}

function updateMarker() {
  const frame = frameAt(elapsedMs);
  if (!frame) return;
  if (!marker) {
    marker = L.marker([frame.lat, frame.lon]).addTo(map);
  } else {
    marker.setLatLng([frame.lat, frame.lon]);
  }
  map.panTo([frame.lat, frame.lon], { animate: false });
  document.getElementById('slider').value = totalDurationMs > 0 ? Math.round((elapsedMs / totalDurationMs) * 1000) : 0;
  document.getElementById('progressLabel').textContent = formatDuration(elapsedMs) + ' ' + (frame.mode || '');
}

function formatDuration(ms) {
  const s = Math.floor(ms / 1000);
  const h = String(Math.floor(s / 3600)).padStart(2, '0');
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const sec = String(s % 60).padStart(2, '0');
  return h + ':' + m + ':' + sec;
}

function tick(ts) {
  if (!playing) return;
  if (lastTick != null) {
    elapsedMs += (ts - lastTick) * speedMultiplier;
    if (elapsedMs >= totalDurationMs) {
      elapsedMs = totalDurationMs;
      playing = false;
    }
  }
  lastTick = ts;
  updateMarker();
  if (playing) requestAnimationFrame(tick);
}

document.getElementById('playBtn').onclick = () => {
  if (elapsedMs >= totalDurationMs) elapsedMs = 0;
  playing = true; lastTick = null;
  requestAnimationFrame(tick);
};
document.getElementById('pauseBtn').onclick = () => { playing = false; };
document.getElementById('stopBtn').onclick = () => { playing = false; elapsedMs = 0; updateMarker(); };
document.getElementById('speedSelect').onchange = (e) => { speedMultiplier = parseFloat(e.target.value); };
document.getElementById('slider').oninput = (e) => {
  playing = false;
  elapsedMs = (parseFloat(e.target.value) / 1000) * totalDurationMs;
  updateMarker();
};

updateMarker();
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
