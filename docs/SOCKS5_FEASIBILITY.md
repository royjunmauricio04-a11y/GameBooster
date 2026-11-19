# SOCKS5 fallback: feasibility evaluation (Phase 8)

**Decision: do not implement yet.** There is no demonstrated technical benefit, and the measurements the brief
asks for could not be taken in the authoring environment (no device, no Android SDK, no network).
Everything below about third-party software is from documentation/memory and is **unverified here**.

## What problem would a fallback solve?
The only scenario where WireGuard fails and a proxy might work: a network that blocks UDP (or the WG port).
Since Phase 7, the app records this directly: a connect that never completes a WireGuard handshake fails with a
specific message and is logged as `reason = 'handshake'` in `connection_events`.

## Evaluation against the brief
| Question | Finding |
|---|---|
| Proxy vs VPN | SOCKS5 is a per-connection proxy protocol, not an encrypted tunnel. Its username/password auth is plaintext. Over the internet it would need a TLS/SSH wrapper to be safe. Must be labelled "proxy", never "VPN". |
| Android integration | Needs a `VpnService` TUN plus a tun2socks engine that turns IP packets into SOCKS5 sessions (a native library: Go via gomobile, or C). Candidates to evaluate: `xjasonlyu/tun2socks`, `hev-socks5-tunnel`. `badvpn-tun2socks` needs server-side udpgw for UDP. |
| TCP and UDP | TCP via CONNECT is universal. UDP needs SOCKS5 `UDP ASSOCIATE`: Dante supports it; `microsocks` is, to my knowledge, TCP-only (verify). **A UDP-blocking network blocks the UDP relay too**, so for UDP-only games SOCKS5 gives no benefit exactly where WireGuard fails. |
| Encrypting the transport | A TLS/SSH wrapper can carry the TCP control and data, but not the UDP relay. TCP-wrapped carriage of game traffic adds TCP-over-TCP head-of-line blocking, which is bad for latency-sensitive games. |
| DNS | A TUN-based engine must handle DNS itself (fake-IP or DNS-over-proxy). Leak behaviour would need dedicated tests. |
| Reliability vs WireGuard | Cannot be compared without a prototype. WireGuard is in-kernel/Go, handshake-based with roaming; a tun2socks stack adds a userspace TCP/IP stack and per-flow state. |
| CPU / RAM / battery | **Not measured.** No numbers are claimed. |

## Likely net benefit
Only TCP-based games on UDP-blocking networks. Narrow, and it adds a native dependency, a second gateway
service to secure (Dante + TLS), another auth path, and more battery/CPU on low-end devices.

## Gate to revisit
Implement only if production data shows a meaningful share of failed connects with `reason = 'handshake'`
AND those users' games are TCP-based. Query: `select reason, count(*) from connection_events where success = false group by 1;`

## If approved, prototype plan
1. Gateway: Dante with `udp` enabled behind stunnel/TLS; separate unprivileged user and firewall rules.
2. App: engine behind an interface next to `TunnelController`; WireGuard stays the default and primary.
3. Measure on a real low-end device, same network, both engines: connect time, CPU %, RSS, battery drain over
   30 min, DNS leak test, UDP echo loss/jitter, Wi-Fi/mobile switch recovery. Publish the numbers before shipping.
