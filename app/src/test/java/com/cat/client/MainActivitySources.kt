package com.cat.client

import java.io.File

/**
 * MainActivity is split over MainActivity.kt + MainActivity<Area>.kt (beta220; see docs/CODEMAP.md).
 * Source-contract tests read the screen code through here so they keep working no matter which file a function
 * lives in. Moved functions are written `internal fun MainActivity.name(`; [combined] shows them as
 * `private fun name(` (and widened `internal` members as `private`) so contracts written against the old single
 * file still match.
 */
internal object MainActivitySources {
    private fun dir(): File =
        listOf(File("src/main/java/com/cat/client"), File("app/src/main/java/com/cat/client")).first { it.isDirectory }

    /** All MainActivity source files: MainActivity.kt first, then the area files in name order. */
    fun files(): List<File> {
        val d = dir()
        val parts = d.listFiles { f -> f.isFile && f.name.startsWith("MainActivity") && f.name.endsWith(".kt") && f.name != "MainActivity.kt" }
            .orEmpty().sortedBy { it.name }
        return listOf(File(d, "MainActivity.kt")) + parts
    }

    fun combined(): String {
        val raw = files().joinToString("\n") { it.readText() }
        // an extension's receiver label is its own name (`this@showFoo`); show it as `this@MainActivity` again
        val moved = Regex("(?m)^internal (?:suspend )?fun MainActivity\\.(\\w+)\\(").findAll(raw).map { it.groupValues[1] }.toSet()
        return raw.replace(Regex("this@(\\w+)")) { if (it.groupValues[1] in moved) "this@MainActivity" else it.value }
        .replace(Regex("(?m)^internal (suspend )?fun MainActivity\\.")) { "private ${it.groupValues[1]}fun " }
        // members the area files use were widened from private to internal; contracts are visibility-insensitive
        .replace(Regex("(?m)^(    (?:@\\w+ )*)internal (?!companion)")) { "${it.groupValues[1]}private " }
    }

    /** Text of one function: from its declaration to the next function declaration at member or file level. */
    fun function(name: String): String {
        val all = combined()
        val start = Regex("(?m)^ {0,4}(?:private |internal |override )?(?:suspend )?fun (?:<[^>]*> )?$name\\(").find(all)
            ?: throw AssertionError("function $name not found in MainActivity sources")
        val next = Regex("(?m)^ {0,4}(?:@\\S+\\s+)*(?:private |internal |override |protected )*(?:suspend )?fun ")
            .find(all, start.range.last + 1)
        return all.substring(start.range.first, next?.range?.first ?: all.length)
    }
}
