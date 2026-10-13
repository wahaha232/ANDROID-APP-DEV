"""產生 gpstracker-web/data/metro.json：台北／新北捷運（含輕軌）的站點與站間路線幾何。

資料來源：OpenStreetMap（Overpass API），授權 ODbL — 使用時需標示「© OpenStreetMap contributors」。
這是網站用來「GPS 中斷（捷運內）時，依路線補畫估算路徑」的靜態參考資料，不是 App 上傳的活動資料。

用法（Python 3.8+，只用標準函式庫）：
  python tools/build-metro-data.py --fetch                 # 從 Overpass 下載並產生（需要網路）
  python tools/build-metro-data.py --raw-dir <資料夾>       # 使用先前下載的 raw.json / nodes.json
輸出：data/metro.json（約百 KB）。
"""
import argparse, heapq, json, math, os, sys, time, urllib.parse, urllib.request

BBOX = "24.85,121.25,25.30,121.75"   # 台北、新北（南緣 24.85、北緣 25.30、西 121.25、東 121.75）
Q_RELATIONS = '[out:json][timeout:180];(relation["route"~"^(subway|light_rail)$"](%s););out geom;' % BBOX
Q_NODES = '[out:json][timeout:120];relation["route"~"^(subway|light_rail)$"](%s)->.r;node(r.r);out tags;' % BBOX
API = "https://overpass-api.de/api/interpreter"
STOP_ROLES = ("stop", "stop_entry_only", "stop_exit_only")
SIMPLIFY_M = 4.0        # 路線幾何簡化誤差（公尺）
SAME_NAME_MAX_M = 700   # 同名站點視為同一個轉乘站的最大距離
SAME_PLACE_M = 60       # 不同名但幾乎同位置的站點視為同一站


def fetch(query):
    req = urllib.request.Request(API, data=urllib.parse.urlencode({"data": query}).encode(),
                                 headers={"User-Agent": "gpstracker-web-dev/1.0 (personal project)", "Accept": "*/*"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=200) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            print("fetch retry", attempt + 1, e, file=sys.stderr)
            time.sleep(5)
    raise SystemExit("Overpass 下載失敗")


def hav(a, b):
    R = 6371000.0
    la1, la2 = math.radians(a[1]), math.radians(b[1])
    dp, dl = la2 - la1, math.radians(b[0] - a[0])
    h = math.sin(dp / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def rdp(pts, eps):
    """Douglas-Peucker（用平面近似，單位公尺）。"""
    if len(pts) < 3:
        return pts
    k = math.cos(math.radians(pts[0][1]))
    xy = [(p[0] * 111320 * k, p[1] * 110540) for p in pts]
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        ax, ay = xy[i]; bx, by = xy[j]
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        best, bi = -1.0, -1
        for m in range(i + 1, j):
            px, py = xy[m]
            t = 0 if L2 == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / L2))
            d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > best:
                best, bi = d, m
        if best > eps:
            keep[bi] = True; stack += [(i, bi), (bi, j)]
    return [p for p, k2 in zip(pts, keep) if k2]


def shortest(graph, src, dst):
    dist = {src: 0.0}; prev = {}
    pq = [(0.0, src)]
    while pq:
        d, u = heapq.heappop(pq)
        if u == dst:
            break
        if d > dist.get(u, 1e18):
            continue
        for v, w in graph.get(u, ()):
            nd = d + w
            if nd < dist.get(v, 1e18):
                dist[v] = nd; prev[v] = u; heapq.heappush(pq, (nd, v))
    if dst not in dist:
        return None
    path = [dst]
    while path[-1] != src:
        path.append(prev[path[-1]])
    return path[::-1]


