-- 升級：為既有的 health_records 加上「血糖測量情境」欄位。
-- 在 Supabase 後台 SQL Editor 貼上整段執行一次即可（重複執行也安全）。
alter table public.health_records add column if not exists context text;
do $$ begin
  alter table public.health_records
    add constraint health_records_context_valid
    check (context is null or context in ('FASTING', 'BEFORE_MEAL', 'AFTER_MEAL', 'BEDTIME'));
exception when duplicate_object then null; end $$;
