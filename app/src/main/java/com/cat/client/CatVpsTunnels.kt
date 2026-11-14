package com.cat.client

/**
 * Cat VPS tunnels: WireGuard and OpenVPN on the owner's VPS.
 * Cat imports the WireGuard `.conf` the WG script prints.
 * OpenVPN is TCP on the VPS only — Cat has no OpenVPN engine.
 */
internal object CatVpsTunnels {
    const val WG_PORT = 51820
    const val OVPN_PORT = 1194

    fun wireGuardBundle(iranHint: String, foreignHost: String, listenPort: Int = WG_PORT): String? {
        val server = foreignHost.trim().removePrefix("[").removeSuffix("]")
        if (!CatVpsRelay.isHost(server) || listenPort !in 1..65535) return null
        if (listenPort == 80 || listenPort == 443 || listenPort == 1080) return null
        val iran = iranHint.trim().removePrefix("[").removeSuffix("]")
        val endpoint = if (CatVpsRelay.isHost(iran)) iran else server
        val sb = StringBuilder()
        sb.appendLine("#!/bin/bash")
        sb.appendLine("# Cat VPS WireGuard — run on the FOREIGN server (terminates WG).")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("umask 077")
        sb.append(CatVpsSh.requireRoot())
        sb.append(CatVpsSh.aptInstall("wireguard iptables curl ca-certificates", "wireguard-tools iptables curl ca-certificates"))
        sb.append(CatVpsSh.persistForward())
        sb.appendLine("modprobe wireguard 2>/dev/null || true")
        sb.appendLine("mkdir -p /etc/wireguard")
        sb.appendLine("wg-quick down wg0 >/dev/null 2>&1 || true")
        sb.appendLine("if [ -f /etc/wireguard/wg0.conf ]; then cp -a /etc/wireguard/wg0.conf /etc/wireguard/wg0.conf.bak.cat; fi")
        sb.appendLine("SERVER_PRIV=\$(wg genkey)")
        sb.appendLine("SERVER_PUB=\$(printf '%s\\n' \"\$SERVER_PRIV\" | wg pubkey)")
        sb.appendLine("CLIENT_PRIV=\$(wg genkey)")
        sb.appendLine("CLIENT_PUB=\$(printf '%s\\n' \"\$CLIENT_PRIV\" | wg pubkey)")
        sb.appendLine("PSK=\$(wg genpsk)")
        sb.appendLine("WAN=\$(ip -4 route show default 2>/dev/null | awk '{print \$5; exit}' || true)")
        sb.appendLine("WAN=\${WAN:-eth0}")
        sb.appendLine("cat > /etc/wireguard/wg0.conf << EOF")
        sb.appendLine("[Interface]")
        sb.appendLine("Address = 10.77.0.1/24")
        sb.appendLine("ListenPort = $listenPort")
        sb.appendLine("PrivateKey = \$SERVER_PRIV")
        sb.appendLine("PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o \$WAN -j MASQUERADE")
        sb.appendLine("PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o \$WAN -j MASQUERADE")
        sb.appendLine("")
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = \$CLIENT_PUB")
        sb.appendLine("PresharedKey = \$PSK")
        sb.appendLine("AllowedIPs = 10.77.0.2/32")
        sb.appendLine("EOF")
        sb.appendLine("chmod 600 /etc/wireguard/wg0.conf")
        sb.append(CatVpsSh.ufwAllow(listenPort, "udp"))
        sb.append(CatVpsSh.ufwAllow(listenPort, "tcp"))
        sb.appendLine("wg-quick up wg0")
        sb.appendLine("systemctl enable wg-quick@wg0 >/dev/null 2>&1 || true")
        val ep = endpointForConf(endpoint, listenPort)
        sb.appendLine("cat > /root/cat-wg-phone.conf << EOF")
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = \$CLIENT_PRIV")
        sb.appendLine("Address = 10.77.0.2/32")
        sb.appendLine("DNS = 1.1.1.1")
        sb.appendLine("MTU = 1280")
        sb.appendLine("")
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = \$SERVER_PUB")
        sb.appendLine("PresharedKey = \$PSK")
        sb.appendLine("Endpoint = $ep")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("PersistentKeepalive = 25")
        sb.appendLine("EOF")
        sb.appendLine("chmod 600 /root/cat-wg-phone.conf")
        sb.appendLine("echo \"==== paste /root/cat-wg-phone.conf in Cat ====\"")
        sb.appendLine("cat /root/cat-wg-phone.conf")
        if (endpoint != server) {
            sb.appendLine("cat > /root/cat-iran-dnat.sh << 'CAT_IRAN'")
            sb.appendLine("#!/bin/bash")
            sb.appendLine("set -euo pipefail")
            sb.append(CatVpsSh.requireRoot())
            sb.append(CatVpsSh.aptInstall("iptables", "iptables"))
            sb.append(CatVpsSh.persistForward())
            sb.append(iranForward(endpoint, server, listenPort))
            sb.append(CatVpsSh.dnatForwardAccept())
            sb.append(CatVpsSh.ufwAllow(listenPort, "udp"))
            sb.append(CatVpsSh.ufwAllow(listenPort, "tcp"))
            sb.append(CatVpsSh.persistIptables())
            sb.appendLine("echo \"Iran DNAT ready $endpoint:$listenPort -> $server:$listenPort\"")
            sb.appendLine("CAT_IRAN")
            sb.appendLine("chmod +x /root/cat-iran-dnat.sh")
            sb.appendLine("echo \"Copy /root/cat-iran-dnat.sh to IRAN $endpoint and run: bash cat-iran-dnat.sh\"")
        }
        return sb.toString()
    }

