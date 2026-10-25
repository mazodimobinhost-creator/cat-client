package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteDnsSmartScannerTest {
    @Test fun cloudflarePortSniHitPreservesExactVerifiedPortForIpv4AndIpv6() {
        val v4Hit = CloudflarePortSniHit("188.114.98.0", 2053, "speed.cloudflare.com", 62L)
        assertEquals("188.114.98.0:2053", v4Hit.endpoint)

        val v6Hit = CloudflarePortSniHit("[2a06:98c1:3101::6812:202f]", 8443, "www.cloudflare.com", 75L)
        assertEquals("[2a06:98c1:3101::6812:202f]:8443", v6Hit.endpoint)

        assertEquals(listOf(443, 2053, 2083, 2087, 2096, 8443), WhiteDnsSmartScanner.CLOUDFLARE_TLS_PORTS)
    }

    @Test fun dnsPacketAndResponseValidationWorkCorrectly() {
        val pkt = WhiteDnsSmartScanner.buildDnsQueryPacket(0x4341)
        assertTrue(pkt.size > 12)
        // Query has QR=0 so it is not a response
        assertFalse(WhiteDnsSmartScanner.isValidDnsResponse(pkt, pkt.size, 0x4341))

        val validResp = pkt.copyOf().also { it[2] = 0x81.toByte() }
        assertTrue(WhiteDnsSmartScanner.isValidDnsResponse(validResp, validResp.size, 0x4341))
        assertFalse(WhiteDnsSmartScanner.isValidDnsResponse(validResp, validResp.size, 0x1234))
    }

    @Test fun dnsttBridgeBuilderUsesVerifiedIranResolver() {
        val lines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("185.212.51.144")
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains("udp=185.212.51.144:53") })

        val dotLines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("178.22.122.100", "dot")
        assertTrue(dotLines.all { it.contains("dot=178.22.122.100:853") })

        val dohLines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("178.22.122.100", "doh")
        assertTrue(dohLines.all { it.contains("doh=https://178.22.122.100/dns-query") })
    }

    @Test fun whiteDnsTunnelBundleAndCatalogSupportAllTransportsRecordsAndIranPresets() {
        assertTrue(WhiteDnsSmartScanner.RESOLVER_CATALOG.size >= 16)
        assertEquals(6, WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.size)

        val tcpFrame = WhiteDnsSmartScanner.buildTcpDnsQueryFrame(0x4341, qtype = 65)
        val udpPkt = WhiteDnsSmartScanner.buildDnsQueryPacket(0x4341, qtype = 65)
        assertEquals(udpPkt.size + 2, tcpFrame.size)
        assertEquals(udpPkt.size, ((tcpFrame[0].toInt() and 0xff) shl 8) or (tcpFrame[1].toInt() and 0xff))

        val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = "178.22.122.100",
            transport = "auto",
            recordMode = WhiteDnsRecordMode.TXT_HTTPS,
            preset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first(),
        )
        assertTrue(bundle.stormDnsUri.startsWith("stormdns://178.22.122.100:53/"))
        assertTrue(bundle.stormDnsUri.contains("delivery=txt-https"))
        assertTrue(bundle.stormDnsUri.contains("preset=iran-fast-low-mtu"))
        assertTrue(bundle.cottenMaxUri.startsWith("cottendns://178.22.122.100:53/?mode=cottenmax"))
        assertTrue(bundle.cottenMaxUri.contains("fec=4:1"))
        assertTrue(bundle.soarUri.startsWith("soar://178.22.122.100:53/?profile=IR"))
    }

    @Test fun socks5HubBuiltInSeedsAndCountryParserPreserveExplicitPortsAndCountryCodes() {
        assertTrue(ProxyScannerPage.BUILTIN_SOCKS5_SEEDS.size >= 50)
        assertTrue(ProxyScannerPage.BUILTIN_SOCKS5_SEEDS.all { ':' in it })
        assertEquals("US", ProxyScannerPage.countryCodeFromNameOrIso("United States"))
        assertEquals("DE", ProxyScannerPage.countryCodeFromNameOrIso("Germany"))
        assertEquals("NL", ProxyScannerPage.countryCodeFromNameOrIso("Netherlands"))
        assertEquals("SG", ProxyScannerPage.countryCodeFromNameOrIso("Singapore"))
        assertEquals("FR", ProxyScannerPage.countryCodeFromNameOrIso("fr"))
    }

    @Test fun whiteDnsTunnelUrisImportIntoMihomoAndRoundTripViaShareLink() {
        val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = "178.22.122.100",
            transport = "auto",
            recordMode = WhiteDnsRecordMode.TXT_HTTPS,
            preset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first(),
        )
        val lines = listOf(bundle.cottenMaxUri, bundle.stormDnsUri, bundle.soarUri).joinToString("\n")
        val imported = UserSubscriptionImporter.import(lines, nowMs = 1_000L)
        assertEquals(3, imported.connectionCount)
        val parsed = MihomoConfigParser.parse(imported.yaml, 1_000L)
        assertEquals(3, parsed.summary.proxies.size)
        val rebuilt = MihomoShareLink.fromYamlBlock(parsed.summary.proxies[0].rawYaml)
        assertEquals(bundle.cottenMaxUri, rebuilt)
    }

    @Test fun tlsClientHelloSplitterAndIrancellFfParametersWorkEndToEnd() {
        // Fake 40-byte TLS 1.2 ClientHello record: 0x16 0x03 0x03 0x00 0x23 (len=35), handshake type 0x01
        val payload = ByteArray(35) { idx -> if (idx == 0) 0x01 else (idx + 1).toByte() }
        val record = byteArrayOf(0x16, 0x03, 0x03, 0x00, 35) + payload
        val split = WhiteDnsSmartScanner.splitTlsClientHelloRecord(record, splitPayloadBytes = 16)!!
        assertEquals(0x16, split.first[0].toInt() and 0xFF)
        assertEquals(16, ((split.first[3].toInt() and 0xFF) shl 8) or (split.first[4].toInt() and 0xFF))
        assertEquals(0x16, split.second[0].toInt() and 0xFF)
        assertEquals(19, ((split.second[3].toInt() and 0xFF) shl 8) or (split.second[4].toInt() and 0xFF))

        val ffLink =
            "vless://uuid@188.114.98.0:443?encryption=none&security=tls&type=ws&host=worker.example" +
                "&path=%2Fvl&sni=worker.example&fp=unsafe&alpn=http%2F1.1&cs=semi-python&fm=tlshello-0-len#MTN-FF"
        val imported = UserSubscriptionImporter.import(ffLink, nowMs = 1_000L)
        val rawBlock = MihomoConfigParser.parse(imported.yaml, 1_000L).summary.proxies.single().rawYaml
        val rebuilt = MihomoShareLink.fromYamlBlock(rawBlock)!!
        assertTrue(rebuilt.contains("cs=semi-python"))
        assertTrue(rebuilt.contains("fm=tlshello-0-len"))
    }

    @Test fun subscriptionSourceLoaderBuildsFallbackDomainCandidateUrls() {
        val urls = SubscriptionSourceLoader.candidateHttpsUrls(
            "https://primary.workers.dev/sub/abc?fallback_host=mirror1.example.com,mirror2.example.com",
        )
        assertEquals(3, urls.size)
        assertEquals("primary.workers.dev", urls[0].host)
        assertEquals("mirror1.example.com", urls[1].host)
        assertEquals("mirror2.example.com", urls[2].host)
        assertEquals("/sub/abc", urls[1].rawPath)
    }

    @Test fun midStreamFreezeEvaluatorDetectsHealthyAndStalledTransfers() {
        val healthy = WhiteDnsSmartScanner.evaluateMidStreamTransfer(
            ttfbMs = 180L,
            bytesRead = 32_768,
            targetBytes = 32_768,
            elapsedMs = 450L,
        )
        assertTrue(healthy.ok)
        assertTrue(!healthy.stalledMidStream)
        assertTrue(healthy.throughputKbps > 0)

        val stalled = WhiteDnsSmartScanner.evaluateMidStreamTransfer(
            ttfbMs = 210L,
            bytesRead = 1024,
            targetBytes = 32_768,
            elapsedMs = 3500L,
        )
        assertTrue(!stalled.ok)
        assertTrue(stalled.stalledMidStream)
        assertTrue(stalled.summaryFa.contains("نیمه‌کاره"))
    }

    @Test fun poisonedIranDnsFilterAndGasRelayAndLiteWorkerBuilders() {
        assertTrue(WhiteDnsSmartScanner.isPoisonedIranDnsIp("10.10.34.35"))
        assertTrue(WhiteDnsSmartScanner.isPoisonedIranDnsIp("127.0.0.1"))
        assertTrue(!WhiteDnsSmartScanner.isPoisonedIranDnsIp("188.114.98.0"))

        val gasUri = WhiteDnsSmartScanner.buildGasRelayUri(
            "https://script.google.com/macros/s/AKfycb123/exec",
            "MyGAS",
        )
        val parsedGas = WhiteDnsSmartScanner.parseTunnelUri(gasUri)
        assertTrue(parsedGas != null)
        assertEquals(WhiteDnsSmartScanner.WhiteDnsTunnelEngine.GOOGLE_SCRIPT_RELAY, parsedGas!!.engine)
        assertTrue(parsedGas.scriptUrl.startsWith("https://script.google.com/macros/s/AKfycb123/exec"))
        val convertedGas = SubConvConverter.convert(gasUri)
        assertEquals(1, convertedGas.size)
        assertEquals("socks5", convertedGas[0].optString("type"))

        val gasCode = WhiteDnsSmartScanner.buildGoogleAppsScriptRelaySource()
        assertTrue(gasCode.contains("UrlFetchApp.fetch"))
        assertTrue(gasCode.contains("DAILY_LIMIT = 20000"))

        val liteWorker = WhiteDnsSmartScanner.buildLiteWorkerScript(
            uuid = "11111111-2222-4333-8444-555555555555",
            proxyIp = "103.237.102.191:11111",
            fallbackHost = "backup.example.com",
        )
        assertTrue(liteWorker.contains("103.237.102.191:11111"))
        assertTrue(liteWorker.contains("tlshello-0-len"))
        assertTrue(liteWorker.contains("semi-python"))
        assertTrue(liteWorker.contains("2a06:98c1:3101::6812:202f"))
    }

    @Test fun moatBridgesJsonParserExtractsBridgeStrings() {
        val sampleJson = """
            {"settings":[
              {"bridges":{"type":"obfs4","bridge_strings":["obfs4 1.2.3.4:443 ABC cert=XYZ iat-mode=0","obfs4 5.6.7.8:8443 DEF cert=UVW iat-mode=1"]}}
            ]}
        """.trimIndent()
        val bridges = AetherOperations.parseMoatBridgesJson(sampleJson)
        assertEquals(2, bridges.size)
        assertTrue(bridges.first().startsWith("obfs4 1.2.3.4:443"))
    }

    @Test fun proguardRulesKeepAetherJniCallbacksAgainstR8Stripping() {
        val candidates = listOf(
            java.io.File("proguard-rules.pro"),
            java.io.File("app/proguard-rules.pro"),
        )
        val rulesFile = candidates.firstOrNull { it.isFile } ?: error("proguard-rules.pro not found")
        val rules = rulesFile.readText()
        assertTrue(rules.contains("com.whitedns.whiteaesther.core.NativeSocketProtector"))
        assertTrue(rules.contains("protectSocket(int)"))
        assertTrue(rules.contains("com.whitedns.whiteaesther.core.NativeEngineListener"))
        assertTrue(rules.contains("onNativeReady()"))
    }

    @Test fun doubleFragmentAndTlsInterceptionQuarantineWorkEndToEnd() {
        val payload = ByteArray(160) { i -> if (i == 0) 0x01 else (i and 0x7F).toByte() }
        val chPacket = ByteArray(5 + payload.size).apply {
            this[0] = 0x16
            this[1] = 0x03
            this[2] = 0x03
            this[3] = ((payload.size ushr 8) and 0xFF).toByte()
            this[4] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, this, 5, payload.size)
        }
        val segments = WhiteDnsSmartScanner.buildDoubleFragmentSegments(
            packet = chPacket,
            firstTlsPayloadBytes = 104,
            firstTcpSegmentBytes = 114,
            maxTailSegments = 5,
            interSegmentDelayMs = 15L,
        )
        assertTrue(segments.size >= 6)
        assertEquals(114, segments.first().first.size)
        assertEquals(0L, segments.first().second)
        assertEquals(1, segments[1].first.size)
        assertEquals(15L, segments[1].second)

        val now = 1_760_000_000_000L
        WhiteDnsSmartScanner.quarantineInterceptedEndpoint("net-mci", "104.18.2.161:443", now)
        assertTrue(WhiteDnsSmartScanner.isEndpointQuarantined("net-mci", "104.18.2.161:443", now + 60_000L))
        val filtered = WhiteDnsSmartScanner.filterOutQuarantinedEndpoints(
            "net-mci",
            listOf("104.18.2.161:443", "188.114.98.0:443"),
            now + 10_000L,
        )
        assertEquals(listOf("188.114.98.0:443"), filtered)
        assertTrue(!WhiteDnsSmartScanner.isEndpointQuarantined("net-mci", "104.18.2.161:443", now + 25 * 3600_000L))
    }

    @Test fun masqueAndWireGuardAutoSchemesParseAndRealityFragmentNormalizes() {
        val masqueSel = AetherSelection.fromAetherUri("masque://auto@162.159.198.1:443/?ip=v6#Mahsa-MASQUE")
        assertTrue(masqueSel != null)
        assertEquals(AetherMode.MasqueHttp2, masqueSel?.mode)
        assertEquals("162.159.198.1:443", masqueSel?.endpoint)
        assertEquals("v6", masqueSel?.ipScan)

        val wgAutoSel = AetherSelection.fromAetherUri("wireguard://auto@162.159.192.1:2408/?ip=both#Mahsa-WG-Auto")
        assertTrue(wgAutoSel != null)
        assertEquals(AetherMode.WireGuard, wgAutoSel?.mode)
        assertEquals("162.159.192.1:2408", wgAutoSel?.endpoint)

        val parsedMasqueTunnel = WhiteDnsSmartScanner.parseTunnelUri("masque://162.159.198.1:443/#MASQUE-Node")
        assertTrue(parsedMasqueTunnel != null)
        assertEquals(WhiteDnsSmartScanner.WhiteDnsTunnelEngine.MASQUE_ENDPOINT, parsedMasqueTunnel?.engine)

        assertEquals("1-3", WhiteDnsSmartScanner.normalizeRealityFragmentPackets("reality", "tlshello"))
        assertEquals("1-3-0-len", WhiteDnsSmartScanner.normalizeRealityFragmentPackets("REALITY", "tlshello-0-len"))
        assertEquals("tlshello", WhiteDnsSmartScanner.normalizeRealityFragmentPackets("tls", "tlshello"))
        assertEquals(9, WhiteDnsSmartScanner.ANTI_FILTER_9_PRESETS.size)
    }

    @Test fun aiUnblockNetMelliAndSmartUnspoofedDirectBypassEvaluatorsAreAccurate() {
        val geminiBlocked = WhiteDnsSmartScanner.evaluateAiServiceProbe("Gemini", 302, "https://gemini.google.com/faq", 120L)
        assertTrue(!geminiBlocked.unlocked)
        val geminiOpen = WhiteDnsSmartScanner.evaluateAiServiceProbe("Gemini", 200, "ok", 95L)
        assertTrue(geminiOpen.unlocked)

        val netMelli = WhiteDnsSmartScanner.evaluateNetMelliState(
            domesticHits = 3,
            internationalHits = 0,
            dnsTunnelReachable = true,
            fastestDomesticMs = 24L,
            fastestIntlMs = null,
        )
        assertEquals(WhiteDnsSmartScanner.NetworkIsolationState.NET_MELLI_SHUTDOWN, netMelli.state)

        assertTrue(WhiteDnsSmartScanner.shouldBypassTunnelForUnspoofedDomain("digikala.com", listOf("185.143.233.5")))
        assertTrue(!WhiteDnsSmartScanner.shouldBypassTunnelForUnspoofedDomain("youtube.com", listOf("10.10.34.35")))
        assertTrue(!WhiteDnsSmartScanner.shouldBypassTunnelForUnspoofedDomain("gemini.google.com", listOf("142.250.185.78")))
    }

    @Test fun iosConnectionBundleMobileConfigPacAndCottenMaxBudgetAreValid() {
        val bundle = WhiteDnsSmartScanner.buildIosConnectionBundle(
            workerHost = "my-panel.workers.dev",
            uuid = "11111111-2222-4333-8444-555555555555",
            cleanIp = "188.114.98.0",
            port = 443,
            path = "/vless-ws",
            remark = "CatClient-iOS",
        )
        assertEquals("https://testflight.apple.com/join/DRkT6zny", bundle.coreForgeTestFlightUrl)
        assertTrue(bundle.vlessFragmentUri.startsWith("vless://11111111-2222-4333-8444-555555555555@188.114.98.0:443?"))
        assertTrue(bundle.vlessFragmentUri.contains("fragment=10-30,10-20,tlshello"))
        assertTrue(bundle.streisandDeepLink.startsWith("streisand://import/"))
        assertTrue(bundle.v2boxDeepLink.startsWith("v2box://install-sub?url="))
        assertTrue(bundle.singBoxIosDeepLink.startsWith("sing-box://import-remote-profile?url="))
        assertTrue(bundle.shadowrocketDeepLink.startsWith("sub://"))
        assertTrue(bundle.karingDeepLink.startsWith("karing://install-config?url="))
        assertTrue(bundle.wireguardIosConf.contains("MTU = 1280"))
        assertTrue(bundle.mobileConfigXml.contains("com.apple.dnsSettings.managed"))
        assertTrue(bundle.mobileConfigXml.contains("https://cloudflare-dns.com/dns-query"))

        val pac = WhiteDnsSmartScanner.buildLanPacScriptForIos("192.168.43.1", 10809, 10808)
        assertTrue(pac.contains("SOCKS5 192.168.43.1:10808"))
        assertTrue(pac.contains("shExpMatch(host, \"*.ir\")"))

        val shortBudget = WhiteDnsSmartScanner.calculateCottenMaxQnameBudget(listOf("t.abc.ir"))
        val longBudget = WhiteDnsSmartScanner.calculateCottenMaxQnameBudget(
            listOf("very-long-panel-generated-tunnel-subdomain.example-domain.co.uk"),
            shortenedLabels = true,
            labelCountJitter = 1,
        )
        assertTrue(shortBudget > longBudget)
        assertTrue(longBudget >= 32)
    }

    @Test fun dnstSpecSlipNetMasterDnsVayDnsAndSixPointScoreWorkEndToEnd() {
        val dnstHuman = WhiteDnsSmartScanner.buildDnstStandardUri(
            domain = "v.example.com",
            transport = "vaydns",
            backend = "socks",
            pubKeyHex = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f",
            recordType = "cname",
            resolverIp = "178.22.122.100",
            tag = "VayDNS-CNAME",
            asBase64Json = false,
        )
        assertTrue(dnstHuman.startsWith("dnst://v.example.com/vaydns/socks?"))
        val parsedHuman = WhiteDnsSmartScanner.parseTunnelUri(dnstHuman)
        assertTrue(parsedHuman != null)
        assertEquals(WhiteDnsSmartScanner.WhiteDnsTunnelEngine.VAY_DNS, parsedHuman?.engine)
        assertEquals("v.example.com", parsedHuman?.tunnelDomain)
        assertEquals("178.22.122.100", parsedHuman?.resolverIp)
        assertEquals(WhiteDnsRecordMode.TXT_CNAME, parsedHuman?.recordMode)

        val dnstB64 = WhiteDnsSmartScanner.buildDnstStandardUri(
            domain = "v.example.com",
            transport = "vaydns",
            backend = "socks",
            pubKeyHex = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f",
            recordType = "aaaa",
            resolverIp = "185.51.200.2",
            tag = "VayDNS-B64",
            asBase64Json = true,
        )
        assertTrue(dnstB64.startsWith("dnst://"))
        assertTrue('/' !in dnstB64.removePrefix("dnst://"))
        val parsedB64 = WhiteDnsSmartScanner.parseTunnelUri(dnstB64)
        assertTrue(parsedB64 != null)
        assertEquals(WhiteDnsSmartScanner.WhiteDnsTunnelEngine.VAY_DNS, parsedB64?.engine)
        assertEquals("v.example.com", parsedB64?.tunnelDomain)
        assertEquals("185.51.200.2", parsedB64?.resolverIp)
        assertEquals(WhiteDnsRecordMode.TXT_AAAA, parsedB64?.recordMode)

        val slipNetUri = WhiteDnsSmartScanner.buildSlipNetUri(
            domain = "n.example.com",
            resolverIp = "178.22.122.100",
            tunnelType = "noizdns",
            maxQuerySize = 60,
            queryPadding = 20,
            sshChain = true,
            remark = "SlipNet-NoizDNS-SSH",
        )
        val parsedSlipNet = WhiteDnsSmartScanner.parseTunnelUri(slipNetUri)
        assertTrue(parsedSlipNet != null)
        assertEquals(WhiteDnsSmartScanner.WhiteDnsTunnelEngine.NOIZ_DNS, parsedSlipNet?.engine)
        assertEquals("n.example.com", parsedSlipNet?.tunnelDomain)
        assertEquals("ssh", parsedSlipNet?.backendType)

        assertEquals(9, WhiteDnsSmartScanner.MasterDnsBalancingStrategy.entries.size)
        assertEquals(9, WhiteDnsSmartScanner.VayDnsRecordType.entries.size)
        val toml = WhiteDnsSmartScanner.buildMasterDnsClientToml(
            domains = listOf("v.example.com"),
            balancingStrategy = WhiteDnsSmartScanner.MasterDnsBalancingStrategy.TIERED_SHORTLIST_RR_6,
            packetDuplication = 2,
        )
        assertTrue(toml.contains("RESOLVER_BALANCING_STRATEGY = 6"))
        assertTrue(toml.contains("PACKET_DUPLICATION_COUNT = 2"))
        assertTrue(toml.contains("MIN_UPLOAD_MTU = 38"))

        val scoreCard = WhiteDnsSmartScanner.evaluateResolverSixPointScore(
            resolverIp = "178.22.122.100",
            providerNameFa = "شکن",
            providerNameEn = "Shecan",
            nsOk = true,
            txtOk = true,
            rndOk = true,
            dpiOk = true,
            ednsPayload = 1232,
            nxdHijacked = false,
            udpAlive = true,
            tcpAlive = true,
            rttMs = 31L,
        )
        assertEquals(6, scoreCard.score)
        assertEquals("NS✓ TXT✓ RND✓ DPI✓ EDNS✓(1232) NXD✓", scoreCard.badgeText)
        assertEquals("UDP+TCP", scoreCard.transportBadge)

        val mihomoProxies = SubConvConverter.convert(
            listOf(dnstHuman, dnstB64, slipNetUri).joinToString("\n"),
        )
        assertEquals(3, mihomoProxies.size)
        assertEquals("socks5", mihomoProxies.first().optString("type"))
        assertEquals("127.0.0.1", mihomoProxies.first().optString("server"))
    }

    @Test fun localSniSpoofProxyNonCfWhitelistFiveCfSpoofsAndHybridTunnelsAreValid() {
        val fakeHello = LocalSniSpoofProxy.buildFakeClientHelloRecord("ebanksepah.ir")
        assertEquals(0x16.toByte(), fakeHello[0])
        assertEquals(0x03.toByte(), fakeHello[1])
        assertEquals(0x01.toByte(), fakeHello[2])

        val sniOffset = LocalSniSpoofProxy.findSniHostnameOffset(fakeHello)
        assertTrue(sniOffset > 40)
        val asciiHost = String(fakeHello, sniOffset, "ebanksepah.ir".length, Charsets.US_ASCII)
        assertEquals("ebanksepah.ir", asciiHost)

        val splitPair = LocalSniSpoofProxy.splitClientHelloAtSni(fakeHello)
        assertTrue(splitPair != null)
        val (rec1, rec2) = splitPair!!
        assertEquals(0x16.toByte(), rec1[0])
        assertEquals(0x16.toByte(), rec2[0])
        val len1 = ((rec1[3].toInt() and 0xFF) shl 8) or (rec1[4].toInt() and 0xFF)
        val len2 = ((rec2[3].toInt() and 0xFF) shl 8) or (rec2[4].toInt() and 0xFF)
        val origLen = ((fakeHello[3].toInt() and 0xFF) shl 8) or (fakeHello[4].toInt() and 0xFF)
        assertEquals(origLen, len1 + len2)

        assertTrue(LocalSniSpoofProxy.NON_CF_WHITELIST_TARGETS.size >= 10)
        assertEquals(5, LocalSniSpoofProxy.CF_SPOOF_5_METHODS.size)

        val hybridsRaw = LocalSniSpoofProxy.buildHybridNetMelliTunnelConfigs()
        val hybridUris = hybridsRaw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(4, hybridUris.size)
        hybridUris.forEach { uri ->
            val parsed = WhiteDnsSmartScanner.parseTunnelUri(uri)
            assertTrue("Expected hybrid URI $uri to parse", parsed != null)
        }

        val aetherWithMasqueSni = AetherSelection(
            enabled = true,
            mode = AetherMode.MasqueHttp2,
            endpoint = "162.159.198.1:443",
            masqueSni = "ebanksepah.ir",
            echEnabled = true,
        )
        val uri = aetherWithMasqueSni.toAetherUri()
        assertTrue(uri.contains("masque_sni=ebanksepah.ir"))
        assertTrue(uri.contains("ech=1"))
        val roundTrip = AetherSelection.fromAetherUri(uri)
        assertEquals("ebanksepah.ir", roundTrip?.masqueSni)
        assertEquals(true, roundTrip?.echEnabled)
    }
}

