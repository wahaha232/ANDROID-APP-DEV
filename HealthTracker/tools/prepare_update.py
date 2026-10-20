"""準備 App 內雲端更新要上傳的兩個檔案（不連網、不需要任何密碼）。

用法（先 gradlew assembleDebug）：
    python tools/prepare_update.py

做的事：
1. 取 app/build/outputs/apk/debug/app-debug.apk，只留 arm64 的原生函式庫（檔案小一半，Supabase 單檔上限 50 MB）；
2. 用 debug 金鑰重新簽章（和手機上已安裝的版本同一把，才能覆蓋更新）；
3. 在 tools/update_out/ 產生 APK 與 latest.json（含 versionCode、SHA-256）。

接著由你本人到 Supabase 後台 Storage → app-updates，把這兩個檔案拖進去（覆蓋舊的）。
App 的「設定 > 關於 > 檢查更新」就會看到新版。
"""
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
OUT = os.path.join(ROOT, 'tools', 'update_out')


def sdk_build_tools():
    props = open(os.path.join(ROOT, 'local.properties'), encoding='utf-8').read()
    sdk = re.search(r'^sdk\.dir=(.+)$', props, re.M).group(1).strip().replace('\\\\', '\\').replace('\\:', ':')
    bt = os.path.join(sdk, 'build-tools')
    return os.path.join(bt, sorted(os.listdir(bt))[-1])


def main():
    if not os.path.isfile(SRC):
        sys.exit('找不到 app-debug.apk，請先執行 gradlew assembleDebug')
    bt = sdk_build_tools()
    os.makedirs(OUT, exist_ok=True)
    raw = os.path.join(OUT, 'raw.apk')
    aligned = os.path.join(OUT, 'aligned.apk')
    with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(raw, 'w', zipfile.ZIP_DEFLATED) as zout:
        for it in zin.infolist():
            n = it.filename
            if n.startswith('lib/') and not n.startswith('lib/arm64-v8a/'):
                continue
            if n.startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')):
                continue
            zi = zipfile.ZipInfo(n, it.date_time)
            zi.compress_type = zipfile.ZIP_STORED if it.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            zout.writestr(zi, zin.read(n))

    badging = subprocess.run([os.path.join(bt, 'aapt.exe'), 'dump', 'badging', raw], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    package, code, name = m.group(1), int(m.group(2)), m.group(3)
    apk_name = f'healthtracker-{name}-arm64.apk'
    final = os.path.join(OUT, apk_name)
    for f in (aligned, final):
        if os.path.exists(f):
            os.remove(f)
    subprocess.run([os.path.join(bt, 'zipalign.exe'), '-p', '4', raw, aligned], check=True)
    subprocess.run(['cmd', '/c', os.path.join(bt, 'apksigner.bat'), 'sign', '--ks', os.path.expanduser(r'~\.android\debug.keystore'),
                    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey',
                    '--out', final, aligned], check=True)
    subprocess.run(['cmd', '/c', os.path.join(bt, 'apksigner.bat'), 'verify', final], check=True)
    for f in (raw, aligned):
        os.remove(f)

    import hashlib
    h = hashlib.sha256()
    with open(final, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    size = os.path.getsize(final)
    if size > 50 * 1024 * 1024:
        sys.exit(f'APK 有 {size / 1e6:.1f} MB，超過 Supabase 單檔 50 MB 上限')
    info = dict(versionCode=code, versionName=name, apk=apk_name, sha256=h.hexdigest(), size=size, notes='', package=package)
    with open(os.path.join(OUT, 'latest.json'), 'w', encoding='utf-8') as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
    print(f'完成：{final}  {size / 1e6:.1f} MB')
    print(f'latest.json：versionCode={code}, versionName={name}')
    print('請到 Supabase 後台 Storage → app-updates，上傳 tools/update_out 裡的 APK 與 latest.json。')


if __name__ == '__main__':
    main()
