package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carrier → fragment-preset decision, run as real code on the JVM.
 *
 * `scripts/panels/tune.test.mjs` proves the panel really emits the text table and that the
 * two id lists agree; this file runs the half that lives on the phone:
 *
 *  * the parser accepts the exact wire format and NOTHING else — an old chatty worker, a
 *    truncated answer or a captive-portal page must leave the cache (and the built-in
 *    table) alone instead of silently disabling fragmenting;
 *  * only carriers and presets the app can act on survive a parse, so a stored table can
 *    never contain a value that would be ignored at connect time;
 *  * the ladder is unambiguous: manual pick → panel recommendation → built-in carrier
 *    table → default, and an unknown stored value degrades to «automatic», never to a
 *    random preset.
 */
class PanelTuneTest {

    private val mci = MobileCarrier.MCI
    private val irancell = MobileCarrier.IRANCELL

    @Test
    fun textPayloadIsParsedAndUnknownRowsAreDropped() {
        val tune = PanelTune.parse(
            """
            cat-panel-tune v1
            version=6.67.2
            updated=1730000000000
            mci=pattn
            irancell=gaming
            rightel=does-not-exist-in-the-app
            evil=mci
            """.trimIndent(),
            now = 1234L,
        )

        assertEquals(mapOf("mci" to "pattn", "irancell" to "gaming"), tune.presets)
        assertEquals("6.67.2", tune.version)
        assertEquals(1234L, tune.fetchedAt)
    }

    @Test
    fun anythingWithoutTheMagicLineIsIgnored() {
        // The three real-world near-misses: an old worker's JSON, an HTML login page, a
        // half-written value. All three must parse to «nothing», which the caller reads as
        // «keep what you have».
        val bodies = listOf(
            null,
            "",
            "{\"ok\":true,\"tune\":{\"mci\":\"pattn\"}}",
            "<!DOCTYPE html><html>sign in</html>",
            "cat-panel-tune v2\nmci=pattn",
            "version=6.67.1\nmci=pattn",
        )
        for (body in bodies) {
            assertTrue("«${body?.take(24)}» was accepted", PanelTune.parse(body).isEmpty)
        }
    }

    @Test
    fun aManualPickAlwaysWins() {
        val panel = PanelTune(presets = mapOf("mci" to "gaming"))
        assertEquals(
            DpiFragmentPreset.PATTN,
            DpiFragmentPreset.resolve(DpiFragmentPreset.PATTN.id, mci, panel),
        )
    }

    @Test
    fun thePanelBeatsTheBuiltInTableWhichBeatsNothing() {
        assertEquals(
            DpiFragmentPreset.GAMING,
            DpiFragmentPreset.resolve(DpiFragmentPreset.AUTO_ID, mci, PanelTune(mapOf("mci" to "gaming"))),
        )
        // No recommendation for THIS carrier → the phone's own table decides.
        assertEquals(
            DpiFragmentPreset.forCarrier(irancell),
            DpiFragmentPreset.resolve(DpiFragmentPreset.AUTO_ID, irancell, PanelTune(mapOf("mci" to "gaming"))),
        )
        // No panel at all (offline, never deployed) → same thing.
        assertEquals(
            DpiFragmentPreset.forCarrier(irancell),
            DpiFragmentPreset.resolve(DpiFragmentPreset.AUTO_ID, irancell, null),
        )
        assertEquals(
            DpiFragmentPreset.IRANCELL,
            DpiFragmentPreset.resolve(null, irancell, null),
        )
    }

    @Test
    fun anUnknownStoredValueDegradesToAutomaticNotToDefault() {
        // «default» would silently switch fragmenting off for a carrier that needs it; the
        // automatic path keeps the carrier table in play.
        assertEquals(
            DpiFragmentPreset.MCI,
            DpiFragmentPreset.resolve("mci-from-a-future-version", mci, null),
        )
        assertEquals(
            DpiFragmentPreset.DEFAULT,
            DpiFragmentPreset.resolve("mci-from-a-future-version", MobileCarrier.UNKNOWN, null),
        )
    }

    @Test
    fun everyCarrierHasABuiltInPresetAndAutoIsNotAStoredPresetId() {
        for (carrier in MobileCarrier.entries) {
            assertTrue(
                "no built-in preset for ${carrier.id}",
                DpiFragmentPreset.forCarrier(carrier) in DpiFragmentPreset.entries,
            )
        }
        assertNull(DpiFragmentPreset.entries.firstOrNull { it.id == DpiFragmentPreset.AUTO_ID })
    }

    @Test
    fun operatorCodesMapToTheRightCarrierAndNothingElse() {
        assertEquals(mci, MobileCarrier.fromOperatorCode("43211"))
        assertEquals(mci, MobileCarrier.fromOperatorCode("43219"))
        assertEquals(irancell, MobileCarrier.fromOperatorCode("43235"))
        assertEquals(irancell, MobileCarrier.fromOperatorCode("43270"))
        assertEquals(MobileCarrier.RIGHTEL, MobileCarrier.fromOperatorCode("43220"))
        assertEquals(MobileCarrier.TCI, MobileCarrier.fromOperatorCode("43232"))
        assertEquals(MobileCarrier.TCI, MobileCarrier.fromOperatorCode("43214"))
        // Abroad, or a carrier that is not in the table: never guess.
        assertEquals(MobileCarrier.OTHER, MobileCarrier.fromOperatorCode("26201"))
        assertEquals(MobileCarrier.OTHER, MobileCarrier.fromOperatorCode("43299"))
        // Wi-Fi / no SIM / a radio that answers nothing.
        assertEquals(MobileCarrier.UNKNOWN, MobileCarrier.fromOperatorCode(""))
        assertEquals(MobileCarrier.UNKNOWN, MobileCarrier.fromOperatorCode(null))
        assertEquals(MobileCarrier.UNKNOWN, MobileCarrier.fromOperatorCode("432"))
        // Android sometimes pads the code; the digits are what matter.
        assertEquals(irancell, MobileCarrier.fromOperatorCode(" 43235 "))
    }

    @Test
    fun anEmptyTableNeverReportsARecommendation() {
        assertNull(PanelTune().presetIdFor(mci))
        assertNull(PanelTune(presets = mapOf("irancell" to "gaming")).presetIdFor(mci))
        assertTrue(PanelTune.parse(PanelTune.MAGIC).isEmpty)
        assertEquals(
            "gaming",
            PanelTune(presets = mapOf("irancell" to "gaming")).presetIdFor(irancell),
        )
    }
}
