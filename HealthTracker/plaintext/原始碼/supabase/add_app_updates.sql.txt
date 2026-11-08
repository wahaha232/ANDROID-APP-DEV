-- App 內雲端更新用的私人 bucket。在 Supabase 後台 SQL Editor 整段貼上執行一次。
-- 只開放「登入者讀取」；寫入（上傳新版 APK 與 latest.json）不開放給任何 App 使用者，
-- 只能由你本人在 Supabase 後台 Storage 介面手動上傳，所以就算有人註冊帳號也無法放惡意檔案進來。
-- 上傳的檔案：tools/prepare_update.py 產生的 APK 與 latest.json，兩個檔案放在 bucket 根目錄。

insert into storage.buckets (id, name, public, file_size_limit)
values ('app-updates', 'app-updates', false, 52428800)
on conflict (id) do nothing;

drop policy if exists "app updates read" on storage.objects;
create policy "app updates read" on storage.objects for select to authenticated
  using (bucket_id = 'app-updates');
