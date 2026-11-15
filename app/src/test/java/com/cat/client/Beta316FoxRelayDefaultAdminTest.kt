package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta316 — Cat Fox relay default secret is admin. Bare workers.dev URL uses it. */
class Beta316FoxRelayDefaultAdminTest {

    @Test
    fun versionIsBeta316() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 384"))
        assertTrue(gradle.contains("1.10.0-beta317"))
    }

    @Test
    fun bareUrlAndCopiedWorkerUseAdmin() {
        assertEquals("admin", FoxMozillaRelay.DEFAULT_SECRET)
        assertEquals(
            "https://my-relay.example.workers.dev/?secret=admin",
            FoxMozillaRelay.normalize("https://my-relay.example.workers.dev/"),
        )
        assertEquals(
            "https://my-relay.example.workers.dev/?secret=admin&health=1",
            FoxMozillaRelay.healthUrl("https://my-relay.example.workers.dev/"),
        )
        assertNull(FoxMozillaRelay.formIssue("https://my-relay.example.workers.dev/"))
        val js = FoxMozillaRelay.workerScript()
        assertTrue(js.contains("const SECRET = \"admin\";"))
        assertTrue(js.contains("cat-fox"))
        assertFalse(js.contains("Spider"))
        assertFalse(js.contains("Arman"))
        val custom = FoxMozillaRelay.workerScript("abc12345")
        assertTrue(custom.contains("const SECRET = \"abc12345\";"))
        assertFalse(custom.contains("const SECRET = \"admin\";"))
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("DEFAULT_SECRET"))
        assertTrue(fox.contains("رمز پیش‌فرض admin"))
        assertTrue(fox.contains("default secret is admin") || fox.contains("Default secret is admin"))
        assertFalse(fox.contains("FoxMozillaRelay.newSecret()"))
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}
