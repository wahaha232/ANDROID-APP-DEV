-- HealthTracker 的 Supabase 結構（在 Supabase 後台 SQL Editor 整段貼上執行一次）。
-- 設計：只有登入者本人能讀寫自己的資料（Row Level Security）；不開放 delete，刪除一律用 deleted_at 軟刪除。
-- 單人自用：建議到 Authentication 設定關閉「Allow new users to sign up」，只保留你自己建立的那個使用者。

create table if not exists public.health_records (
  id           uuid primary key,
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  type         text not null check (type in ('WEIGHT', 'BODY_FAT', 'BLOOD_GLUCOSE', 'BLOOD_PRESSURE')),
  value        double precision not null,
  value2       double precision,
  measured_at  bigint not null,            -- epoch millis（量測時間）
  source       text not null default 'MANUAL',
  note         text not null default '',
  created_at   bigint not null,
  updated_at   bigint not null,            -- 用戶端時間，衝突時以較新者為準
  deleted_at   bigint,
  photo_object text,                       -- Storage 物件路徑：<user_id>/<id>.jpg
  synced_at    timestamptz not null default now()  -- 伺服器時間，供拉取增量用
);

create index if not exists health_records_user_synced_idx on public.health_records (user_id, synced_at);
create index if not exists health_records_user_measured_idx on public.health_records (user_id, type, measured_at desc);

-- 每次寫入都由伺服器蓋上 synced_at，避免依賴手機時鐘。
create or replace function public.touch_synced_at() returns trigger
language plpgsql as $$
begin
  new.synced_at := now();
  return new;
end $$;

drop trigger if exists health_records_touch on public.health_records;
create trigger health_records_touch before insert or update on public.health_records
  for each row execute function public.touch_synced_at();

alter table public.health_records enable row level security;

drop policy if exists "own select" on public.health_records;
drop policy if exists "own insert" on public.health_records;
drop policy if exists "own update" on public.health_records;

create policy "own select" on public.health_records for select to authenticated
  using (user_id = auth.uid());
create policy "own insert" on public.health_records for insert to authenticated
  with check (user_id = auth.uid());
create policy "own update" on public.health_records for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 私人照片 bucket：非公開，路徑第一段必須是自己的 user id。
insert into storage.buckets (id, name, public)
values ('meter-photos', 'meter-photos', false)
on conflict (id) do nothing;

drop policy if exists "own photos select" on storage.objects;
drop policy if exists "own photos insert" on storage.objects;
drop policy if exists "own photos update" on storage.objects;
drop policy if exists "own photos delete" on storage.objects;

create policy "own photos select" on storage.objects for select to authenticated
  using (bucket_id = 'meter-photos' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "own photos insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'meter-photos' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "own photos update" on storage.objects for update to authenticated
  using (bucket_id = 'meter-photos' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "own photos delete" on storage.objects for delete to authenticated
  using (bucket_id = 'meter-photos' and (storage.foldername(name))[1] = auth.uid()::text);
