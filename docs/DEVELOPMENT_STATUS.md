# Development Status

## Current phase
Phase 2 — Android reliability. Three bounded increments are implemented and locally verified: network-transition policy extraction, numeric-literal DNS parsing, and foreground-service start-request failure propagation. Phase 2 is not complete.

## Completed
- Phase 0 source audit completed without project edits.
- Added generic `VpnEngine<Config>`, `VpnEngineStatus`, `WireGuardEngine`, and redacted `WireGuardConfig`; adapted `TunnelController` and injected the interface into `BoosterEngine` without changing WireGuard routing, handshake, retry, or failover decisions.
- Added tests for config package-list copying/private-key redaction.
- Added `NetworkTransitionPolicy` for initial, unchanged, changed, offline-to-online, and reset transitions; Android callback timing and reconnect execution remain in `BoosterEngine`.
- Added `DnsServerAddress`, which parses numeric IPv4/IPv6 literals without name resolution and rejects hostnames before tunnel activation.
- `BoosterService.start()` now returns the synchronous start-request result. `BoosterEngine` surfaces request failure for new connections and stops a restored tunnel if the service request fails, rather than treating it as monitored. Unit tests cover the request-result wrapper only.
- Added initial AI context, architecture, and technical-debt documents.

## Verification and limitations
`./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed with JDK 17 and Android SDK platform 34/build-tools 34.0.0. All 53 Android JVM tests passed (0 failures/errors/skips), and a 27 MB debug APK assembled. APK SHA-256: `50f5254faa1901dcb56386d9b4009a5a373916a55f1b5fc90faade6fce8f8651`.

The APK is an unconfigured debug/test build, not a production release. The service result covers only synchronous failure to submit `ContextCompat.startForegroundService`; it does not prove `onStartCommand()`/`startForeground()` succeeds or the service survives process death. No physical-device Wi-Fi/mobile handoff, callback timing, foreground-service recovery, DNS/IPv6 leak, live Supabase, or deployed gateway verification was performed. Gateway tests passed in Phase 0 (22 tests against fakes); no gateway code changed here.

## Latest verified source
The build used the current working tree based on `main` commit `a58f61c`. Phase 1 and Phase 2 changes remain uncommitted and unpushed. Pure policy/parser tests and request-result tests do not establish Android radio, callback, tunnel, service, or DNS behavior.

## Next action
Continue Phase 2 with a focused Android service/callback lifecycle increment or authorized-device validation. Do not claim network recovery or DNS leak protection complete until tested on an Android device.
