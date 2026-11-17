#!/bin/bash
# Cat VPS — روی سرور ایران اجرا کن (root).
# لینک را بعداً در Cat با address=IRAN بچسبان؛ SNI همان هاست خارج بماند.
set -euo pipefail

IRAN="${IRAN:-CHANGE_ME}"
FOREIGN="${FOREIGN:-CHANGE_ME}"
LISTEN="${LISTEN:-443}"
FOREIGN_PORT="${FOREIGN_PORT:-$LISTEN}"

if [ "$(id -u)" -ne 0 ]; then echo "run as root"; exit 1; fi
if [ "$IRAN" = CHANGE_ME ] || [ "$FOREIGN" = CHANGE_ME ] || [ -z "$IRAN" ] || [ -z "$FOREIGN" ]; then
  echo "Set IRAN and FOREIGN at the top of this file (not 127.0.0.1)."
  exit 1
fi
if [ "$FOREIGN" = "127.0.0.1" ] || [ "$FOREIGN" = localhost ]; then echo "FOREIGN cannot be loopback"; exit 1; fi

export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y iptables curl ca-certificates
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y iptables curl ca-certificates
else
  echo "need apt-get or dnf"; exit 1
fi

cat > /etc/sysctl.d/99-cat-vps.conf << 'CAT_SYSCTL'
net.ipv4.ip_forward=1
net.ipv6.conf.all.forwarding=1
CAT_SYSCTL
sysctl --system >/dev/null 2>&1 || sysctl -p /etc/sysctl.d/99-cat-vps.conf >/dev/null 2>&1 || true
sysctl -w net.ipv4.ip_forward=1 >/dev/null
sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>/dev/null || true

iptables -t nat -N CAT_RELAY 2>/dev/null || iptables -t nat -F CAT_RELAY
iptables -t nat -C PREROUTING -j CAT_RELAY 2>/dev/null || iptables -t nat -A PREROUTING -j CAT_RELAY
iptables -t nat -A CAT_RELAY -p tcp --dport "$LISTEN" -j DNAT --to-destination "${FOREIGN}:${FOREIGN_PORT}"
iptables -t nat -A CAT_RELAY -p udp --dport "$LISTEN" -j DNAT --to-destination "${FOREIGN}:${FOREIGN_PORT}"
iptables -t nat -C POSTROUTING -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -j MASQUERADE
iptables -C FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
iptables -C FORWARD -m conntrack --ctstate DNAT -j ACCEPT 2>/dev/null || iptables -I FORWARD 2 -m conntrack --ctstate DNAT -j ACCEPT

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then
  ufw allow "$LISTEN"/tcp || true
  ufw allow "$LISTEN"/udp || true
fi

mkdir -p /etc/iptables
iptables-save > /etc/iptables/rules.v4
if command -v apt-get >/dev/null 2>&1; then
  echo iptables-persistent iptables-persistent/autosave_v4 boolean true | debconf-set-selections || true
  echo iptables-persistent iptables-persistent/autosave_v6 boolean true | debconf-set-selections || true
  apt-get install -y iptables-persistent || true
  netfilter-persistent save >/dev/null 2>&1 || true
fi
cat > /etc/systemd/system/cat-iptables.service << 'CAT_IPT_UNIT'
[Unit]
After=network-pre.target
Before=network-online.target
[Service]
Type=oneshot
ExecStart=/sbin/iptables-restore /etc/iptables/rules.v4
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
CAT_IPT_UNIT
systemctl daemon-reload
systemctl enable cat-iptables.service >/dev/null

echo "Cat relay ready: $IRAN:$LISTEN  ->  $FOREIGN:$FOREIGN_PORT  (tcp+udp)"
echo "In Cat: address=$IRAN  port=$LISTEN  SNI=foreign host."
