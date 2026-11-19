# Game Booster (Android)

Per-app WireGuard tunnel for games, with real latency/jitter/loss measurement.
Open this folder in Android Studio or build with `./gradlew :app:assembleDebug` (Android SDK 34 required).

## Plug in credentials later
1. Copy `local.properties.example` -> `local.properties`; set `SUPABASE_URL` and `SUPABASE_ANON_KEY` (public anon key only).
2. Supabase: run `supabase/schema.sql`; add rows to `servers` and `game_profiles`.
3. Oracle VM: copy `gateway/` to it, run `sudo ./setup.sh`, follow the printed steps.
   Open UDP 51820 and TCP 443 in the OCI security list as well as the VM firewall.

## How it works
- Sign in (Supabase Auth) -> load servers + game profiles (RLS-protected).
- Connect: baseline probe -> pick server (preferred / lowest measured latency) -> device key
  (generated on device, private key in EncryptedSharedPreferences) -> gateway agent verifies JWT and
  authorises the public key -> WireGuard comes up for the selected game's package only.
- Dashboard shows before/now latency, jitter, loss (TCP connect time), duration, rx/tx.

## Known limits
- Not compiled or run in the authoring environment: build and test on a device first.
- WireGuard is UDP; networks that block UDP will fail. SOCKS5 fallback is NOT implemented (needs a native tun2socks library to carry a full tunnel).
- Auto-reconnect: if the tunnel drops or its handshake goes stale, the app retries with backoff and fails over to the next-best measured server (up to 4 tries).
- Latency is a TCP-connect proxy and varies; no ping improvement is guaranteed.
