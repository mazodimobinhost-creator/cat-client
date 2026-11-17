package com.cat.client

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orthographic 3D globe math for the home connection sphere.
 * Yaw 0 = longitude 0 faces the camera. Tilt pitches north toward the camera.
 */
internal object Globe3d {
    data class Vec(val x: Float, val y: Float, val z: Float)

    fun sphere(lonDeg: Float, latDeg: Float, yawDeg: Float, tiltDeg: Float = 16f): Vec {
        val lat = Math.toRadians(latDeg.toDouble())
        val lon = Math.toRadians((lonDeg + yawDeg).toDouble())
        val cl = cos(lat)
        val x = (cl * sin(lon)).toFloat()
        val y = sin(lat).toFloat()
        val z = (cl * cos(lon)).toFloat()
        val tilt = Math.toRadians(tiltDeg.toDouble())
        val ct = cos(tilt).toFloat()
        val st = sin(tilt).toFloat()
        return Vec(x, y * ct - z * st, y * st + z * ct)
    }

    fun front(v: Vec, horizon: Float = 0.04f): Boolean = v.z > horizon

    fun light(v: Vec): Float =
        (v.x * 0.28f + v.y * 0.22f + v.z * 0.88f).coerceIn(0.22f, 1f)

    fun lerp(a: Vec, b: Vec, t: Float): Vec {
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        val z = a.z + (b.z - a.z) * t
        val n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-5f)
        return Vec(x / n, y / n, z / n)
    }
}
