package com.cat.client

/**
 * Cat VPS tunnels the Worker panel cannot host: WireGuard and OpenVPN.
 * Scripts run on the owner's VPS. Cat imports the WireGuard `.conf` the WG script prints.
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
        sb.appendLine("# Cat VPS WireGuard — run on the server that terminates WG (usually FOREIGN).")
        sb.appendLine("# Distro package only. Cat pastes the printed .conf.")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("umask 077")
        sb.appendLine("if ! command -v wg >/dev/null 2>&1; then")
        sb.appendLine("  echo \"WireGuard is not installed. You run:\"")
        sb.appendLine("  echo 'apt-get update && apt-get install -y wireguard'")
        sb.appendLine("  echo \"Then run this script again.\"")
        sb.appendLine("  exit 1")
        sb.appendLine("fi")
        sb.appendLine("mkdir -p /etc/wireguard")
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
        sb.appendLine("sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        sb.appendLine("if command -v systemctl >/dev/null 2>&1 && systemctl enable --now wg-quick@wg0; then")
        sb.appendLine("  echo \"wg-quick@wg0 up\"")
        sb.appendLine("else")
        sb.appendLine("  echo \"Start WireGuard yourself: wg-quick up wg0\"")
        sb.appendLine("fi")
        val ep = endpointForConf(endpoint, listenPort)
        sb.appendLine("echo \"==== paste this in Cat (➕ or share the .conf) ====\"")
        sb.appendLine("cat << EOF")
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
        if (endpoint != server) {
            sb.appendLine("")
            sb.appendLine("# ----- IRAN VPS: UDP/TCP DNAT so the phone dials $ep -----")
            sb.append(iranForward(endpoint, server, listenPort))
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
        sb.appendLine("# Cat VPS OpenVPN TCP — run on the FOREIGN server (terminates OpenVPN).")
        sb.appendLine("# Cat Client has no OpenVPN engine — use the WireGuard script to connect in Cat.")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("umask 077")
        sb.appendLine("if ! command -v openvpn >/dev/null 2>&1; then")
        sb.appendLine("  echo \"OpenVPN is not installed. You run:\"")
        sb.appendLine("  echo 'apt-get update && apt-get install -y openvpn'")
        sb.appendLine("  echo \"Then run this script again.\"")
        sb.appendLine("  exit 1")
        sb.appendLine("fi")
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
        sb.appendLine("sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        sb.appendLine("if command -v systemctl >/dev/null 2>&1 && systemctl restart openvpn@cat-tcp; then")
        sb.appendLine("  echo \"openvpn@cat-tcp restarted\"")
        sb.appendLine("else")
        sb.appendLine("  echo \"Start OpenVPN yourself: openvpn --config /etc/openvpn/cat-tcp.conf\"")
        sb.appendLine("fi")
        val epHost = if (':' in endpoint && '.' !in endpoint) endpoint else endpoint
        sb.appendLine("echo \"==== OpenVPN client — Cat cannot import this; use WireGuard in Cat ====\"")
        sb.appendLine("echo \"# save as cat-static.key:\"")
        sb.appendLine("cat /etc/openvpn/cat-static.key")
        sb.appendLine("echo \"# client.conf:\"")
        sb.appendLine("cat << 'CAT_OVPN'")
        sb.appendLine("dev tun")
        sb.appendLine("proto tcp-client")
        sb.appendLine("remote $epHost $listenPort")
        sb.appendLine("ifconfig 10.89.0.2 10.89.0.1")
        sb.appendLine("secret cat-static.key")
        sb.appendLine("CAT_OVPN")
        if (endpoint != server) {
            sb.appendLine("")
            sb.appendLine("# ----- IRAN VPS: TCP DNAT so the phone dials $epHost:$listenPort -----")
            sb.append(iranForward(endpoint, server, listenPort))
        }
        return sb.toString()
    }

    fun iranForward(iranHost: String, foreignHost: String, port: Int): String {
        val dest = if (':' in foreignHost && '.' !in foreignHost) "[$foreignHost]" else foreignHost
        return buildString {
            appendLine("sysctl -w net.ipv4.ip_forward=1 >/dev/null")
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
