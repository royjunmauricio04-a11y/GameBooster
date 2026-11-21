# Architecture

## Current system

- **Android application:** Compose UI delegates connection work to process-scoped `BoosterEngine`, which coordinates authentication, server selection, probes, session history, network callbacks, reconnect, and failover. `NetworkTransitionPolicy` is a pure core policy for detecting a changed or restored underlying network; Android callback timing and reconnect execution remain in `BoosterEngine`.
- **VPN engine boundary:** `VpnEngine<Config>` in `app/src/main/java/com/gamebooster/app/vpn/VpnEngine.kt` defines connect/disconnect/status. `WireGuardEngine` adds Android consent, handshake-age, and traffic-statistics capabilities required by the current coordinator. `NetworkTransitionPolicy` isolates underlying-network signature decisions, and `DnsServerAddress` accepts literal IPv4/IPv6 DNS addresses without name resolution.
- **Android transport and service:** `TunnelController` implements `WireGuardEngine` with WireGuard GoBackend. It includes the selected game and GameBooster packages, routes IPv4/IPv6 default ranges to the peer, and configures the registration DNS. The current traffic configuration is per-app split tunneling, not a verified device-wide VPN. `BoosterService` keeps the process and monitor alive; synchronous start-request failures are reported to `BoosterEngine`, which aborts new connection setup or stops a restored tunnel. This does not establish successful asynchronous service startup or process-death recovery.
- **Control plane:** `SupabaseApi`/`SessionStore` handle user auth, server/device registration, and local encrypted session/key storage. `gateway/agent.py` manages WireGuard peers and TCP probes. `supabase/schema.sql` defines catalog and user-scoped data with RLS.

## Planned boundaries
The lifecycle interface is transport-neutral, while `WireGuardEngine` and `WireGuardConfig` intentionally retain current transport-specific needs. A future SOCKS5/TUN implementation must provide a separate config and platform adapter rather than alter WireGuard behavior. Shared control/policy code should remain separate from platform-specific tunnel implementations.

## Not implemented or not verified
There is no Windows client or SOCKS5/tun2socks engine in the current source. The tun2socks RFC is design documentation. No physical-device VPN, foreground-service recovery, live Supabase schema, live OCI gateway, production signing, IPv6 leak, or end-to-end DNS behavior has been verified.
