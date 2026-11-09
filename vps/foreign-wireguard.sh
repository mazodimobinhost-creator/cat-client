#!/bin/bash
# Cat VPS WireGuard — روی سرور خارج اجرا کن (root).
# بعد /root/cat-wg-phone.conf را در Cat بچسبان.
set -euo pipefail
umask 077

IRAN="${IRAN:-}"
FOREIGN="${FOREIGN:-CHANGE_ME}"
WG_PORT="${WG_PORT:-51820}"

if [ "$(id -u)" -ne 0 ]; then echo "run as root"; exit 1; fi
if [ "$FOREIGN" = CHANGE_ME ] || [ -z "$FOREIGN" ] || [ "$FOREIGN" = "127.0.0.1" ]; then
  echo "Set FOREIGN at the top of this file (the public IP of THIS server)."
  exit 1
fi
if [ "$WG_PORT" = 80 ] || [ "$WG_PORT" = 443 ] || [ "$WG_PORT" = 1080 ]; then
  echo "WG_PORT cannot be 80, 443 or 1080"; exit 1
fi
ENDPOINT="$FOREIGN"
if [ -n "$IRAN" ] && [ "$IRAN" != CHANGE_ME ]; then ENDPOINT="$IRAN"; fi

export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y wireguard iptables curl ca-certificates
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y wireguard-tools iptables curl ca-certificates
else
  echo "need apt-get or dnf"; exit 1
fi

cat > /etc/sysctl.d/99-cat-vps.conf << 'CAT_SYSCTL'
net.ipv4.ip_forward=1
net.ipv6.conf.all.forwarding=1
CAT_SYSCTL
sysctl --system >/dev/null 2>&1 || sysctl -p /etc/sysctl.d/99-cat-vps.conf >/dev/null 2>&1 || true
sysctl -w net.ipv4.ip_forward=1 >/dev/null

modprobe wireguard 2>/dev/null || true
mkdir -p /etc/wireguard
wg-quick down wg0 >/dev/null 2>&1 || true
if [ -f /etc/wireguard/wg0.conf ]; then cp -a /etc/wireguard/wg0.conf /etc/wireguard/wg0.conf.bak.cat; fi

SERVER_PRIV=$(wg genkey)
SERVER_PUB=$(printf '%s\n' "$SERVER_PRIV" | wg pubkey)
CLIENT_PRIV=$(wg genkey)
CLIENT_PUB=$(printf '%s\n' "$CLIENT_PRIV" | wg pubkey)
PSK=$(wg genpsk)
WAN=$(ip -4 route show default 2>/dev/null | awk '{print $5; exit}' || true)
WAN=${WAN:-eth0}

cat > /etc/wireguard/wg0.conf << EOF
[Interface]
Address = 10.77.0.1/24
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIV}
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o ${WAN} -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o ${WAN} -j MASQUERADE

[Peer]
PublicKey = ${CLIENT_PUB}
PresharedKey = ${PSK}
AllowedIPs = 10.77.0.2/32
EOF
chmod 600 /etc/wireguard/wg0.conf

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then
  ufw allow "$WG_PORT"/udp || true
  ufw allow "$WG_PORT"/tcp || true
fi

wg-quick up wg0
systemctl enable wg-quick@wg0 >/dev/null 2>&1 || true

cat > /root/cat-wg-phone.conf << EOF
[Interface]
PrivateKey = ${CLIENT_PRIV}
Address = 10.77.0.2/32
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = ${SERVER_PUB}
PresharedKey = ${PSK}
Endpoint = ${ENDPOINT}:${WG_PORT}
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
EOF
chmod 600 /root/cat-wg-phone.conf
echo "==== paste /root/cat-wg-phone.conf in Cat ===="
cat /root/cat-wg-phone.conf

if [ "$ENDPOINT" != "$FOREIGN" ]; then
  echo "Also run iran-dnat.sh on IRAN $IRAN  (WG_PORT=$WG_PORT FOREIGN=$FOREIGN)"
fi
