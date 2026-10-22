package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The tunnel section, exercised as real code (not as a source-grep).
 *
 * `scripts/panels/chain-tunnel.test.mjs` guards the SHAPE of the wiring; this file
 * runs the same Kotlin the phone runs, on the JVM, and checks the YAML that
 * actually reaches mihomo:
 *
 *   * a pinned address replaces `server:` (and `port:` when one is given) of the
 *     proxy the hop is dialled at — and nothing else;
 *   * inside a hop whose config carries its own `dialer-proxy`, only the OUTER
 *     proxy is repinned: the config that the hop needs to reach its own upstream
 *     keeps its address, because repinning it would break the very chain the
 *     subscription shipped;
 *   * the next hop still dials through the previous hop's exit;
 *   * a pin that cannot be applied throws instead of silently dialling the old
 *     address;
 *   * `normalized()` keeps a valid pin, drops a broken one, and drops both when
 *     the hop is off.
 */
class ConnectionChainTunnelTest {

    private fun yamlOf(vararg blocks: String): String = "proxies:\n" + blocks.joinToString("")

    private fun proxy(name: String, server: String, port: Int, dialerProxy: String? = null): String =
        buildString {
            append("  - name: ").append(name).append('\n')
            append("    type: vless\n")
            append("    server: ").append(server).append('\n')
            append("    port: ").append(port).append('\n')
            append("    uuid: 11111111-1111-1111-1111-111111111111\n")
            append("    network: ws\n")
            append("    tls: true\n")
            dialerProxy?.let { append("    dialer-proxy: ").append(it).append('\n') }
        }

    private fun sourceOf(
        rawYaml: String,
        selected: List<Triple<String, String, Int>>,
        subscriptionId: String = SUBSCRIPTION,
    ): ConnectionChainSource = ConnectionChainSource(
        subscriptionId = subscriptionId,
        subscriptionName = "Sub",
        snapshot = MihomoSubscriptionSnapshot(
            rawConfig = rawYaml,
            summary = MihomoConfigSummary(proxies = emptyList(), groups = emptyList()),
            catalog = SubscriptionCatalog(
                profiles = selected.map { (tag, server, port) ->
                    ConnectionProfile(
                        tag = tag,
                        type = "vless",
                        server = server,
                        port = port,
                        transport = "ws",
                        validationHost = server,
                    )
                },
                fetchedAt = 0L,
            ),
        ),
    )

    private fun fingerprintOf(source: ConnectionChainSource, tag: String): String =
        source.snapshot.catalog.profiles.first { it.tag == tag }.fingerprint

    @Test
    fun pinnedAddressReplacesServerAndPort() {
        val block = MihomoProxyBlockParser
            .parse(yamlOf(proxy("IR", "ir.example.com", 443)))
            .single()

        val rendered = block.render("CatClient Chain 1.1", null, "10.0.0.1:8443").joinToString("\n")

        assertTrue("server was not repinned:\n$rendered", rendered.contains("server: 10.0.0.1"))
        assertTrue("port was not repinned:\n$rendered", rendered.contains("port: 8443"))
        assertFalse("the old address survived:\n$rendered", rendered.contains("ir.example.com"))
        assertFalse("the old port survived:\n$rendered", rendered.contains("port: 443"))
    }

    @Test
    fun pinnedAddressWithoutPortLeavesThePortAlone() {
        val block = MihomoProxyBlockParser
            .parse(yamlOf(proxy("IR", "ir.example.com", 443)))
            .single()

        val rendered = block.render("CatClient Chain 1.1", null, "10.0.0.1").joinToString("\n")

        assertTrue(rendered.contains("server: 10.0.0.1"))
        assertTrue("a pin without a port must not rewrite the port:\n$rendered", rendered.contains("port: 443"))
    }

