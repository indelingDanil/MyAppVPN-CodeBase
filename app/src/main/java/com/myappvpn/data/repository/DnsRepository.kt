package com.myappvpn.data.repository

import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.DnsPreset
import com.myappvpn.data.model.DnsPresets
import com.myappvpn.data.model.DnsServer
import com.myappvpn.data.model.EffectiveDns
import com.myappvpn.data.model.XrayDnsEntry
import com.myappvpn.util.DnsConfigInjector
import com.myappvpn.util.DnsValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User DNS servers for Xray's internal resolver (Stage 3b).
 *
 * Stored as a JSON array in [AppPreferences.KEY_DNS_SERVERS]; order matters (Xray tries in order).
 * Limits: [MAX_TOTAL] entries, at most [MAX_ENABLED] enabled.
 * Empty list / nothing enabled → default Cloudflare + Google DoH (identical to the pre-3b config).
 *
 * Every mutation is a single read-modify-write inside one dataStore.edit (no lost updates).
 */
@Singleton
class DnsRepository @Inject constructor(
    private val preferences: AppPreferences
) {
    companion object {
        const val MAX_TOTAL = 6
        const val MAX_ENABLED = 4

        /** Short human summary of the enabled servers, for the Settings row. */
        fun summarize(list: List<DnsServer>): String {
            val enabled = list.filter { it.enabled }
            return if (enabled.isEmpty()) DnsPresets.DEFAULT_SUMMARY
            else enabled.joinToString(", ") { it.label }
        }
    }

    sealed class AddResult {
        /** [enabled] = false when it was added disabled because [MAX_ENABLED] were already on. */
        data class Added(val enabled: Boolean, val warning: String?) : AddResult()
        data class Failed(val message: String) : AddResult()
    }

    val servers: Flow<List<DnsServer>> = preferences.dnsServersJson
        .map { decode(it) }
        .distinctUntilChanged()

    /** "UseIPv4" (default) or "UseIP". */
    val queryStrategy: Flow<String> = preferences.dnsQueryStrategy
        .map { normalizeStrategy(it) }
        .distinctUntilChanged()

    /** What the tunnel should use right now; emits only on real changes (for hot-apply). */
    val effective: Flow<EffectiveDns> = combine(servers, queryStrategy) { list, strategy ->
        computeEffective(list, strategy)
    }.distinctUntilChanged()

    // ── Mutations ───────────────────────────────────────────────────────────────

    suspend fun add(raw: String, label: String? = null): AddResult {
        val v = when (val r = DnsValidator.validate(raw)) {
            is DnsValidator.Result.Error -> return AddResult.Failed(r.message)
            is DnsValidator.Result.Ok -> r
        }
        var result: AddResult = AddResult.Failed("Не удалось сохранить")
        preferences.updateDnsServersJson { json ->
            val list = decode(json)
            when {
                list.any { it.id == v.canonical } -> {
                    result = AddResult.Failed("Этот сервер уже в списке")
                    null
                }
                list.size >= MAX_TOTAL -> {
                    result = AddResult.Failed("Максимум $MAX_TOTAL серверов — удалите лишний")
                    null
                }
                else -> {
                    val enable = list.count { it.enabled } < MAX_ENABLED
                    val name = label?.takeIf { it.isNotBlank() }
                        ?: DnsPresets.byAddress(v.canonical)?.label
                        ?: v.host
                    result = AddResult.Added(enable, v.warning)
                    encode(list + DnsServer(v.canonical, v.canonical, name, enable))
                }
            }
        }
        return result
    }

    suspend fun addPreset(preset: DnsPreset): AddResult = add(preset.address, preset.label)

    suspend fun remove(id: String) {
        preferences.updateDnsServersJson { json ->
            val list = decode(json)
            if (list.none { it.id == id }) null else encode(list.filterNot { it.id == id })
        }
    }

    /** Returns an error message (limit reached) or null on success. */
    suspend fun setEnabled(id: String, enabled: Boolean): String? {
        var error: String? = null
        preferences.updateDnsServersJson { json ->
            val list = decode(json)
            val target = list.firstOrNull { it.id == id } ?: return@updateDnsServersJson null
            if (target.enabled == enabled) return@updateDnsServersJson null
            if (enabled && list.count { it.enabled } >= MAX_ENABLED) {
                error = "Можно включить не более $MAX_ENABLED серверов"
                return@updateDnsServersJson null
            }
            encode(list.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        return error
    }

    /** Moves the entry one position up ([up] = true) or down. No-op at the edges. */
    suspend fun move(id: String, up: Boolean) {
        preferences.updateDnsServersJson { json ->
            val list = decode(json).toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in list.indices) return@updateDnsServersJson null
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
            encode(list)
        }
    }

    suspend fun setQueryStrategy(strategy: String) {
        preferences.setDnsQueryStrategy(normalizeStrategy(strategy))
    }

    /** IPv6 answers (AAAA) on = "UseIP", off = "UseIPv4". */
    suspend fun setIpv6Enabled(enabled: Boolean) {
        setQueryStrategy(if (enabled) DnsConfigInjector.STRATEGY_IP else DnsConfigInjector.STRATEGY_IPV4)
    }

    /** Clears the list and the query strategy → default Cloudflare + Google DoH, UseIPv4. */
    suspend fun resetToDefault() {
        preferences.resetDnsSettings()
    }

    // ── Reads for the tunnel ────────────────────────────────────────────────────

    /** Ordered `dns.servers` entries the tunnel should use right now. */
    suspend fun effectiveServers(): List<XrayDnsEntry> = effectiveConfig().servers

    /** Full snapshot (servers + bootstrap hosts + queryStrategy) for [DnsConfigInjector.inject]. */
    suspend fun effectiveConfig(): EffectiveDns =
        computeEffective(servers.first(), queryStrategy.first())

    // ── Internals ───────────────────────────────────────────────────────────────

    /**
     * Enabled + still-valid entries in order. Empty → defaults.
     * Bootstrap: hostname-DoH presets (AdGuard) get `dns.hosts` entries. If EVERY enabled entry is a
     * hostname DoH without a bootstrap entry, Cloudflare DoH (IP) is appended as a last-resort
     * fallback so DNS can never end up with zero resolvable servers. (Normally hostname DoH works
     * anyway: the dns-internal routing rule sends the request to the proxy with the domain name and
     * the exit resolves it.)
     */
    private fun computeEffective(list: List<DnsServer>, strategy: String): EffectiveDns {
        val oks = list.filter { it.enabled }
            .mapNotNull { DnsValidator.validate(it.address) as? DnsValidator.Result.Ok }
            .ifEmpty {
                DnsPresets.DEFAULT_SERVERS.mapNotNull {
                    DnsValidator.validate(it) as? DnsValidator.Result.Ok
                }
            }

        val hosts = LinkedHashMap<String, List<String>>()
        oks.filter { !it.isIpLiteral }.forEach { ok ->
            DnsPresets.BOOTSTRAP_HOSTS[ok.host]?.let { hosts[ok.host] = it }
        }

        val entries = oks.map { it.xrayEntry }.toMutableList()
        val hasResolvable = oks.any { it.isIpLiteral || it.host in DnsPresets.BOOTSTRAP_HOSTS }
        if (!hasResolvable) {
            val fallback = XrayDnsEntry(DnsPresets.DEFAULT_SERVERS.first())
            if (fallback !in entries) entries.add(fallback)
        }
        return EffectiveDns(entries, hosts, strategy)
    }

    private fun normalizeStrategy(s: String): String =
        if (s == DnsConfigInjector.STRATEGY_IP) DnsConfigInjector.STRATEGY_IP
        else DnsConfigInjector.STRATEGY_IPV4

    private fun encode(list: List<DnsServer>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("address", s.address)
                put("label", s.label)
                put("enabled", s.enabled)
            })
        }
        return arr.toString()
    }

    private fun decode(json: String): List<DnsServer> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val address = o.optString("address").ifEmpty { return@mapNotNull null }
            DnsServer(
                id = o.optString("id").ifEmpty { address },
                address = address,
                label = o.optString("label").ifEmpty { address },
                enabled = o.optBoolean("enabled", true),
            )
        }.distinctBy { it.id }
    } catch (_: Exception) {
        emptyList()
    }
}
