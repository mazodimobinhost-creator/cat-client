package com.cat.client

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Stores only conclusive probe evidence. Network-change/captive results are intentionally ephemeral. */
internal class DeploymentMeasurementStore(private val preferences: SharedPreferences) {
    fun read(profiles: List<DeploymentProfile>): List<DeploymentProbeMeasurement> {
        val byId = profiles.mapNotNull { it.normalized() }.associateBy { it.id }
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val profile = byId[item.optString("id")] ?: continue
                    val verdict = runCatching { DeploymentProbeVerdict.valueOf(item.optString("verdict")) }.getOrNull() ?: continue
                    if (verdict == DeploymentProbeVerdict.INCONCLUSIVE) continue
                    val latency = item.optLong("latencyMs", -1L)
                    val at = item.optLong("measuredAtMs", -1L)
                    if (latency < 0L || at < 0L) continue
                    add(DeploymentProbeMeasurement(profile, verdict, latency, at))
                }
            }.distinctBy { it.profile.id }
        }.getOrDefault(emptyList())
    }

    fun record(result: DeploymentProbeMeasurement, profiles: List<DeploymentProfile>) {
        if (result.verdict == DeploymentProbeVerdict.INCONCLUSIVE) return
        val current = read(profiles).filterNot { it.profile.id == result.profile.id }.toMutableList()
        current += result
        val array = JSONArray()
        current.sortedByDescending { it.measuredAtMs }.take(MAX_RESULTS).forEach {
            array.put(JSONObject()
                .put("id", it.profile.id)
                .put("verdict", it.verdict.name)
                .put("latencyMs", it.latencyMs)
                .put("measuredAtMs", it.measuredAtMs))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    fun prune(profiles: List<DeploymentProfile>) {
        val known = profiles.map { it.id }.toSet()
        val kept = read(profiles).filter { it.profile.id in known }
        preferences.edit().remove(KEY).apply()
        kept.forEach { record(it, profiles) }
    }

    private companion object {
        const val KEY = "deployment_measurements_v1"
        const val MAX_RESULTS = 24
    }
}
