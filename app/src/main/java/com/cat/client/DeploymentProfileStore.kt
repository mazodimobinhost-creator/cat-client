package com.cat.client

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class DeploymentProfileStore(private val preferences: SharedPreferences) {
    fun read(): List<DeploymentProfile> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val target = runCatching { DeploymentTarget.valueOf(item.optString("target")) }.getOrNull() ?: continue
                    DeploymentProfile(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        target = target,
                        baseUrl = item.optString("baseUrl"),
                        enabled = item.optBoolean("enabled", true),
                    ).normalized()?.let(::add)
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun write(profiles: List<DeploymentProfile>) {
        val valid = profiles.mapNotNull { it.normalized() }.distinctBy { it.id }.take(MAX_PROFILES)
        val array = JSONArray()
        valid.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("target", profile.target.name)
                .put("baseUrl", profile.baseUrl)
                .put("enabled", profile.enabled))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    fun upsert(profile: DeploymentProfile): Boolean {
        val valid = profile.normalized() ?: return false
        val next = read().filterNot { it.id == valid.id }.toMutableList().apply { add(valid) }
        write(next)
        return true
    }

    fun remove(id: String) = write(read().filterNot { it.id == id })

    private companion object {
        const val KEY = "deployment_profiles_v1"
        const val MAX_PROFILES = 24
    }
}
