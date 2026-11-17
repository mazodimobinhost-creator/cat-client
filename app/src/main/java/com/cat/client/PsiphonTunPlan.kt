package com.cat.client

/** Final local leg: Android TUN → Mihomo → isolated Psiphon or Tor SOCKS. */
internal object PsiphonTunPlan {
    fun yaml(socksPort: Int): String {
        require(socksPort in 1024..65535)
        return """
            proxies:
              - name: 'CAT-PSIPHON-EXIT'
                type: socks5
                server: 127.0.0.1
                port: $socksPort
                udp: true
            proxy-groups:
              - name: 'CAT-AETHER-PSIPHON'
                type: select
                proxies:
                  - 'CAT-PSIPHON-EXIT'
            rules:
              - MATCH,CAT-AETHER-PSIPHON
        """.trimIndent() + "\n"
    }

    /**
     * WARP -> Tor (Orbot) sanction-bypass chain:
     * Simple WARP carrier (WireGuard or MASQUE-HTTP/2) listens on 127.0.0.1:[aetherSocksPort]
     * and chains into Tor/Orbot SOCKS5 on 127.0.0.1:[torSocksPort].
     */
    fun torChainYaml(
        aetherSocksPort: Int = 1819,
        torSocksPort: Int = OrbotEnginePolicy.DEFAULT_PORT,
        torReachable: Boolean = true,
    ): String {
        require(aetherSocksPort in 1024..65535)
        require(torSocksPort in 1024..65535)
        return if (torReachable) {
            """
                proxies:
                  - name: 'CAT-WARP-CARRIER'
                    type: socks5
                    server: 127.0.0.1
                    port: $aetherSocksPort
                    udp: true
                  - name: 'CAT-TOR-EXIT'
                    type: socks5
                    server: 127.0.0.1
                    port: $torSocksPort
                    udp: false
                    dialer-proxy: 'CAT-WARP-CARRIER'
                proxy-groups:
                  - name: 'CAT-AETHER-TOR'
                    type: select
                    proxies:
                      - 'CAT-TOR-EXIT'
                      - 'CAT-WARP-CARRIER'
                rules:
                  - MATCH,CAT-AETHER-TOR
            """.trimIndent() + "\n"
        } else {
            """
                proxies:
                  - name: 'CAT-WARP-CARRIER'
                    type: socks5
                    server: 127.0.0.1
                    port: $aetherSocksPort
                    udp: true
                proxy-groups:
                  - name: 'CAT-AETHER-TOR'
                    type: select
                    proxies:
                      - 'CAT-WARP-CARRIER'
                rules:
                  - MATCH,CAT-AETHER-TOR
            """.trimIndent() + "\n"
        }
    }
}
