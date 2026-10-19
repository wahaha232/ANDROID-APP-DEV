"""本機測試用的假 Supabase + 靜態檔伺服器（只在開發時用，不會發佈）。
用法：python web-dev/mock_server.py   然後開 http://localhost:8791/
測試帳號（假的，只對這個假伺服器有效）：demo@example.com / demo-pass
"""
import io, json, os, random, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get('MOCK_PORT', '8791'))
WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'web')
EMAIL, PASSWORD = 'demo@example.com', 'demo-pass'
EXPIRES_IN = int(os.environ.get('MOCK_EXPIRES_IN', '3600'))
PHOTO_ID = '123e4567-e89b-12d3-a456-426614174000'
USER_ID = '00000000-0000-4000-8000-000000000001'
PHOTO_PATH = f'{USER_ID}/{PHOTO_ID}.jpg'

now = int(time.time() * 1000)
DAY = 86400000
random.seed(7)
ROWS = []
def add(t, v, v2, days_ago, source='MANUAL', note='', photo=None, deleted=None, context=None):
    ROWS.append(dict(id=str(uuid.uuid4()), type=t, value=v, value2=v2, measured_at=now - int(days_ago * DAY),
                     source=source, note=note, photo_object=photo, deleted_at=deleted, context=context))
for i in range(0, 40, 2):
    add('WEIGHT', round(70 + random.uniform(-1.5, 1.5), 1), None, i, 'MIJIA' if i % 4 == 0 else 'MANUAL')
add('WEIGHT', 99.9, None, 1, note='已刪除的不應出現', deleted=now)
add('WEIGHT', 71.0, None, 200, note='半年多前')
for i in range(0, 30, 5): add('BODY_FAT', round(22 + random.uniform(-1, 1), 1), None, i, 'MIJIA')
add('BLOOD_GLUCOSE', 112, None, 0.2, 'OCR', '早餐前', PHOTO_PATH, context='FASTING')
add('BLOOD_GLUCOSE', 98, None, 1.1, 'MANUAL', '<img src=x onerror=alert(1)> 備註含 HTML')
add('BLOOD_GLUCOSE', 135, None, 3, 'OCR', 'photo 不存在', f'{USER_ID}/{uuid.uuid4()}.jpg', context='AFTER_MEAL')
add('BLOOD_PRESSURE', 120, 80, 2)
add('BLOOD_PRESSURE', 118, 76, 9)

def make_jpeg():
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (600, 400), (200, 215, 190))
    d = ImageDraw.Draw(im)
    d.text((220, 150), '112 mg/dL (mock photo)', fill=(20, 20, 20))
    buf = io.BytesIO(); im.save(buf, 'JPEG'); return buf.getvalue()
JPEG = make_jpeg()

def token(email, refresh_n=0):
    return dict(access_token=f'mock-access-{refresh_n}-{int(time.time())}', refresh_token=f'mock-refresh-{refresh_n + 1}',
                expires_in=EXPIRES_IN, token_type='bearer', user=dict(id=USER_ID, email=email))

class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a): print(self.command, self.path.split('?')[0], '->', a[1] if len(a) > 1 else '')
    def _send(self, code, body=b'', ctype='application/json'):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Access-Control-Allow-Headers', '*'); self.end_headers(); self.wfile.write(body)
    def _authed(self):
        return self.headers.get('apikey') and (self.headers.get('Authorization') or '').startswith('Bearer mock-access-')
    def do_OPTIONS(self): self._send(204)
    def do_POST(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if u.path == '/auth/v1/token':
            if q.get('grant_type') == ['password']:
                if body.get('email') == EMAIL and body.get('password') == PASSWORD: return self._send(200, token(EMAIL))
                return self._send(400, dict(error_code='invalid_credentials'))
            if q.get('grant_type') == ['refresh_token'] and str(body.get('refresh_token', '')).startswith('mock-refresh-'):
                return self._send(200, token(EMAIL, int(body['refresh_token'].split('-')[-1])))
            return self._send(400, dict(error_code='bad_refresh'))
        self._send(404, {})
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path == '/rest/v1/health_records':
            if not self._authed(): return self._send(401, dict(code='PGRST301'))
            rows = [r for r in ROWS if r['deleted_at'] is None] if q.get('deleted_at') == ['is.null'] else list(ROWS)
            if 'type' in q: rows = [r for r in rows if f"eq.{r['type']}" == q['type'][0]]
            if 'measured_at' in q: rows = [r for r in rows if r['measured_at'] >= int(q['measured_at'][0].removeprefix('gte.'))]
            rows.sort(key=lambda r: (r['measured_at'], r['id']), reverse=True)
            rows = rows[:int(q.get('limit', ['1000'])[0])]
            cols = None if q.get('select', ['*'])[0] == '*' else q['select'][0].split(',')
            return self._send(200, [{k: r[k] for k in cols} if cols else r for r in rows])
        if u.path == '/storage/v1/object/authenticated/meter-photos/' + PHOTO_PATH:
            if not self._authed(): return self._send(401, {})
            return self._send(200, JPEG, 'image/jpeg')
        if u.path.startswith('/storage/'): return self._send(404, dict(error='not_found'))
        rel = 'index.html' if u.path in ('/', '') else u.path.lstrip('/')
        f = os.path.normpath(os.path.join(WEB, rel))
        if not f.startswith(os.path.normpath(WEB)) or not os.path.isfile(f): return self._send(404, b'not found', 'text/plain')
        data = open(f, 'rb').read()
        ctype = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css'}.get(os.path.splitext(f)[1], 'application/octet-stream')
        if rel == 'index.html':  # 只有開發用的假伺服器放寬 CSP，讓頁面能連到 localhost
            data = data.replace(b'connect-src https://*.supabase.co', f'connect-src http://localhost:{PORT} http://127.0.0.1:{PORT} https://*.supabase.co'.encode())
        self._send(200, data, ctype)

if __name__ == '__main__':
    print(f'http://localhost:{PORT}/  帳號 {EMAIL} 密碼 {PASSWORD}')
    ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