    @Test
    fun aPinThatCannotBeAppliedThrowsInsteadOfDiallingTheOldAddress() {
        // A block with no `server:` field at all — the shape a hand-edited subscription can
        // have. Silently keeping the old address would look like «the tunnel works» while
        // the phone dials something else entirely.
        val block = MihomoProxyBlockParser
            .parse(yamlOf("  - name: Weird\n    type: socks5\n    port: 1080\n"))
            .single()

        try {
            block.render("CatClient Chain 1.1", null, "10.0.0.1")
            fail("render() accepted a pin it could not apply")
        } catch (expected: Exception) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun thePlannerPinsTheFirstLegOfEveryHopAndLeavesNothingElseTouched() {
        val rawYaml = yamlOf(
            proxy("IR", "ir.example.com", 443),
            proxy("CF", "cf.example.com", 8443),
        )
        val source = sourceOf(
            rawYaml,
            listOf(Triple("IR", "ir.example.com", 443), Triple("CF", "cf.example.com", 8443)),
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.fixed(
                SUBSCRIPTION,
                fingerprintOf(source, "IR"),
                serverOverride = "10.0.0.1",
            ),
            after = ConnectionChainHop.fixed(
                SUBSCRIPTION,
                fingerprintOf(source, "CF"),
                serverOverride = "104.16.0.1:8443",
            ),
        ).normalized()

        val plan = ConnectionChainPlanner
            .plans(settings, listOf(source), emptyList(), allowIpv6Literals = true)
            .single()

        assertTrue("the Iran leg was not pinned:\n${plan.rawYaml}", plan.rawYaml.contains("server: 10.0.0.1"))
        assertTrue("the Cloudflare leg was not pinned:\n${plan.rawYaml}", plan.rawYaml.contains("server: 104.16.0.1"))
        assertTrue("the pinned port did not land:\n${plan.rawYaml}", plan.rawYaml.contains("port: 8443"))
        assertFalse("the superseded address is still dialled:\n${plan.rawYaml}", plan.rawYaml.contains("ir.example.com"))
        assertFalse("the superseded address is still dialled:\n${plan.rawYaml}", plan.rawYaml.contains("cf.example.com"))
        assertTrue(
            "hop 2 must dial through hop 1's exit:\n${plan.rawYaml}",
            plan.rawYaml.contains("dialer-proxy: 'CatClient Chain 1.1'"),
        )
        assertEquals("10.0.0.1", plan.hops[0].serverOverride)
        assertEquals("104.16.0.1:8443", plan.hops[1].serverOverride)
        assertTrue(
            "the selected group must point at the last hop's exit:\n${plan.rawYaml}",
            plan.rawYaml.contains("      - '${plan.finalRuntimeName}'"),
        )
    }

    @Test
    fun aHopThatCarriesItsOwnChainKeepsTheUpstreamAddressItNeeds() {
        // «Exit» is a config whose own dialer-proxy is «Mid»: the hop's leg order is
        // Mid → Exit, and only Mid may be repinned. Repinning Exit would cut the route
        // the subscription itself configured.
        val rawYaml = yamlOf(
            proxy("IR", "ir.example.com", 443),
            proxy("Mid", "mid.example.com", 2053),
            proxy("Exit", "exit.example.com", 2096, dialerProxy = "Mid"),
        )
        val source = sourceOf(
            rawYaml,
            listOf(Triple("IR", "ir.example.com", 443), Triple("Exit", "exit.example.com", 2096)),
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.fixed(SUBSCRIPTION, fingerprintOf(source, "IR")),
            after = ConnectionChainHop.fixed(
                SUBSCRIPTION,
                fingerprintOf(source, "Exit"),
                serverOverride = "104.16.0.1",
            ),
        ).normalized()

        val plan = ConnectionChainPlanner
            .plans(settings, listOf(source), emptyList(), allowIpv6Literals = true)
            .single()

        assertEquals("Mid", plan.hops[1].candidate.entryBlock.name)
        assertEquals("Exit", plan.hops[1].candidate.rootBlock.name)
        assertTrue("the hop's outer leg was not pinned:\n${plan.rawYaml}", plan.rawYaml.contains("server: 104.16.0.1"))
        assertFalse("the hop's outer leg kept the old address:\n${plan.rawYaml}", plan.rawYaml.contains("mid.example.com"))
        assertTrue(
            "the address the chained config needs was stripped:\n${plan.rawYaml}",
            plan.rawYaml.contains("server: exit.example.com"),
        )
        assertTrue("hop 1 kept its own address:\n${plan.rawYaml}", plan.rawYaml.contains("server: ir.example.com"))
    }

    @Test
    fun anUnpinnedChainKeepsEveryAddressFromTheSubscription() {
        val rawYaml = yamlOf(
            proxy("IR", "ir.example.com", 443),
            proxy("CF", "cf.example.com", 8443),
        )
        val source = sourceOf(
            rawYaml,
            listOf(Triple("IR", "ir.example.com", 443), Triple("CF", "cf.example.com", 8443)),
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.fixed(SUBSCRIPTION, fingerprintOf(source, "IR")),
            after = ConnectionChainHop.fixed(SUBSCRIPTION, fingerprintOf(source, "CF")),
        ).normalized()

        val plan = ConnectionChainPlanner
            .plans(settings, listOf(source), emptyList(), allowIpv6Literals = true)
            .single()

        assertTrue(plan.rawYaml.contains("server: ir.example.com"))
        assertTrue(plan.rawYaml.contains("server: cf.example.com"))
    }

    @Test
    fun normalizedKeepsAValidPinDropsABrokenOneAndForgetsAnOffHop() {
        val valid = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.automatic().copy(serverOverride = "10.0.0.1:8443"),
            after = ConnectionChainHop.automatic().copy(serverOverride = "2606:4700::1"),
        ).normalized()
        assertEquals("10.0.0.1:8443", valid.hop(ConnectionChainSlot.Base).serverOverride)
        assertEquals("2606:4700::1", valid.hop(ConnectionChainSlot.After).serverOverride)

        val broken = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.automatic().copy(serverOverride = "1.2.3.4:99999"),
        ).normalized()
        assertNull(
            "a pin the runtime cannot dial must be dropped, not carried into the runtime",
            broken.hop(ConnectionChainSlot.Base).serverOverride,
        )

