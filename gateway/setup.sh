#!/usr/bin/env bash
# Run as root on the Oracle Ubuntu VM (ARM64 or x86). Review before running.
# Also open in the OCI VCN security list / NSG: UDP 51820 and TCP 443 (and TCP 22 from your IP only).
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }

WG_PORT=${WG_PORT:-51820}
WG_SUBNET=${WG_SUBNET:-10.8.0.0/24}
WG_ADDR=${WG_ADDR:-10.8.0.1/24}

apt-get update
apt-get install -y wireguard ufw fail2ban python3 caddy

# --- WireGuard ---
umask 077
mkdir -p /etc/wireguard /var/lib/gamebooster
[ -f /etc/wireguard/server.key ] || wg genkey > /etc/wireguard/server.key
wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub
EXT_IF=$(ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')

cat > /etc/wireguard/wg0.conf <<CONF
[Interface]
Address = ${WG_ADDR}
ListenPort = ${WG_PORT}
PrivateKey = $(cat /etc/wireguard/server.key)
PostUp   = iptables -t nat -A POSTROUTING -s ${WG_SUBNET} -o ${EXT_IF} -j MASQUERADE; iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s ${WG_SUBNET} -o ${EXT_IF} -j MASQUERADE; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT
CONF
chmod 600 /etc/wireguard/wg0.conf

echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wg.conf
sysctl --system >/dev/null
systemctl enable --now wg-quick@wg0

# --- Firewall (keep SSH reachable first!) ---
ufw allow 22/tcp
ufw allow ${WG_PORT}/udp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
systemctl enable --now fail2ban

# --- Registration agent ---
install -m 755 "$(dirname "$0")/agent.py" /usr/local/bin/gamebooster-agent
if [ ! -f /etc/gamebooster.env ]; then
  cat > /etc/gamebooster.env <<ENV
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-PUBLIC-KEY
WG_IFACE=wg0
WG_SUBNET=${WG_SUBNET}
WG_DNS=1.1.1.1
ENV
  chmod 600 /etc/gamebooster.env
fi

cat > /etc/systemd/system/gamebooster-agent.service <<'UNIT'
[Unit]
Description=Game Booster registration agent
After=network-online.target wg-quick@wg0.service

[Service]
EnvironmentFile=/etc/gamebooster.env
ExecStart=/usr/local/bin/gamebooster-agent
Restart=always
MemoryMax=128M
NoNewPrivileges=true
ProtectHome=true
ReadWritePaths=/var/lib/gamebooster

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable gamebooster-agent

cat <<MSG

Server WireGuard PUBLIC key (put in Supabase servers.wg_public_key):
$(cat /etc/wireguard/server.pub)

Next:
 1. Edit /etc/gamebooster.env with your Supabase URL + anon key, then: systemctl restart gamebooster-agent
 2. Point a DNS name at this VM and create /etc/caddy/Caddyfile:
        gw.example.com {
            reverse_proxy 127.0.0.1:8080
        }
    then: systemctl reload caddy      (Caddy obtains the TLS certificate automatically)
 3. Put https://gw.example.com in servers.api_url.
MSG
