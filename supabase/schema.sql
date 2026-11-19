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
  game_package text not null check (length(game_package) <= 200),
  success boolean not null,
  reason text check (reason in ('handshake','register','other')),   -- coarse failure category only
  created_at timestamptz not null default now()
);

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  public_key text not null unique check (public_key ~ '^[A-Za-z0-9+/]{43}=$'),  -- public key only
  server_id uuid references public.servers(id) on delete set null,
  created_at timestamptz not null default now()
);

create index if not exists connection_events_user_time on public.connection_events (user_id, created_at desc);

alter table public.servers enable row level security;
alter table public.devices enable row level security;
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

create policy "own devices" on public.devices
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Per-user manual games (Phase 6)
create table if not exists public.user_games (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  package_name text not null check (length(package_name) <= 200 and package_name ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'),
  display_name text not null check (length(display_name) between 1 and 80),
  probe_host text not null check (probe_host ~ '^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$'),
  probe_port int not null default 443 check (probe_port between 1 and 65535),
  created_at timestamptz not null default now(),
  unique (user_id, package_name)
);
alter table public.user_games enable row level security;
create policy "own user_games" on public.user_games
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create or replace function public.limit_user_games() returns trigger language plpgsql as $$
begin
  if (select count(*) from public.user_games where user_id = new.user_id) >= 50 then
    raise exception 'game limit (50) reached';
  end if;
  return new;
end $$;
create or replace trigger user_games_limit before insert on public.user_games
  for each row execute function public.limit_user_games();

-- Retention: keep connection history 90 days. Schedule with pg_cron (enable the extension first), e.g.
--   select cron.schedule('prune-events', '17 3 * * *', $$select public.prune_connection_events(90)$$);
create or replace function public.prune_connection_events(keep_days int default 90)
returns void language sql security definer set search_path = public as $$
  delete from public.connection_events where created_at < now() - make_interval(days => keep_days);
$$;
revoke all on function public.prune_connection_events(int) from public, anon, authenticated;

-- Example rows (edit after the gateway exists):
-- insert into servers (name, location, host, wg_public_key, api_url)
--   values ('Oracle Singapore', 'Singapore', 'GATEWAY_IP', 'SERVER_WG_PUBLIC_KEY', 'https://gw.example.com');
-- insert into game_profiles (package_name, display_name, probe_host)
--   values ('com.example.game', 'Example Game', 'game-server.example.com');
