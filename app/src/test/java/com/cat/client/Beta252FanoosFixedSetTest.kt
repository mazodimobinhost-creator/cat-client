package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * beta252, from the owner's screenshot: «🌍 every country» asked 20 locations and the free account got 🇩🇪 + 🇸🇬 back
 * EVERY time (8 servers from 2 countries, 19 lines of «↩️ not given»). The service decides, not Cat — so now:
 *  - after 3 ignored requests the run stops, says it plainly and remembers it for 24 h (the picker says so too);
 *  - the ignored locations are one line of flags, not one line each;
 *  - the same server under another row name counts once, and with 1–2 countries up to 8 servers each are kept.
 */
class Beta252FanoosFixedSetTest {
    private fun loc(code: String, city: String = "X") = LanternPlanner.Location(code, city, code)

    @Test
    fun screenshotCaseStopsAfterThreeIgnoredRequests() {
        val f = LanternPlanner.FixedCheck()
        val ds = setOf("DE", "SG")
        assertFalse(f.add(null, ds)) // 🎲 automatic
        assertEquals(ds, f.base)
        assertFalse("a country already in the set proves nothing", f.add(loc("DE", "Frankfurt"), ds))
        assertFalse(f.isSameAsAlways(loc("DE"), ds))
        assertTrue(f.isSameAsAlways(loc("NL"), ds))
        assertFalse(f.add(loc("NL"), ds))
        assertFalse(f.add(loc("GB"), ds))
        assertTrue("third ignored request → stop", f.add(loc("FR"), ds))
        assertFalse(f.honouredSeen)
    }

    @Test
    fun aServiceThatHonoursACountryIsNeverCutShort() {
        val f = LanternPlanner.FixedCheck()
        f.add(null, setOf("DE"))
        assertFalse(f.add(loc("NL"), setOf("DE")))
        assertFalse(f.add(loc("GB"), setOf("GB"))) // honoured: a NEW country came
        assertTrue(f.honouredSeen)
        assertFalse(f.add(loc("FR"), setOf("DE")))
        assertFalse(f.add(loc("SE"), setOf("DE")))
        assertFalse(f.add(loc("PL"), setOf("DE")))
    }

    @Test
    fun emptyAnswersDoNotCountAndADifferentSetIsNotIgnored() {
        val f = LanternPlanner.FixedCheck()
        assertFalse(f.add(loc("NL"), emptySet()))
        assertEquals(null, f.base)
        f.add(null, setOf("DE", "SG"))
        assertFalse(f.add(loc("NL"), setOf("DE")))
        assertFalse(f.add(loc("GB"), setOf("SG")))
        assertFalse(f.add(loc("FR"), setOf("DE", "SG")))
    }

    @Test
    fun theSameServerUnderAnotherNameIsOneServer() {
        val a = "vless://u@1.2.3.4:443?security=tls&sni=x.com#🏮 Cat Fanoos 🇩🇪 Germany Frankfurt #1"
        val b = "vless://u@1.2.3.4:443?security=tls&sni=x.com#🏮 Cat Fanoos 🇩🇪 Germany Frankfurt #3"
        val c = "vless://u@1.2.3.5:443?security=tls&sni=x.com#🏮 Cat Fanoos 🇩🇪 Germany Frankfurt #1"
        assertEquals(LanternPlanner.serverKey(a), LanternPlanner.serverKey(b))
        assertNotEquals(LanternPlanner.serverKey(a), LanternPlanner.serverKey(c))
        fun vm(ps: String, add: String) = "vmess://" + Base64.getEncoder().encodeToString("{\"v\":\"2\",\"ps\":\"$ps\",\"add\":\"$add\",\"port\":\"443\"}".toByteArray())
        assertEquals(LanternPlanner.serverKey(vm("A #1", "9.9.9.9")), LanternPlanner.serverKey(vm("B #2", "9.9.9.9")))
        assertNotEquals(LanternPlanner.serverKey(vm("A", "9.9.9.9")), LanternPlanner.serverKey(vm("A", "8.8.8.8")))
        val rows = listOf(a to "DE", b to "DE", c to "DE")
        assertEquals(2, LanternPlanner.limitPerCountry(rows).size)
        val merged = LanternPlanner.merge(listOf(b to "SG"), listOf(a to "DE"))
        assertEquals("an old row of the same server is not kept twice", 1, merged.size)
    }

    @Test
    fun fewCountriesKeepMoreServersEach() {
        assertEquals(8, LanternPlanner.perCountryFor(1))
        assertEquals(8, LanternPlanner.perCountryFor(2))
        assertEquals(5, LanternPlanner.perCountryFor(3))
        assertEquals(4, LanternPlanner.perCountryFor(4))
        assertEquals(4, LanternPlanner.perCountryFor(12))
        assertEquals(8, LanternPlanner.perCountryFor(0))
        val rows = (1..10).map { "ss://k@10.0.0.$it:8388#r$it" to (if (it % 2 == 0) "DE" else "SG") }
        assertEquals(8, LanternPlanner.limitPerCountry(rows, LanternPlanner.perCountryFor(2)).size.coerceAtMost(8))
        assertEquals(10, LanternPlanner.limitPerCountry(rows, LanternPlanner.perCountryFor(2)).size)
    }

    @Test
    fun screenWiresTheStopTheMemoryAndTheShortReport() {
        val f = listOf(File("src/main/java/com/cat/client/MainActivityFreeNetworks.kt"), File("app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt")).first { it.isFile }
        val src = f.readText()
        val dialog = src.substringAfter("internal fun MainActivity.showFanoosDialog()").substringBefore("private fun MainActivity.fetchFanoos(")
        assertTrue(dialog.contains("lanternFixedRecent()") && dialog.contains("fetchFanoosAll(emptyList())"))
        val all = src.substringAfter("private fun MainActivity.fetchFanoosAll(")
        assertTrue(all.contains("LanternPlanner.FixedCheck()") && all.contains("if (stop) { fixedConfirmed = true; break }"))
        assertTrue(all.contains("store.lanternFixed = base.joinToString(\",\")"))
        assertTrue(all.contains("LanternPlanner.perCountryFor("))
        assertTrue("ignored locations are grouped, not one line each", all.contains("-> notGiven += loc!!.label()"))
    }
}
