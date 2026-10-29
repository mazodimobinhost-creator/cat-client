package com.cat.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta220: docs/CODEMAP.md (scripts/codemap.py) must know every source file, so «what is where» stays true. */
class CodeMapTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "docs/CODEMAP.md").isFile || File(it, "app/src").isDirectory }

    @Test
    fun everySourceFileIsInTheCodeMap() {
        val r = root()
        val map = File(r, "docs/CODEMAP.md").takeIf { it.isFile }?.readText()
            ?: File(r, "../docs/CODEMAP.md").readText()
        val src = listOf(File(r, "app/src/main/java/com/cat/client"), File(r, "src/main/java/com/cat/client")).first { it.isDirectory }
        val missing = src.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.name }.filter { "`$it`" !in map }
        assertTrue("run python3 scripts/codemap.py — missing: $missing", missing.isEmpty())
    }

    @Test
    fun screenAreaFilesOnlyHoldMainActivityExtensions() {
        val src = listOf(File("app/src/main/java/com/cat/client"), File("src/main/java/com/cat/client")).first { it.isDirectory }
        val areas = src.listFiles { f -> f.name.startsWith("MainActivity") && f.name != "MainActivity.kt" && f.name.endsWith(".kt") }.orEmpty()
        assertTrue(areas.size >= 10)
        for (f in areas) {
            val text = f.readText()
            // no second Activity class or top-level state hiding in an area file
            assertTrue(f.name, !Regex("(?m)^(class|object|internal class|internal object) ").containsMatchIn(text))
            assertTrue(f.name, !Regex("(?m)^(private |internal )?va[lr] ").containsMatchIn(text))
            assertTrue(f.name, Regex("(?m)^internal (suspend )?fun MainActivity\\.").containsMatchIn(text))
        }
    }
}
