#!/bin/bash
# Cat VPS Xray — روی سرور ایران اجرا کن (root). با iran-relay.sh روی یک پورت هم‌زمان نگذار.
set -euo pipefail
umask 077

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
  apt-get install -y curl ca-certificates unzip
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y curl ca-certificates unzip
else
  echo "need apt-get or dnf"; exit 1
fi

if ! command -v xray >/dev/null 2>&1; then
  bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
fi
command -v xray >/dev/null 2>&1 || { echo "xray install failed"; exit 1; }

mkdir -p /usr/local/etc/xray
if [ -f /usr/local/etc/xray/config.json ]; then cp -a /usr/local/etc/xray/config.json /usr/local/etc/xray/config.json.bak.cat; fi
cat > /usr/local/etc/xray/config.json << EOF
{"log":{"loglevel":"warning"},"inbounds":[{"tag":"in-exit","listen":"0.0.0.0","port":${LISTEN},"protocol":"dokodemo-door","settings":{"address":"${FOREIGN}","port":${FOREIGN_PORT},"network":"tcp,udp","followRedirect":false}}],"outbounds":[{"tag":"out-exit","protocol":"freedom","settings":{}},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","inboundTag":["in-exit"],"outboundTag":"out-exit"}]}}
EOF

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then
  ufw allow "$LISTEN"/tcp || true
  ufw allow "$LISTEN"/udp || true
fi

systemctl enable xray >/dev/null 2>&1 || true
if systemctl restart xray; then
  echo "xray running"
else
  xray run -c /usr/local/etc/xray/config.json >/var/log/cat-xray.log 2>&1 &
  echo "xray started in background"
fi
echo "in-exit: $IRAN:$LISTEN  ->  out-exit $FOREIGN:$FOREIGN_PORT"
echo "Xray ready. In Cat: address=$IRAN  port=$LISTEN  SNI=foreign host."
