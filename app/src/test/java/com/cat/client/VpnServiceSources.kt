package com.cat.client

import java.io.File

/**
 * beta222: CatClientVpnService is split over CatClientVpnService.kt + CatClientVpnService<Area>.kt (per engine/feature,
 * see docs/CODEMAP.md). Source-contract tests read the service through here so a function can live in any of those
 * files. Moved functions are written `internal fun CatClientVpnService.name(`; [combined] shows them as
 * `private fun name(` and widened members as `private`, so contracts written against the single file still match.
 */
internal object VpnServiceSources {
    private fun dir(): File =
        listOf(File("src/main/java/com/cat/client"), File("app/src/main/java/com/cat/client")).first { it.isDirectory }

    fun files(): List<File> {
        val d = dir()
        val parts = d.listFiles { f -> f.isFile && f.name.startsWith("CatClientVpnService") && f.name.endsWith(".kt") && f.name != "CatClientVpnService.kt" }
            .orEmpty().sortedBy { it.name }
        return listOf(File(d, "CatClientVpnService.kt")) + parts
    }

    fun combined(): String {
        val raw = files().joinToString("\n") { it.readText() }
        // an extension's receiver label is its own name (`this@runAetherTorStartup`); show it as the class label again
        val moved = Regex("(?m)^internal (?:suspend )?fun CatClientVpnService\\.(\\w+)\\(").findAll(raw).map { it.groupValues[1] }.toSet()
        return raw
            .replace(Regex("this@(\\w+)")) { if (it.groupValues[1] in moved) "this@CatClientVpnService" else it.value }
            .replace(Regex("(?m)^internal (suspend )?fun CatClientVpnService\\.")) { "private ${it.groupValues[1]}fun " }
            .replace(Regex("(?m)^(    (?:@\\w+ )*)internal (?!companion)")) { "${it.groupValues[1]}private " }
    }
}
