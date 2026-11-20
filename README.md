# GameBooster (Android)

[![Download latest debug APK](https://img.shields.io/badge/Download-GameBooster%20v0.2.0-blue?logo=android)](https://github.com/royjunmauricio04-a11y/GameBooster/releases/download/gamebooster-latest/GameBooster-v0.2.0.apk)

> Test/debug build only—not a Play Store release. This repository is private, so only people with repository access can download the APK. The app still needs a configured Supabase project and gateway to connect.

Per-app WireGuard tunnel for games with real, honestly-labelled network measurements.
Open this folder in Android Studio (it generates the Gradle wrapper).

## Current app version
`GameBooster v0.2.0` (Android version code 2). The APK asset is versioned so it is clear which build is being installed.

## Plug in credentials later
1. `local.properties.example` -> `local.properties`: set `SUPABASE_URL`, `SUPABASE_ANON_KEY` (public anon key only).
2. Supabase: run `supabase/schema.sql`; add rows to `servers` and `game_profiles`.
3. Oracle VM: copy `gateway/` over, `sudo SSH_ALLOW_CIDR=<ip>/32 GW_DOMAIN=<domain> ./setup.sh`, follow the printed steps.
   Open UDP 51820 and TCP 443 in the OCI security list as well as the VM firewall.

### Email confirmation callback
The Android sign-up request sends Supabase users to `com.gamebooster.app://auth/callback` after they confirm their email. In the Supabase dashboard, open **Authentication → URL Configuration** and add this exact value under **Additional Redirect URLs**:

```text
com.gamebooster.app://auth/callback
```

Do not leave the Supabase **Site URL** set to `http://localhost:3000`; use a real HTTPS page or the app callback appropriate for the project. Email confirmation must remain enabled. The Android app uses only the public anon/publishable key; never use a `service_role` or `sb_secret_…` key in the app or signup request.

### Build a configured APK from a phone
1. In the private GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**.
2. Add `SUPABASE_URL` (the HTTPS Supabase Project URL) and `SUPABASE_ANON_KEY` (the public anon JWT with `role: anon` or `sb_publishable_…`). The workflow rejects service-role JWTs and `sb_secret_…` keys; never bundle those in an Android APK.
3. Open **Actions → Android debug APK → Run workflow** on the branch being tested. After the build succeeds, the README download button points to the updated private debug APK. Only people with repository access can download it.

If either secret is missing, CI can still run tests, but it will not replace the downloadable APK with an unconfigured build. The admin dashboard will need its own server-side secret setup; never put the Supabase `service_role` key in the browser, APK, README, or GitHub source.

## Verification status
| Area | State |
|---|---|
| Gateway agent (`gateway/agent.py`) | **Tested**: 22 automated tests (`python3 -m unittest gateway/tests/test_agent.py`) against a fake `wg` and fake Supabase over real HTTP |
| `gateway/setup.sh` | Syntax-checked only. **Never run** on an OCI VM |
| Android app | **Compiled in GitHub Actions** with Android SDK 34 as a debug APK; not yet tested on a physical device |
| `GameInputTest.kt` (JUnit) | Run in GitHub Actions |
| Supabase schema | **Never executed** |
| Handshake gate, network-change reconnect, foreground service | Unverified on real devices |
| v0.3 smart switch, history, overlay, circuit breaker | **Not compiled, not run** (hand-reviewed only); first CI run will tell |

## Features
- **Connect:** baseline probe (game IP resolved once, pre-tunnel) -> server (explicit > per-game preference > catalog preference > automatic) -> device key (on device, encrypted prefs) -> gateway verifies JWT, authorises key -> WireGuard up for the game package (+ this app, so live probes use the tunnel) -> **must complete a WireGuard handshake within 8 s**, otherwise that server fails (with a specific "UDP may be blocked" message) and the next one is tried.
- **Games:** catalog (`game_profiles`) plus per-user manual games (`user_games`, RLS, max 50), installed-only, per-game preferred server, quick-launch after connect.
- **Server choice:** automatic ranking uses an *estimate* = phone->gateway TCP connect + gateway->game TCP connect (measured by the gateway via `/probe`, only for hosts in your own profiles). Servers without a gateway->game number get a fixed 150 ms penalty.
- **Resilience:** process-scoped engine + foreground service; UI re-syncs with the real tunnel on launch; reconnect with failover on dead tunnel, stale handshake (>4 min) or network change; optional auto-reconnect switch.
- **Battery:** the hidden app performs a light three-sample probe every 60 s so session history remains useful; smart switching uses a 15 s cadence, the open UI 10 s, and the overlay 5 s. Disable smart switching and overlay when battery life is more important than live telemetry.
- **Diagnostics tab:** handshake status, tunnel DNS, per-server measurements, event log, copyable report (no tokens, keys or email).
- **Gateway:** per-device peers (max 3/user), key-ownership checks, revoke (owner via `/revoke`, admin via CLI), 30-day stale expiry, startup reconcile, rate limits, 60 s token cache capped at token `exp`, SSRF-hardened `/probe` (allow-listed hosts, public IPs only), non-root service.
- **Evidence for SOCKS5 decision:** failed connects are logged with a coarse reason (`handshake` / `register` / `other`); see `docs/SOCKS5_FEASIBILITY.md` (decision: not implemented yet).

## v0.3 systems (new, uncompiled and untested on a device)
- **Smart server switching** (`core/SmartSwitchPolicy.kt`, off by default, toggle in Diagnostics → Settings): while connected, the live TCP-connect probe is watched. After 3 consecutive bad samples (>=5 % loss, no response, or latency > 1.8x baseline and +40 ms) the app measures the other servers **bound to the physical network** (so the probe does not go through our own tunnel) and switches only if one is cheaper by >=20 ms and >=25 %. Limits: 2 min switch cool-down, 1 min between re-evaluations. A switch re-establishes the tunnel (a few seconds), which can drop a game connection.
- **Backup routes / circuit breaker** (`core/RouteHealth.kt`): a server that fails twice (handshake/register failure, or a dead tunnel that was not a network change) is skipped for 5 min, then gets one trial. Reconnect goes straight to the best backup instead of retrying a tripped server. The ranked standby list is shown on the Connect tab. **This is failover, not multi-hop**: chaining two gateways would need gateway-to-gateway WireGuard forwarding, which the gateway agent does not have.
- **Session history & analytics** (`core/SessionRecord.kt`, `data/HistoryStore.kt`, History tab): per session, TCP-connect avg / best / p95 / jitter / loss vs the direct baseline, servers used, switches, reconnects, traffic, and a chart. Stored locally (last 200, JSON lines) and wiped on sign-out; a numbers-only summary is uploaded to `session_summaries` (run the new block at the end of `supabase/schema.sql`).
- **In-game overlay** (`overlay/OverlayController.kt`, owned by `BoosterService`, off by default): draggable ping / jitter / loss HUD, needs the "Display over other apps" permission. Some competitive games or anti-cheat may object to overlay apps; use at your own risk.
- **Probe cadence** (changed): overlay 5 s, UI open 10 s, smart switching while hidden 15 s, otherwise a light 3-sample probe every 60 s so history has data. The old "hidden = no probes" battery claim no longer holds.
- **Tests**: `app/src/test/.../core/CoreLogicTest.kt` (policy, circuit breaker, recorder, codec, overlay text). Written but not run: no Kotlin/Android toolchain was available when they were authored.

Operational setup, permissions, Supabase migration steps, and tuning guidance are in [`docs/V0.3_OPERATIONS.md`](docs/V0.3_OPERATIONS.md).

## Known limits
- Measurements are **TCP connect times** (not ICMP, not in-game latency). Many games use UDP; the probe port may not reflect their path.
- The server ranking is an estimate, not an end-to-end measurement.
- WireGuard is UDP: UDP-blocking networks fail (now reported clearly). **No SOCKS5 fallback.**
- IPv6 from tunnelled apps is dropped (no v6 tunnel address), not tunnelled.
- Revocation is not instant for an already-registered peer unless revoked or expired; the JWT cache can delay session revocation by up to 60 s.
- Per-game server preference is stored on the device only (not synced across devices).
- `/probe` runs a blocking DNS lookup; slow resolvers can hold a worker (bounded by the 32-connection cap and rate limits).
- Foreground service type `specialUse` needs Play Console justification if published.
- No routing options beyond per-app (no per-game extra packages, no split by destination).
