# AI Context

## Product and constraints
GameBooster is for private, authorized devices. Preserve the Android WireGuard baseline, prioritize privacy/security and low operating cost, and do not claim behavior is complete without appropriate tests. Do not create billable resources without explicit permission. The OCI region is undecided; no new Supabase project or gateway deployment is authorized by the current work.

## Current architecture and key files
- Android coordinator/UI state: `app/src/main/java/com/gamebooster/app/ui/BoosterEngine.kt`
- VPN engine contracts/config: `app/src/main/java/com/gamebooster/app/vpn/VpnEngine.kt`
- Current Android WireGuard adapter: `app/src/main/java/com/gamebooster/app/vpn/TunnelController.kt`
- Android foreground service: `app/src/main/java/com/gamebooster/app/BoosterService.kt`
- Auth/API and encrypted local session/key storage: `app/src/main/java/com/gamebooster/app/data/SupabaseApi.kt`, `SessionStore.kt`
- Gateway control plane: `gateway/agent.py`; schema: `supabase/schema.sql`
- Android tests: `app/src/test`; gateway tests: `gateway/tests/test_agent.py`

## Conventions and constraints
Keep engine contracts narrow; retain WireGuard-specific capabilities in `WireGuardEngine`, not the transport-neutral lifecycle interface. Never log or stringify private keys/tokens. Do not silently change per-app routing, DNS, retry, or failover behavior while extracting interfaces. Separate CI/mock evidence from physical-device and live-infrastructure verification.

## Baseline
The sandbox has Android SDK platform 34/build-tools 34.0.0 under `$HOME/android-sdk` and OpenJDK 17. The local Android toolchain setup followed the official [Android developer tools](https://developer.android.com/tools) and [sdkmanager](https://developer.android.com/tools/sdkmanager) guidance. The current engine-boundary, network-policy, DNS-literal, and foreground-service synchronous start-result working tree compiled; all 53 Android JVM tests passed, and debug APK assembly succeeded (SHA-256 `50f5254faa1901dcb56386d9b4009a5a373916a55f1b5fc90faade6fce8f8651`). The service result does not validate asynchronous Android service startup or process-death recovery. No physical-device callback/radio handoff, tunnel behavior, DNS leaks, or live infrastructure has been verified. Changes remain uncommitted.
