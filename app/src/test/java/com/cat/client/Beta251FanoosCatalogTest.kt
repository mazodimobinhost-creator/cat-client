package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta251: Fanoos showed only 2–3 configs because the free account's answer usually carries no location list, so
 * the «🌍 every country» choice was never offered and only the service's default servers came. Now:
 *  - a catalog of common locations is always offered and asked for (the service's own list goes first);
 *  - locations the service names during the run join the queue;
 *  - a location the service refuses (HTTP 4xx) is reported as «not given» and does not stop the run.
 */
class Beta251FanoosCatalogTest {
    private fun key(l: LanternPlanner.Location) = l.code.uppercase() + "|" + l.city.lowercase()

    @Test
    fun catalogIsRealCountriesWithoutDuplicates() {
        val c = LanternPlanner.CATALOG
        assertTrue(c.size >= 15)
        assertEquals(c.size, c.map(::key).toSet().size)
        assertTrue(c.all { it.code.matches(Regex("[A-Z]{2}")) && it.flag.isNotEmpty() && it.city.isNotBlank() && it.country.isNotBlank() })
        assertTrue(c.map { it.code }.toSet().size >= 15)
        assertTrue(c.size <= LanternPlanner.ALL_COUNTRIES_MAX_LOCATIONS)
    }

    @Test
    fun allTargetsPutsTheServiceListFirstAndDeduplicates() {
        assertEquals(LanternPlanner.CATALOG, LanternPlanner.allTargets(emptyList()))
        val offered = listOf(
            LanternPlanner.Location("Germany", "frankfurt", "de"),
            LanternPlanner.Location("Moldova", "Chisinau", "MD"),
        )
        val t = LanternPlanner.allTargets(offered)
        assertEquals(offered, t.take(2))
        assertEquals(1, t.count { it.code.equals("DE", true) && it.city.equals("Frankfurt", true) })
        assertEquals(t.size, t.map(::key).toSet().size)
        assertEquals(3, LanternPlanner.allTargets(offered, max = 3).size)
        assertTrue(LanternPlanner.allTargets(offered).size <= LanternPlanner.ALL_COUNTRIES_MAX_LOCATIONS)
    }

    @Test
    fun queueGrowsOnlyWithNewPlacesAndRespectsTheCap() {
        val q = mutableListOf(LanternPlanner.Location("Germany", "Frankfurt", "DE"))
        val added = LanternPlanner.extendQueue(q, listOf(
            LanternPlanner.Location("Germany", "FRANKFURT", "de"),
            LanternPlanner.Location("Japan", "Osaka", "JP"),
            LanternPlanner.Location("Japan", "Osaka", "JP"),
        ))
        assertEquals(1, added)
        assertEquals(listOf("DE", "JP"), q.map { it.code })
        val full = LanternPlanner.CATALOG.toMutableList()
        assertEquals(1, LanternPlanner.extendQueue(full, listOf(LanternPlanner.Location("Chile", "Santiago", "CL"), LanternPlanner.Location("Peru", "Lima", "PE")), max = full.size + 1))
    }

    @Test
    fun refusedLocationIsNotTreatedAsTheServiceGoingDown() {
        assertTrue(LanternPlanner.answeredButRefused("config: df.getiantem.org → HTTP 400 {\"error\":\"bad location\"}"))
        assertTrue(LanternPlanner.answeredButRefused("config: df.iantem.io → HTTP 404 nope"))
        assertFalse(LanternPlanner.answeredButRefused("config: df.getiantem.org → timeout"))
        assertFalse(LanternPlanner.answeredButRefused("config: df.getiantem.org → HTTP 502 (CDN block page)"))
        assertFalse(LanternPlanner.answeredButRefused(null))
    }

    @Test
    fun pickerIsAlwaysShownAndEveryCountryUsesTheCatalog() {
        val f = listOf(File("src/main/java/com/cat/client/MainActivityFreeNetworks.kt"), File("app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt")).first { it.isFile }
        val src = f.readText()
        val dialog = src.substringAfter("internal fun MainActivity.showFanoosDialog()").substringBefore("private fun MainActivity.fetchFanoos(")
        assertFalse("no silent single fetch when the service list is empty", dialog.contains("fetchFanoos(null)\n        return"))
        assertTrue(dialog.contains("ifEmpty { LanternPlanner.CATALOG }"))
        assertTrue(dialog.contains("LanternPlanner.allTargets("))
        val all = src.substringAfter("private fun MainActivity.fetchFanoosAll(")
        assertTrue(all.contains("LanternPlanner.extendQueue(") && all.contains("LanternPlanner.answeredButRefused("))
        assertTrue(all.contains("if (refused) continue"))
    }
}