def build(raw, nodes):
    names, ntags = {}, {}
    for n in nodes["elements"]:
        t = n.get("tags", {})
        ntags[n["id"]] = t
        names[n["id"]] = (t.get("name:zh") or t.get("name") or "", t.get("name:en") or "")

    # 1) 每條路線：用「該路線自己的軌道頂點圖」求相鄰兩站之間的實際走法
    legs = []          # (lineRef, color, stopA(id,lat,lon), stopB, coords)
    lines = {}
    for rel in (e for e in raw["elements"] if e["type"] == "relation"):
        tags = rel.get("tags", {})
        ref = tags.get("ref") or tags.get("name:en") or tags.get("name") or "?"
        color = tags.get("colour") or "#888888"
        lines.setdefault(ref, {"n": tags.get("name:en") or tags.get("name") or ref, "c": color})
        graph, coord = {}, {}
        def key(p): return (round(p["lon"], 6), round(p["lat"], 6))
        for m in rel["members"]:
            if m["type"] != "way" or not m.get("geometry"):
                continue
            g = m["geometry"]
            for a, b in zip(g, g[1:]):
                ka, kb = key(a), key(b)
                coord[ka] = ka; coord[kb] = kb
                w = hav(ka, kb)
                graph.setdefault(ka, []).append((kb, w)); graph.setdefault(kb, []).append((ka, w))
        # 站點：role 為 stop*，或 role 是空的但節點本身是車站／站牌（OSM 各路線的標法不一致）。
        # 漏掉站點會讓相鄰兩站之間「跳站」，所以兩種都要收。platform 一律不算。
        def is_stop(m):
            if m["type"] != "node" or m.get("role") == "platform":
                return False
            if m.get("role") in STOP_ROLES:
                return True
            return m.get("role", "") == "" and ntags.get(m["ref"], {}).get("railway") in ("station", "stop")
        stops = [m for m in rel["members"] if is_stop(m)]
        if not graph or len(stops) < 2:
            continue
        verts = list(graph.keys())
        def nearest(s):
            p = (s["lon"], s["lat"])
            return min(verts, key=lambda v: (v[0] - p[0]) ** 2 + (v[1] - p[1]) ** 2)
        for sa, sb in zip(stops, stops[1:]):
            va, vb = nearest(sa), nearest(sb)
            path = [va] if va == vb else shortest(graph, va, vb)
            if not path:
                continue
            legs.append((ref, color, sa, sb, [list(p) for p in path]))

    # 2) 站點分群：同名（且距離不遠）或幾乎同位置 → 同一個轉乘站
    stop_nodes = {}
    for _, _, sa, sb, _ in legs:
        for s in (sa, sb):
            stop_nodes[s["ref"]] = s
    ids = list(stop_nodes)
    parent = {i: i for i in ids}
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    def union(a, b): parent[find(a)] = find(b)
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            sa, sb = stop_nodes[a], stop_nodes[b]
            d = hav((sa["lon"], sa["lat"]), (sb["lon"], sb["lat"]))
            na, nb = names.get(a, ("", ""))[0], names.get(b, ("", ""))[0]
            if d <= SAME_PLACE_M or (na and na == nb and d <= SAME_NAME_MAX_M):
                union(a, b)
    clusters = {}
    for i in ids:
        clusters.setdefault(find(i), []).append(i)
    cid = {}; stations = []
    for n, (root, members) in enumerate(sorted(clusters.items(), key=lambda kv: min(kv[1]))):
        lat = sum(stop_nodes[m]["lat"] for m in members) / len(members)
        lon = sum(stop_nodes[m]["lon"] for m in members) / len(members)
        nm = next((names[m][0] for m in members if names.get(m, ("", ""))[0]), "")
        en = next((names[m][1] for m in members if names.get(m, ("", ""))[1]), "")
        stations.append({"id": n, "n": nm, "e": en, "y": round(lat, 5), "x": round(lon, 5)})
        for m in members:
            cid[m] = n

    # 3) 站間邊（同線同站對只留一條；兩個方向的路線幾何擇一）
    edges, seen = [], set()
    for ref, color, sa, sb, path in legs:
        a, b = cid[sa["ref"]], cid[sb["ref"]]
        if a == b:
            continue
        k = (ref, min(a, b), max(a, b))
        if k in seen:
            continue
        seen.add(k)
        simp = rdp(path, SIMPLIFY_M)
        length = sum(hav(p, q) for p, q in zip(path, path[1:]))
        edges.append({"a": a, "b": b, "l": ref, "m": round(length), "g": [[round(p[0], 5), round(p[1], 5)] for p in simp]})
    return {"v": 1, "source": "OpenStreetMap contributors (ODbL) via Overpass API", "bbox": BBOX,
            "generated": time.strftime("%Y-%m-%d"), "lines": lines, "stations": stations, "edges": edges}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--raw-dir")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "metro.json"))
    a = ap.parse_args()
    if a.fetch:
        raw, nodes = fetch(Q_RELATIONS), fetch(Q_NODES)
    elif a.raw_dir:
        raw = json.load(open(os.path.join(a.raw_dir, "raw.json"), encoding="utf-8"))
        nodes = json.load(open(os.path.join(a.raw_dir, "nodes.json"), encoding="utf-8"))
    else:
        ap.error("需要 --fetch 或 --raw-dir")
    out = build(raw, nodes)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("stations:", len(out["stations"]), "edges:", len(out["edges"]), "lines:", len(out["lines"]),
          "size KB:", round(os.path.getsize(a.out) / 1024, 1))


if __name__ == "__main__":
    main()
