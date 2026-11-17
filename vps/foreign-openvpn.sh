#!/bin/bash
# Cat VPS OpenVPN TCP — روی سرور خارج اجرا کن (root).
# Cat این را وصل نمی‌کند؛ برای Cat از foreign-wireguard.sh استفاده کن.
set -euo pipefail
umask 077

IRAN="${IRAN:-}"
FOREIGN="${FOREIGN:-CHANGE_ME}"
OVPN_PORT="${OVPN_PORT:-1194}"

if [ "$(id -u)" -ne 0 ]; then echo "run as root"; exit 1; fi
if [ "$FOREIGN" = CHANGE_ME ] || [ -z "$FOREIGN" ] || [ "$FOREIGN" = "127.0.0.1" ]; then
  echo "Set FOREIGN at the top of this file (the public IP of THIS server)."
  exit 1
fi
if [ "$OVPN_PORT" = 80 ] || [ "$OVPN_PORT" = 443 ] || [ "$OVPN_PORT" = 1080 ]; then
  echo "OVPN_PORT cannot be 80, 443 or 1080"; exit 1
fi
ENDPOINT="$FOREIGN"
if [ -n "$IRAN" ] && [ "$IRAN" != CHANGE_ME ]; then ENDPOINT="$IRAN"; fi

export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y openvpn iptables
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y openvpn iptables
else
  echo "need apt-get or dnf"; exit 1
fi

cat > /etc/sysctl.d/99-cat-vps.conf << 'CAT_SYSCTL'
net.ipv4.ip_forward=1
net.ipv6.conf.all.forwarding=1
CAT_SYSCTL
sysctl --system >/dev/null 2>&1 || true
sysctl -w net.ipv4.ip_forward=1 >/dev/null
modprobe tun 2>/dev/null || true

mkdir -p /etc/openvpn
if [ -f /etc/openvpn/cat-tcp.conf ]; then cp -a /etc/openvpn/cat-tcp.conf /etc/openvpn/cat-tcp.conf.bak.cat; fi
if [ ! -f /etc/openvpn/cat-static.key ]; then
  openvpn --genkey --secret /etc/openvpn/cat-static.key 2>/dev/null || openvpn --genkey secret /etc/openvpn/cat-static.key
fi

cat > /etc/openvpn/cat-tcp.conf << EOF
dev tun
proto tcp-server
port ${OVPN_PORT}
bind
ifconfig 10.89.0.1 10.89.0.2
secret /etc/openvpn/cat-static.key
keepalive 10 60
persist-tun
verb 3
EOF

cat > /etc/systemd/system/cat-openvpn.service << 'CAT_OVPN_UNIT'
[Unit]
After=network-online.target
Wants=network-online.target
[Service]
ExecStart=/usr/sbin/openvpn --config /etc/openvpn/cat-tcp.conf
Restart=on-failure
[Install]
WantedBy=multi-user.target
CAT_OVPN_UNIT
systemctl daemon-reload
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then
  ufw allow "$OVPN_PORT"/tcp || true
fi
systemctl enable --now cat-openvpn

cp -a /etc/openvpn/cat-static.key /root/cat-static.key
cat > /root/cat-ovpn-client.conf << EOF
dev tun
proto tcp-client
remote ${ENDPOINT} ${OVPN_PORT}
ifconfig 10.89.0.2 10.89.0.1
secret cat-static.key
EOF
echo "==== OpenVPN client — Cat cannot import this; use WireGuard in Cat ===="
echo "files: /root/cat-ovpn-client.conf and /root/cat-static.key"
cat /root/cat-ovpn-client.conf
if [ "$ENDPOINT" != "$FOREIGN" ]; then
  echo "Also run iran-dnat.sh on IRAN $IRAN  (PORT=$OVPN_PORT FOREIGN=$FOREIGN)"
fi