        val off = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop(ConnectionChainHopMode.Off, serverOverride = "10.0.0.1"),
            base = ConnectionChainHop.automatic(),
        ).normalized()
        assertNull("an off hop must not keep a pin", off.hop(ConnectionChainSlot.Before).serverOverride)
    }

    @Test
    fun firstConfiguredSlotNamesTheLegTheDeviceDialsFirst() {
        val withEntry = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.fixed(SUBSCRIPTION, "fp"),
            base = ConnectionChainHop.automatic(),
        )
        assertEquals(ConnectionChainSlot.Before, withEntry.firstConfiguredSlot())
        assertTrue(withEntry.isActive)

        // Chain on, but only the default base hop → nothing to tunnel into: the scanner
        // must fall back to plain fronting instead of pinning a chain that never runs.
        val singleHop = ConnectionChainSettings(enabled = true)
        assertFalse(singleHop.isActive)
        assertEquals(ConnectionChainSlot.Base, singleHop.firstConfiguredSlot())
    }

    private companion object {
        const val SUBSCRIPTION = "sub-1"
    }

    // ── exit country ─────────────────────────────────────────────────────────
    // The chain used to be chosen by name or by measured delay only, and the app's location
    // setting was dropped on this path (hardcoded null), so «connect from Germany» quietly did
    // nothing. These four tests run the decision on the JVM: what narrows, what wins, and what
    // happens when the wish cannot be granted.

    /**
     * Three distinct configs: a fixed road (IR) and two possible exits (DE, TR). The road must
     * be a THIRD config — a chain whose two hops are the same profile is refused
     * (`SameConnection`), which is exactly what a lazier fixture gets wrong.
     */
    private fun exitFixture(): Pair<ConnectionChainSource, ConnectionChainSettings> {
        val rawYaml = yamlOf(
            proxy("IR Tehran", "ir.example.com", 2053),
            proxy("DE Frankfurt", "de.example.com", 443),
            proxy("TR Istanbul", "tr.example.com", 8443),
        )
        val source = sourceOf(
            rawYaml,
            listOf(
                Triple("IR Tehran", "ir.example.com", 2053),
                Triple("DE Frankfurt", "de.example.com", 443),
                Triple("TR Istanbul", "tr.example.com", 8443),
            ),
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            base = ConnectionChainHop.fixed(SUBSCRIPTION, fingerprintOf(source, "IR Tehran")),
            after = ConnectionChainHop.automatic(),
        ).normalized()
        return source to settings
    }

    @Test
    fun theRequestedExitCountryNarrowsTheFinalHopOnly() {
        val (source, settings) = exitFixture()

        val plan = ConnectionChainPlanner
            .plans(
                settings,
                listOf(source),
                emptyList(),
                allowIpv6Literals = true,
                requestedExitCountryCode = "de",
            )
            .single()

        assertEquals("DE", plan.exitCountryCode)
        assertEquals("DE", plan.requestedExitCountryCode)
        assertTrue("a plan that landed in DE must say it matched", plan.exitCountryMatched)
        assertEquals("DE Frankfurt", plan.finalHop.candidate.profile.tag)
        assertEquals(
            "the road must not be filtered by the destination country",
            "IR Tehran",
            plan.hops.first().candidate.profile.tag,
        )
    }

    @Test
    fun anImpossibleCountryKeepsTheTunnelAndAdmitsTheFallback() {
        val (source, settings) = exitFixture()

        // The Netherlands is in neither config: a country wish must never cost the user their
        // connection, so plans must still exist — but not one of them may claim it used NL.
        // (There are two here: DE and TR are both legitimate fallbacks, and the planner is
        // allowed to offer both, in rank order.)
        val plans = ConnectionChainPlanner.plans(
            settings,
            listOf(source),
            emptyList(),
            allowIpv6Literals = true,
            requestedExitCountryCode = "NL",
        )

        assertTrue("an impossible country must not stop the chain", plans.isNotEmpty())
        for (plan in plans) {
            assertEquals("NL", plan.requestedExitCountryCode)
            assertFalse("a plan claimed a country it did not use", plan.exitCountryMatched)
            assertTrue(
                "the fallback exit must be one of the configs the chain could actually use",
                plan.exitCountryCode == "DE" || plan.exitCountryCode == "TR",
            )
        }
    }

    @Test
    fun aFixedExitOutranksTheCountryWish() {
        val (source, settings) = exitFixture()
        val fixedExit = settings.copy(
            after = ConnectionChainHop.fixed(SUBSCRIPTION, fingerprintOf(source, "TR Istanbul")),
        ).normalized()
        assertTrue("the fixture must keep the road distinct from the fixed exit", fixedExit.isActive)

        val plan = ConnectionChainPlanner
            .plans(
                fixedExit,
                listOf(source),
                emptyList(),
                allowIpv6Literals = true,
                requestedExitCountryCode = "DE",
            )
            .single()

        // An explicit pick is the user's own decision and stays — but the plan says out loud
        // that it is not the requested country, which is what the service then logs.
        assertEquals("TR Istanbul", plan.finalHop.candidate.profile.tag)
        assertFalse(plan.exitCountryMatched)
    }

    @Test
    fun exitCountryCodesListsOnlyWhatTheChainCanReach() {
        val (source, settings) = exitFixture()

        // Every config the automatic exit slot may land in — the road config included, because
        // nothing stops the user from asking for its country as the destination.
        assertEquals(listOf("DE", "IR", "TR"), ConnectionChainPlanner.exitCountryCodes(settings, listOf(source)))

        val disabled = settings.copy(enabled = false).normalized()
        assertTrue(ConnectionChainPlanner.exitCountryCodes(disabled, listOf(source)).isEmpty())

        val noCountry = sourceOf(yamlOf(proxy("Node 1", "n1.example.com", 443)), listOf(Triple("Node 1", "n1.example.com", 443)))
        assertEquals(
            "a config that names no country must not invent one",
            emptyList<String>(),
            ConnectionChainPlanner.exitCountryCodes(settings, listOf(noCountry)),
        )
    }
}
