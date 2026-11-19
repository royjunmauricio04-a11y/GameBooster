-- Run in Supabase SQL editor. Writes to servers/game_profiles are admin-only (service role / dashboard).
create extension if not exists pgcrypto;

create table if not exists public.servers (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  location text not null default '',
  host text not null,                 -- gateway public IP / hostname
  wg_port int not null default 51820,
  wg_public_key text not null,        -- PUBLIC key only. Never store private keys here.
  api_url text not null,              -- https://gw.example.com  (Caddy -> agent)
  probe_port int not null default 443,
  enabled boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.game_profiles (
  id uuid primary key default gen_random_uuid(),
  package_name text not null unique,  -- Android package, e.g. com.example.game
  display_name text not null,
  probe_host text not null,           -- game server host used for before/after measurement
  probe_port int not null default 443,
  preferred_server_id uuid references public.servers(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists public.user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  settings jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.connection_events (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  server_id uuid references public.servers(id) on delete set null,
  game_package text not null,
  success boolean not null,
  created_at timestamptz not null default now()
);

alter table public.servers enable row level security;
alter table public.game_profiles enable row level security;
alter table public.user_settings enable row level security;
alter table public.connection_events enable row level security;

create policy "servers readable by signed-in users" on public.servers
  for select to authenticated using (enabled);
create policy "games readable by signed-in users" on public.game_profiles
  for select to authenticated using (true);
create policy "own settings" on public.user_settings
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own events read" on public.connection_events
  for select to authenticated using (user_id = auth.uid());
create policy "own events insert" on public.connection_events
  for insert to authenticated with check (user_id = auth.uid());

-- Example rows (edit after the gateway exists):
-- insert into servers (name, location, host, wg_public_key, api_url)
--   values ('Oracle Singapore', 'Singapore', 'GATEWAY_IP', 'SERVER_WG_PUBLIC_KEY', 'https://gw.example.com');
-- insert into game_profiles (package_name, display_name, probe_host)
--   values ('com.example.game', 'Example Game', 'game-server.example.com');