    fun openVpnTcpBundle(iranHint: String, foreignHost: String, listenPort: Int = OVPN_PORT): String? {
        val server = foreignHost.trim().removePrefix("[").removeSuffix("]")
        if (!CatVpsRelay.isHost(server) || listenPort !in 1..65535) return null
        if (listenPort == 80 || listenPort == 443 || listenPort == 1080) return null
        val iran = iranHint.trim().removePrefix("[").removeSuffix("]")
        val endpoint = if (CatVpsRelay.isHost(iran)) iran else server
        val sb = StringBuilder()
        sb.appendLine("#!/bin/bash")
        sb.appendLine("# Cat VPS OpenVPN TCP — run on the FOREIGN server.")
        sb.appendLine("# Cat Client has no OpenVPN engine — use the WireGuard script to connect in Cat.")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("umask 077")
        sb.append(CatVpsSh.requireRoot())
        sb.append(CatVpsSh.aptInstall("openvpn iptables", "openvpn iptables"))
        sb.append(CatVpsSh.persistForward())
        sb.appendLine("modprobe tun 2>/dev/null || true")
        sb.appendLine("mkdir -p /etc/openvpn")
        sb.appendLine("if [ -f /etc/openvpn/cat-tcp.conf ]; then cp -a /etc/openvpn/cat-tcp.conf /etc/openvpn/cat-tcp.conf.bak.cat; fi")
        sb.appendLine("if [ ! -f /etc/openvpn/cat-static.key ]; then")
        sb.appendLine("  openvpn --genkey --secret /etc/openvpn/cat-static.key 2>/dev/null || openvpn --genkey secret /etc/openvpn/cat-static.key")
        sb.appendLine("fi")
        sb.appendLine("cat > /etc/openvpn/cat-tcp.conf << EOF")
        sb.appendLine("dev tun")
        sb.appendLine("proto tcp-server")
        sb.appendLine("port $listenPort")
        sb.appendLine("bind")
        sb.appendLine("ifconfig 10.89.0.1 10.89.0.2")
        sb.appendLine("secret /etc/openvpn/cat-static.key")
        sb.appendLine("keepalive 10 60")
        sb.appendLine("persist-tun")
        sb.appendLine("verb 3")
        sb.appendLine("EOF")
        sb.appendLine("cat > /etc/systemd/system/cat-openvpn.service << 'CAT_OVPN_UNIT'")
        sb.appendLine("[Unit]")
        sb.appendLine("After=network-online.target")
        sb.appendLine("Wants=network-online.target")
        sb.appendLine("[Service]")
        sb.appendLine("ExecStart=/usr/sbin/openvpn --config /etc/openvpn/cat-tcp.conf")
        sb.appendLine("Restart=on-failure")
        sb.appendLine("[Install]")
        sb.appendLine("WantedBy=multi-user.target")
        sb.appendLine("CAT_OVPN_UNIT")
        sb.appendLine("systemctl daemon-reload")
        sb.append(CatVpsSh.ufwAllow(listenPort, "tcp"))
        sb.appendLine("systemctl enable --now cat-openvpn")
        val epHost = if (':' in endpoint && '.' !in endpoint) endpoint else endpoint
        sb.appendLine("cp -a /etc/openvpn/cat-static.key /root/cat-static.key")
        sb.appendLine("cat > /root/cat-ovpn-client.conf << 'CAT_OVPN'")
        sb.appendLine("dev tun")
        sb.appendLine("proto tcp-client")
        sb.appendLine("remote $epHost $listenPort")
        sb.appendLine("ifconfig 10.89.0.2 10.89.0.1")
        sb.appendLine("secret cat-static.key")
        sb.appendLine("CAT_OVPN")
        sb.appendLine("echo \"==== OpenVPN client — Cat cannot import this; use WireGuard in Cat ====\"")
        sb.appendLine("echo \"files: /root/cat-ovpn-client.conf and /root/cat-static.key\"")
        sb.appendLine("cat /root/cat-ovpn-client.conf")
        if (endpoint != server) {
            sb.appendLine("cat > /root/cat-iran-dnat.sh << 'CAT_IRAN'")
            sb.appendLine("#!/bin/bash")
            sb.appendLine("set -euo pipefail")
            sb.append(CatVpsSh.requireRoot())
            sb.append(CatVpsSh.aptInstall("iptables", "iptables"))
            sb.append(CatVpsSh.persistForward())
            sb.append(iranForward(endpoint, server, listenPort))
            sb.append(CatVpsSh.dnatForwardAccept())
            sb.append(CatVpsSh.ufwAllow(listenPort, "tcp"))
            sb.append(CatVpsSh.persistIptables())
            sb.appendLine("echo \"Iran DNAT ready $endpoint:$listenPort -> $server:$listenPort\"")
            sb.appendLine("CAT_IRAN")
            sb.appendLine("chmod +x /root/cat-iran-dnat.sh")
            sb.appendLine("echo \"Copy /root/cat-iran-dnat.sh to IRAN $endpoint and run: bash cat-iran-dnat.sh\"")
        }
        return sb.toString()
    }

    fun iranForward(iranHost: String, foreignHost: String, port: Int): String {
        val dest = if (':' in foreignHost && '.' !in foreignHost) "[$foreignHost]" else foreignHost
        return buildString {
            appendLine("iptables -t nat -N CAT_RELAY 2>/dev/null || true")
            appendLine("iptables -t nat -C PREROUTING -j CAT_RELAY 2>/dev/null || iptables -t nat -A PREROUTING -j CAT_RELAY")
            appendLine("iptables -t nat -A CAT_RELAY -p tcp --dport $port -j DNAT --to-destination ${dest}:$port")
            appendLine("iptables -t nat -A CAT_RELAY -p udp --dport $port -j DNAT --to-destination ${dest}:$port")
            appendLine("iptables -t nat -C POSTROUTING -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -j MASQUERADE")
            appendLine("echo \"Iran $iranHost:$port  ->  $foreignHost:$port  (tcp+udp)\"")
        }
    }

    private fun endpointForConf(host: String, port: Int): String =
        if (':' in host && '.' !in host) "[$host]:$port" else "$host:$port"
}
