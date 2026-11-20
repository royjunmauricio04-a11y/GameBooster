# Game Booster (Android)

[![Download latest debug APK](https://img.shields.io/badge/Download-Latest%20APK-blue?logo=android)](https://github.com/royjunmauricio04-a11y/GameBooster/releases/download/gamebooster-latest/app-debug.apk)

> Test/debug build only—not a Play Store release. This repository is private, so only people with repository access can download the APK. The app still needs a configured Supabase project and gateway to connect.

Per-app WireGuard tunnel for games with real, honestly-labelled network measurements.
Open this folder in Android Studio (it generates the Gradle wrapper).

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
3. Open **Actions → Android debug APK → Run workflow** on `main`. After the build succeeds, the README download button points to the updated private debug APK. Only people with repository access can download it.

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

## Features
- **Connect:** baseline probe (game IP resolved once, pre-tunnel) -> server (explicit > per-game preference > catalog preference > automatic) -> device key (on device, encrypted prefs) -> gateway verifies JWT, authorises key -> WireGuard up for the game package (+ this app, so live probes use the tunnel) -> **must complete a WireGuard handshake within 8 s**, otherwise that server fails (with a specific "UDP may be blocked" message) and the next one is tried.
- **Games:** catalog (`game_profiles`) plus per-user manual games (`user_games`, RLS, max 50), installed-only, per-game preferred server, quick-launch after connect.
- **Server choice:** automatic ranking uses an *estimate* = phone->gateway TCP connect + gateway->game TCP connect (measured by the gateway via `/probe`, only for hosts in your own profiles). Servers without a gateway->game number get a fixed 150 ms penalty.
- **Resilience:** process-scoped engine + foreground service; UI re-syncs with the real tunnel on launch; reconnect with failover on dead tunnel, stale handshake (>4 min) or network change; optional auto-reconnect switch.
- **Battery:** while the UI is hidden only a 5 s liveness check runs (no probes, no stats).
- **Diagnostics tab:** handshake status, tunnel DNS, per-server measurements, event log, copyable report (no tokens, keys or email).
- **Gateway:** per-device peers (max 3/user), key-ownership checks, revoke (owner via `/revoke`, admin via CLI), 30-day stale expiry, startup reconcile, rate limits, 60 s token cache capped at token `exp`, SSRF-hardened `/probe` (allow-listed hosts, public IPs only), non-root service.
- **Evidence for SOCKS5 decision:** failed connects are logged with a coarse reason (`handshake` / `register` / `other`); see `docs/SOCKS5_FEASIBILITY.md` (decision: not implemented yet).

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
