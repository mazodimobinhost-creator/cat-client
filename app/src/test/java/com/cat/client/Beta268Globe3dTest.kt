package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta268Globe3dTest {

    @Test
    fun versionIsBeta268() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 358"))
        assertTrue(gradle.contains("1.10.0-beta291"))
    }

    @Test
    fun equatorAtLon0FacesTheCamera() {
        val front = Globe3d.sphere(0f, 0f, 0f)
        assertTrue(front.z > 0.9f)
        assertTrue(Globe3d.front(front))
        val back = Globe3d.sphere(180f, 0f, 0f)
        assertTrue(back.z < 0f)
        assertFalse(Globe3d.front(back))
    }

    @Test
    fun iranFacesCameraWhenYawMatchesLongitude() {
        val iran = Globe3d.sphere(51.4f, 35.7f, -51.4f)
        assertTrue(Globe3d.front(iran))
        assertTrue(iran.z > 0.7f)
    }

    @Test
    fun codeGsHasNoBlockCommentThatBreaksAppsScript() {
        val gs = listOf(
            File("app/src/main/assets/catgp/Code.gs"),
            File("src/main/assets/catgp/Code.gs"),
        ).first { it.isFile }.readText()
        assertFalse(gs.contains("/*"))
        assertTrue(gs.trimStart().startsWith("//"))
        assertTrue(gs.contains("var CAT_KEY = __CAT_KEY__"))
        assertTrue(gs.contains("var CAT_WORKER = __CAT_WORKER__"))
        assertTrue(gs.contains("relay: \"cat-gp\""))
    }
}
