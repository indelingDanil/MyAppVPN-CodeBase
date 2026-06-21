package com.myappvpn.data.repository

import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.SubscriptionSource
import com.myappvpn.data.remote.SubscriptionUrls
import com.myappvpn.util.SubscriptionUrlValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the list of subscription sources: built-in (Universal, igareck) + custom (user-added).
 * Built-ins can be enabled/disabled but not deleted; custom ones can be added, toggled and deleted.
 *
 * Only ENABLED sources are fetched ([getEnabledChains]); disabled sources' servers are pruned by
 * ConfigRepository's existing `deleteNotIn` step on the next refresh.
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val preferences: AppPreferences
) {
    /** Reason string on failure, or null on success. */
    suspend fun addCustom(rawUrl: String): String? {
        val url = rawUrl.trim()
        when (val v = SubscriptionUrlValidator.validate(url)) {
            is SubscriptionUrlValidator.Result.Invalid -> return v.reason
            SubscriptionUrlValidator.Result.Valid -> {}
        }
        val current = readCustom()
        if (current.any { it.urls.firstOrNull() == url }) return "Эта подписка уже добавлена"
        val updated = current + SubscriptionSource(
            id = url,
            name = deriveName(url),
            urls = listOf(url),
            enabled = true,
            isBuiltin = false,
        )
        writeCustom(updated)
        return null
    }

    suspend fun removeCustom(id: String) {
        writeCustom(readCustom().filterNot { it.id == id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val builtin = SubscriptionUrls.BUILTIN_SOURCES.firstOrNull { it.id == id }
        if (builtin != null) {
            val disabled = preferences.disabledBuiltinSources.first().toMutableSet()
            if (enabled) disabled.remove(id) else disabled.add(id)
            preferences.setDisabledBuiltinSources(disabled)
        } else {
            writeCustom(readCustom().map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    /** All sources for the UI: built-ins (with current enabled state) followed by custom ones. */
    val sources: Flow<List<SubscriptionSource>> = combine(
        preferences.customSubscriptionsJson,
        preferences.disabledBuiltinSources,
    ) { customJson, disabledBuiltins ->
        val builtins = SubscriptionUrls.BUILTIN_SOURCES.map {
            it.copy(enabled = it.id !in disabledBuiltins)
        }
        builtins + decodeCustom(customJson)
    }

    /** Mirror chains of only the enabled sources. Custom URLs are SSRF-validated (invalid skipped). */
    suspend fun getEnabledChains(): List<List<String>> {
        val disabledBuiltins = preferences.disabledBuiltinSources.first()
        val builtinChains = SubscriptionUrls.BUILTIN_SOURCES
            .filter { it.id !in disabledBuiltins }
            .map { it.urls }
        val customChains = readCustom()
            .filter { it.enabled }
            .map { it.urls }
            .filter { chain -> chain.all { SubscriptionUrlValidator.isValid(it) } }
        return builtinChains + customChains
    }

    // ── Custom-subs JSON storage ─────────────────────────────────────────────────

    private suspend fun readCustom(): List<SubscriptionSource> =
        decodeCustom(preferences.customSubscriptionsJson.first())

    private suspend fun writeCustom(list: List<SubscriptionSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.urls.firstOrNull() ?: "")
                put("enabled", s.enabled)
            })
        }
        preferences.setCustomSubscriptionsJson(arr.toString())
    }

    private fun decodeCustom(json: String): List<SubscriptionSource> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").ifEmpty { return@mapNotNull null }
            SubscriptionSource(
                id = url,
                name = o.optString("name").ifEmpty { deriveName(url) },
                urls = listOf(url),
                enabled = o.optBoolean("enabled", true),
                isBuiltin = false,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun deriveName(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}
