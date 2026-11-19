#!/usr/bin/env bash
# Run as root on the Oracle Ubuntu VM (ARM64 or x86). Review before running.
# Optional env:  SSH_ALLOW_CIDR=203.0.113.4/32   GW_DOMAIN=gw.example.com   WG_PORT=51820
# Also open in the OCI VCN security list / NSG: UDP 51820 and TCP 443 (and TCP 22 from your IP only).
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }

WG_PORT=${WG_PORT:-51820}
WG_SUBNET=${WG_SUBNET:-10.8.0.0/24}
WG_ADDR=${WG_ADDR:-10.8.0.1/24}
SSH_ALLOW_CIDR=${SSH_ALLOW_CIDR:-}
GW_DOMAIN=${GW_DOMAIN:-}
SRC="$(cd "$(dirname "$0")" && pwd)"

apt-get update
apt-get install -y wireguard ufw fail2ban python3 curl
if ! apt-get install -y caddy; then
  echo "WARN: 'caddy' not installable from the default repos. Install it from https://caddyserver.com/docs/install"
  echo "      (or use another TLS reverse proxy to 127.0.0.1:8080). The app requires an https:// api_url."
fi

# ---------------- WireGuard ----------------
umask 077
mkdir -p /etc/wireguard
[ -f /etc/wireguard/server.key ] || wg genkey > /etc/wireguard/server.key
wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub
EXT_IF=$(ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
[ -n "$EXT_IF" ] || { echo "could not detect outbound interface"; exit 1; }

# Oracle's Ubuntu images ship iptables with a catch-all REJECT at the end of FORWARD, so our ACCEPTs must be
# INSERTED at the top (-I ... 1), not appended. The -C checks keep wg-quick restarts idempotent.
cat > /etc/wireguard/wg0.conf <<CONF
[Interface]
Address = ${WG_ADDR}
ListenPort = ${WG_PORT}
PrivateKey = $(cat /etc/wireguard/server.key)
PostUp = iptables -t nat -C POSTROUTING -s ${WG_SUBNET} -o ${EXT_IF} -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s ${WG_SUBNET} -o ${EXT_IF} -j MASQUERADE
PostUp = iptables -C FORWARD -i wg0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wg0 -j ACCEPT
PostUp = iptables -C FORWARD -o wg0 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -o wg0 -m state --state RELATED,ESTABLISHED -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s ${WG_SUBNET} -o ${EXT_IF} -j MASQUERADE || true
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT || true
PostDown = iptables -D FORWARD -o wg0 -m state --state RELATED,ESTABLISHED -j ACCEPT || true
CONF
chmod 600 /etc/wireguard/wg0.conf
# Peers are NOT written here: the agent owns them (state file -> wg set on every start).

echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wg.conf
sysctl --system >/dev/null
systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

# ---------------- Firewall + SSH ----------------
ufw default deny incoming
ufw default allow outgoing
if [ -n "$SSH_ALLOW_CIDR" ]; then
  ufw allow from "$SSH_ALLOW_CIDR" to any port 22 proto tcp
else
  echo "WARN: SSH_ALLOW_CIDR not set; SSH stays open to the internet (rate-limited via ufw). Re-run with SSH_ALLOW_CIDR=<your-ip>/32."
  ufw limit 22/tcp
fi
ufw allow ${WG_PORT}/udp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
systemctl enable --now fail2ban

# Key-only SSH, but only if a key is actually installed (avoid locking you out).
if compgen -G "/home/*/.ssh/authorized_keys" >/dev/null || [ -s /root/.ssh/authorized_keys ]; then
  cat > /etc/ssh/sshd_config.d/99-gamebooster.conf <<SSH
PasswordAuthentication no
PermitRootLogin prohibit-password
SSH
  sshd -t && { systemctl reload ssh 2>/dev/null || systemctl reload sshd; }
else
  echo "WARN: no authorized_keys found; leaving SSH authentication unchanged."
fi

# ---------------- Registration agent (unprivileged user) ----------------
id gbagent >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin gbagent
install -d -m 750 -o gbagent -g gbagent /var/lib/gamebooster
[ -e /var/lib/gamebooster/peers.json.lock ] || install -m 600 -o gbagent -g gbagent /dev/null /var/lib/gamebooster/peers.json.lock
install -m 755 "$SRC/agent.py" /usr/local/bin/gamebooster-agent

if [ ! -f /etc/gamebooster.env ]; then
  cat > /etc/gamebooster.env <<ENV
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-PUBLIC-KEY
WG_IFACE=wg0
WG_SUBNET=${WG_SUBNET}
WG_DNS=1.1.1.1
MAX_DEVICES_PER_USER=3
PEER_TTL_DAYS=30
ENV
  chmod 600 /etc/gamebooster.env
fi

cat > /etc/systemd/system/gamebooster-agent.service <<'UNIT'
[Unit]
Description=Game Booster registration agent
After=network-online.target wg-quick@wg0.service
Wants=network-online.target

[Service]
User=gbagent
Group=gbagent
EnvironmentFile=/etc/gamebooster.env
ExecStart=/usr/bin/python3 /usr/local/bin/gamebooster-agent serve
Restart=always
RestartSec=3
AmbientCapabilities=CAP_NET_ADMIN
CapabilityBoundingSet=CAP_NET_ADMIN
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
MemoryMax=128M
ReadWritePaths=/var/lib/gamebooster

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable gamebooster-agent

# ---------------- TLS front (Caddy) ----------------
if [ -n "$GW_DOMAIN" ] && command -v caddy >/dev/null; then
  cat > /etc/caddy/Caddyfile <<CADDY
${GW_DOMAIN} {
    reverse_proxy 127.0.0.1:8080
}
CADDY
  systemctl reload caddy || systemctl restart caddy
fi

if grep -q "YOUR-" /etc/gamebooster.env; then
  START="NOT started yet: edit /etc/gamebooster.env, then: systemctl restart gamebooster-agent"
else
  systemctl restart gamebooster-agent; START="agent started"
fi

cat <<MSG

== Done ($START) ==
Server WireGuard PUBLIC key (servers.wg_public_key in Supabase):
$(cat /etc/wireguard/server.pub)

Next:
 1. Set SUPABASE_URL / SUPABASE_ANON_KEY in /etc/gamebooster.env and restart the agent.
 2. TLS: point DNS for your domain at this VM, then re-run with GW_DOMAIN=<domain> (or configure Caddy yourself).
    Put https://<domain> in servers.api_url.
 3. Check:  curl -s http://127.0.0.1:8080/health      journalctl -u gamebooster-agent -n 50
 4. Admin:  sudo gamebooster-agent list | revoke <public_key> | purge-user <user_uuid>
 5. Verify forwarding rules sit above Oracle's REJECT:  iptables -S FORWARD | head
MSG
