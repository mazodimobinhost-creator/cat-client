package com.cat.client

/** Final local leg: Android TUN → Mihomo → isolated Psiphon SOCKS. */
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
}
