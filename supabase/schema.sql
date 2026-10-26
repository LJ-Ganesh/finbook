-- Run this once in Supabase: SQL Editor -> New query -> paste -> Run.
-- One row per purchase. Row level security makes sure each signed-in user
-- can only see, add, change or delete their own rows.

create table if not exists public.holdings (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  symbol      text not null check (symbol ~ '^[A-Z0-9&-]+\.(NS|BO)$'),
  name        text not null,
  bought_on   date not null,
  price       numeric not null check (price > 0),
  quantity    numeric not null check (quantity > 0),
  created_at  timestamptz not null default now()
);

create index if not exists holdings_user_id_idx on public.holdings (user_id);

alter table public.holdings enable row level security;

drop policy if exists "Read own holdings" on public.holdings;
drop policy if exists "Add own holdings" on public.holdings;
drop policy if exists "Change own holdings" on public.holdings;
drop policy if exists "Delete own holdings" on public.holdings;

create policy "Read own holdings" on public.holdings
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "Add own holdings" on public.holdings
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "Change own holdings" on public.holdings
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Delete own holdings" on public.holdings
  for delete to authenticated using ((select auth.uid()) = user_id);
