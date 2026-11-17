package com.cat.client

/** Shared bash the VPS scripts actually run (install, persist, start). */
internal object CatVpsSh {
    fun requireRoot(): String = buildString {
        appendLine("if [ \"\$(id -u)\" -ne 0 ]; then echo \"run as root\"; exit 1; fi")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
    }

    fun aptInstall(debian: String, fedora: String): String = buildString {
        appendLine("if command -v apt-get >/dev/null 2>&1; then")
        appendLine("  apt-get update -y")
        appendLine("  apt-get install -y $debian")
        appendLine("elif command -v dnf >/dev/null 2>&1; then")
        appendLine("  dnf install -y $fedora")
        appendLine("else")
        appendLine("  echo \"need apt-get or dnf\"")
        appendLine("  exit 1")
        appendLine("fi")
    }

    fun persistForward(): String = buildString {
        appendLine("cat > /etc/sysctl.d/99-cat-vps.conf << 'CAT_SYSCTL'")
        appendLine("net.ipv4.ip_forward=1")
        appendLine("net.ipv6.conf.all.forwarding=1")
        appendLine("CAT_SYSCTL")
        appendLine("sysctl --system >/dev/null 2>&1 || sysctl -p /etc/sysctl.d/99-cat-vps.conf >/dev/null 2>&1 || true")
        appendLine("sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        appendLine("sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>/dev/null || true")
    }

    fun persistIptables(): String = buildString {
        appendLine("mkdir -p /etc/iptables")
        appendLine("iptables-save > /etc/iptables/rules.v4")
        appendLine("if command -v apt-get >/dev/null 2>&1; then")
        appendLine("  echo iptables-persistent iptables-persistent/autosave_v4 boolean true | debconf-set-selections || true")
        appendLine("  echo iptables-persistent iptables-persistent/autosave_v6 boolean true | debconf-set-selections || true")
        appendLine("  apt-get install -y iptables-persistent || true")
        appendLine("  netfilter-persistent save >/dev/null 2>&1 || true")
        appendLine("fi")
        appendLine("cat > /etc/systemd/system/cat-iptables.service << 'CAT_IPT_UNIT'")
        appendLine("[Unit]")
        appendLine("After=network-pre.target")
        appendLine("Before=network-online.target")
        appendLine("[Service]")
        appendLine("Type=oneshot")
        appendLine("ExecStart=/sbin/iptables-restore /etc/iptables/rules.v4")
        appendLine("RemainAfterExit=yes")
        appendLine("[Install]")
        appendLine("WantedBy=multi-user.target")
        appendLine("CAT_IPT_UNIT")
        appendLine("systemctl daemon-reload")
        appendLine("systemctl enable cat-iptables.service >/dev/null")
    }

    fun dnatForwardAccept(): String = buildString {
        appendLine("iptables -C FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT")
        appendLine("iptables -C FORWARD -m conntrack --ctstate DNAT -j ACCEPT 2>/dev/null || iptables -I FORWARD 2 -m conntrack --ctstate DNAT -j ACCEPT")
    }

    fun ufwAllow(port: Int, proto: String): String =
        "if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then ufw allow $port/$proto || true; fi\n"
}
