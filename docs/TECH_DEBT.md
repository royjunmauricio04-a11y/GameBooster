# Technical Debt and Open Risks

## Android / tunnel
- **DNS bootstrap:** `TunnelController` now requires a numeric IPv4/IPv6 literal via `DnsServerAddress`, which performs no hostname lookup; server-provided hostnames fail closed. This removes the identified pre-tunnel lookup path in code, but DNS routing and leak behavior still need physical-device testing.
- **IPv6 leak protection:** the tunnel configuration routes `::/0` but has no assigned IPv6 tunnel address. The source comment intends IPv6 to be dropped; no device test proves that traffic cannot escape.
- **Lifecycle races:** the pure network transition decision is unit-tested, but callback timing and Wi-Fi/mobile reconnect execution are not. Connect, disconnect, and reconnect are not protected by one explicit lifecycle mutex; validate on-device and add coordination tests before changing this behavior.
- **Shutdown observability:** `TunnelController.disconnect()` intentionally swallows backend shutdown errors, and a failed `status()` lookup is reported as disconnected. Callers cannot distinguish a confirmed stop from a backend error; preserve safe best-effort cleanup but consider exposing diagnostic failure state.
- **Service/network handling:** synchronous exceptions from `ContextCompat.startForegroundService` now reach `BoosterEngine`; new connection setup aborts, and restored tunnels are stopped if that request fails. This does not capture later `onStartCommand()`/`startForeground()` failures or prove process-death recovery. Network callback registration failures are now visible in diagnostics and the engine has an explicit shutdown/unregister path, but physical handoff and service recovery remain unverified.
- **Test coverage:** current Android JVM tests cover pure policies, data parsing, config redaction/copying, and synchronous service-request result handling, but not GoBackend, Android service lifecycle, network callbacks, DNS/IPv6 leakage, or reconnect races.
- **Manifest:** verify the merged manifest includes the required WireGuard VPN service declaration; source manifest alone does not establish dependency manifest merging.

## Gateway / data
- Gateway tests use fake `wg` and fake Supabase. They do not establish real JWT, WireGuard, TLS/reverse-proxy, firewall, or deployed behavior.
- `/health` is a liveness response, not a check of Supabase/WireGuard readiness. Probe authorization is cached; token revocation may be delayed by the auth cache.
- The user-game cap is enforced by a count-before-insert trigger that may race under concurrent inserts.
- Retention functions exist, but scheduled pruning is not configured by the schema itself.

## Release and infrastructure
- No physical-device end-to-end verification, live gateway deployment, or production signing has been completed.
- Supabase schema has not been executed in a live project. OCI region is undecided; defer project creation/deployment until chosen.
- Phase 1/2 working-tree source was committed and pushed to `main` as `8b0bf14`. Local JDK 17/SDK 34 build and all 53 Android JVM tests passed. The GitHub Actions run was intentionally canceled before its tests/artifact/public-release steps because pushing to `main` would invoke an existing workflow that may replace the public debug APK. Do not report remote CI success until a later run completes.
