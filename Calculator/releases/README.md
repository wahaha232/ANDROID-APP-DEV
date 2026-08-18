# Releases

`calculator-latest.apk` — 最新一次 GitHub Actions 編譯成功的 debug APK，手動更新（每次確認 CI 過了才會覆蓋這個檔案，不是自動同步）。

`calculator-SIGNED-release.apk` / `.aab` — 正式簽署版本，使用**正式的 AdMob App ID / 廣告單元 ID**。**不要**拿這個版本給封閉測試（closed testing）的外部測試者使用，多人密集點擊正式廣告有觸發 AdMob 無效流量機制、導致帳號被限流或停權的風險。

`calculator-SIGNED-release-ADMOB-TEST.apk` / `.aab` — 功能與正式版完全相同，同一把正式簽署金鑰簽署，**唯一差異是 AdMob App ID / 廣告單元 ID 換成 Google 官方公開的測試值**（`ca-app-pub-3940256099942544~3347511713` / `ca-app-pub-3940256099942544/6300978111`），畫面上的廣告會顯示「Test Ad」標籤。**專供 Play Console 封閉測試階段使用**，測試者怎麼點都不會影響正式 AdMob 帳號。正式上架（Production 軌道）前，請改用不含 `ADMOB-TEST` 字樣的正式版本，並記得調高 `versionCode`。

下載後傳到 Android 手機安裝即可測試，需先允許安裝不明來源應用程式。
