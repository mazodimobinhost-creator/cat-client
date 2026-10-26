package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDesignTest {
    @Test
    fun designWireNamesRoundTripAndDefaultToPortal() {
        AppDesign.entries.forEach { assertEquals(it, AppDesign.fromWireName(it.wireName)) }
        assertEquals(AppDesign.Portal, AppDesign.fromWireName(null))
        assertEquals(AppDesign.Portal, AppDesign.fromWireName("unknown"))
        assertEquals(listOf("pixel", "portal", "old"), AppDesign.entries.map { it.wireName })
    }

    @Test
    fun colorStyleAndContrastDefaults() {
        ColorStyle.entries.forEach { assertEquals(it, ColorStyle.fromWireName(it.wireName)) }
        ColorContrast.entries.forEach { assertEquals(it, ColorContrast.fromWireName(it.wireName)) }
        assertEquals(ColorStyle.Cat, ColorStyle.fromWireName(null))
        assertEquals(ColorContrast.Standard, ColorContrast.fromWireName("x"))
        assertEquals(9, ColorStyle.entries.size)
        assertEquals(ColorStyle.entries.size, ColorStyle.entries.map { it.wireName }.toSet().size)
    }

    @Test
    fun shapeRadiusIsNormalised() {
        ZedShape.entries.forEach { shape ->
            var max = 0f
            var min = Float.MAX_VALUE
            for (i in 0 until 720) {
                val r = shape.radius((i / 720f) * (2f * Math.PI.toFloat()))
                assertTrue("${shape.name} radius must be finite", r.isFinite())
                max = maxOf(max, r)
                min = minOf(min, r)
            }
            assertTrue("${shape.name} max radius $max", max <= 1.02f && max > 0.85f)
            assertTrue("${shape.name} min radius $min", min > 0.2f)
        }
        assertEquals(1f, ZedShape.Circle.radius(1.234f), 1e-6f)
    }
}
