package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Beta209WarpEditorTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    private val key = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private val store = """
        version = 1

        [devices.64c41a30-1111-2222-3333-444455556666]
        access_token = "tok"
        ipv4 = "172.16.0.2"
        ipv6 = "2606:4700:110:8696:d25c:d0f:1f55:437d"
        wg_private_key = "$key"
        wg_peer_public_key = "$key"
        client_id = "AQID"
        tunnel_type = "masque"
        cert_pem = '''
        -----BEGIN CERTIFICATE-----
        abc
        -----END CERTIFICATE-----
        '''
        key_pem = "k"

        [devices."05ae02d9-aaaa"]
        access_token = "tok2"
        ipv4 = "172.16.0.2"
        ipv6 = "2606:4700:110:8c97:b23e:e97f:6f08:fa2f"
        wg_private_key = "$key"
        wg_peer_public_key = "$key"
        client_id = "AQID"

        [slots]
        masque = "64c41a30-1111-2222-3333-444455556666"
        wireguard = "05ae02d9-aaaa"

        [registration]
        attempts = 0
    """.trimIndent()

    @Test fun storeIsReadLikeTheCoreDoes() {
        val devices = AetherIdentityStore.parseStore(store)
        assertEquals(listOf("masque", "wireguard"), devices.map { it.slot })
        val masque = devices.first()
        assertEquals("64c41a30", masque.shortId)
        assertFalse("a MASQUE enrolment destroys the WireGuard key", masque.wireGuardUsable)
        assertTrue(masque.hasCertificate)
        val wg = devices.last()
        assertTrue(wg.wireGuardUsable)
        val account = AetherIdentityStore.toAccount(wg)
        assertNotNull(account)
        assertEquals(listOf(1, 2, 3), account!!.reserved)
        assertEquals("2606:4700:110:8c97:b23e:e97f:6f08:fa2f", account.ipv6)
        val lines = AetherIdentityStore.describe(devices, farsi = false)
        assertTrue(lines[0], lines[0].startsWith("Outer hop key (MASQUE): device 64c41a30…, IPv4 172.16.0.2, IPv6 2606:"))
        assertFalse(lines.joinToString().contains(key))
    }

    @Test fun storeFileSitsNextToAetherToml() {
        val dir = Files.createTempDirectory("aether").toFile()
        val base = File(dir, "aether.toml")
        assertEquals("aether-store.toml", AetherIdentityStore.storeFile(base).name)
        assertEquals("aether-masque-secondary.toml", AetherIdentityStore.sibling(AetherIdentityStore.sibling(base, "masque"), "secondary").name)
        assertTrue(AetherIdentityStore.read(base).isEmpty())
        AetherIdentityStore.storeFile(base).writeText(store)
        assertNotNull(AetherIdentityStore.wireGuardAccount(base))
        dir.deleteRecursively()
    }

    @Test fun legacyFileWithCertificateIsNotOfferedForWireGuard() {
        val legacy = "device_id = \"abc12345\"\naccess_token = \"t\"\nipv4 = \"172.16.0.2\"\nipv6 = \"\"\n" +
            "wg_private_key = \"$key\"\nwg_peer_public_key = \"$key\"\ncert_pem = \"x\"\n"
        val d = AetherIdentityStore.parseLegacy(legacy, "wireguard")!!
        assertFalse(d.wireGuardUsable)
        assertNull(AetherIdentityStore.toAccount(d))
        val clean = AetherIdentityStore.parseLegacy(legacy.replace("cert_pem = \"x\"\n", ""), "wireguard")!!
        assertTrue(clean.wireGuardUsable)
    }

    @Test fun commandPreviewMatchesTheCli() {
        val mim = AetherSelection(mode = AetherMode.MasqueInMasque, ipScan = "v4", masqueTransport = "h2")
        val cmd = AetherCommandPreview.build(mim, 10820)
        assertTrue(cmd, cmd.startsWith("aether --bind 127.0.0.1:10820 --protocol mim --scan balanced --ip v4 --masque-sni www.cloudflare.com --h2 --fragment"))
        assertTrue(cmd, cmd.contains("--mim-scan") && cmd.contains("--no-quick-reconnect") && !cmd.contains("--noize"))
        val pinned = AetherCommandPreview.build(
            mim.copy(endpoint = "162.159.198.1:443", innerEndpoint = "162.159.198.2:443", chainTarget = "psiphon", psiphonRegion = "DE"),
        )
        assertTrue(pinned, pinned.contains("--mim-outer 162.159.198.1:443 --mim-inner 162.159.198.2:443") &&
            pinned.contains("--quick-reconnect") && pinned.endsWith("--psiphon chain --psiphon-region DE"))
        val wg = AetherCommandPreview.build(AetherSelection(mode = AetherMode.WireGuard, endpoint = "162.159.192.1:2408"))
        assertTrue(wg, wg.contains("--protocol wg") && !wg.contains("--h2") && !wg.contains("--masque-sni") && wg.contains("--peer 162.159.192.1:2408"))
    }

    @Test fun psiphonDataFoldersAreCreatedBeforeStart() {
        val dir = Files.createTempDirectory("psi").toFile()
        val root = File(dir, "psiphon")
        assertTrue(PsiphonCarrierConfig.ensureDataDirectories(root))
        assertTrue(File(root, "ca.psiphon.PsiphonTunnel.tunnel-core/datastore").isDirectory)
        assertTrue(src("PsiphonCarrierConfig.kt").contains("ensureDataDirectories(root)"))
        dir.deleteRecursively()
    }

    @Test fun warpRowsGetARealKeyAndTheEditorIsWired() {
        val warp = src("WarpRegistration.kt")
        assertTrue(warp.contains("AetherIdentityStore.wireGuardAccount(identity)"))
        assertTrue(warp.contains("AetherOperations.provision(AetherMode.WireGuard"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("R.string.subscription_action_edit to { editSubscriptionOrAetherConfig(item) }"))
        assertTrue(main.contains("explainIfEveryWireGuardRowFailed()"))
        assertFalse("no made-up endpoint after a failed scan", main.contains("Selected endpoint \$fallbackPeer"))
    }

    @Test fun legacyPrefilledMasqueSniFallsBackToTheCoreDefault() {
        val id = File("/tmp/aether.toml")
        val legacy = org.json.JSONObject(
            AetherRuntimeConfig(AetherMode.MasqueInMasque, id, masqueSni = "consumer-masque.cloudflareclient.com").toNativeJson(),
        )
        assertFalse(legacy.has("masqueSni"))
        val custom = org.json.JSONObject(AetherRuntimeConfig(AetherMode.MasqueInMasque, id, masqueSni = "ebanksepah.ir").toNativeJson())
        assertEquals("ebanksepah.ir", custom.getString("masqueSni"))
        val uri = AetherSelection(mode = AetherMode.MasqueInMasque, masqueSni = "consumer-masque.cloudflareclient.com").toAetherUri()
        assertFalse(uri, uri.contains("masque_sni="))
        assertFalse(src("MainActivity.kt").contains(".ifBlank { \"consumer-masque.cloudflareclient.com\" }"))
    }
}
