package com.myappvpn.data.db

import androidx.room.*
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import kotlinx.coroutines.flow.Flow

/** Stay well below SQLite's 999 bound-variable limit (Android 8–10). */
private const val SQL_CHUNK = 900

@Dao
abstract class ServerDao {

    @Query("SELECT * FROM servers ORDER BY CASE WHEN latencyMs = -1 THEN 99999 WHEN latencyMs = 0 THEN 88888 ELSE latencyMs END ASC")
    abstract fun getAllServers(): Flow<List<ServerProfile>>

    /** One-shot snapshot (used inside transactions). */
    @Query("SELECT * FROM servers")
    abstract suspend fun getAllServersOnce(): List<ServerProfile>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    abstract suspend fun getSelectedServer(): ServerProfile?

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    abstract suspend fun getServerById(id: String): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(servers: List<ServerProfile>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(server: ServerProfile)

    /** Inserts only rows whose id is new; existing rows (and their stats) are left untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAllIgnore(servers: List<ServerProfile>)

    /**
     * Refresh-time update of the STATIC (subscription-derived) columns only. Never touches
     * isSelected / latency / counters / validation columns, so concurrent stat writes survive.
     */
    @Query(
        "UPDATE servers SET name = :name, flag = :flag, country = :country, address = :address, " +
            "port = :port, vlessUri = :vlessUri, transport = :transport, security = :security, protocol = :protocol, " +
            "lastUpdated = :lastUpdated WHERE id = :id"
    )
    abstract suspend fun updateStaticColumns(
        id: String,
        name: String,
        flag: String,
        country: String,
        address: String,
        port: Int,
        vlessUri: String,
        transport: String,
        security: String,
        protocol: String,
        lastUpdated: Long,
    )

    @Query("DELETE FROM servers WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /**
     * Atomically apply a subscription refresh:
     *  1. existing ids → update static columns only (stats / pin / validation untouched);
     *  2. new ids → INSERT IGNORE; if a row with the OLD (legacy, raw-URI md5) id has the same
     *     normalized identity (VlessUriParser.stableId(stored vlessUri) == new id), its stats and pin
     *     are carried over to the new row and the old row is deleted (one-time id migration);
     *  3. rows not present in [fresh] are deleted in chunks (≤[SQL_CHUNK] bound variables).
     *
     * All reads happen INSIDE the transaction, so no concurrent stat write can be rolled back.
     */
    @Transaction
    open suspend fun applyRefresh(fresh: List<ServerProfile>) {
        val existing = getAllServersOnce()
        val existingIds = existing.mapTo(HashSet(existing.size)) { it.id }
        val freshIds = fresh.mapTo(HashSet(fresh.size)) { it.id }

        // Rows that will disappear under their current id, grouped by their normalized identity.
        val legacyByIdentity: Map<String, List<ServerProfile>> =
            existing.filter { it.id !in freshIds }.groupBy { VlessUriParser.stableId(it.vlessUri) }

        val toInsert = ArrayList<ServerProfile>()
        for (f in fresh) {
            if (f.id in existingIds) {
                updateStaticColumns(
                    id = f.id, name = f.name, flag = f.flag, country = f.country,
                    address = f.address, port = f.port, vlessUri = f.vlessUri,
                    transport = f.transport, security = f.security, protocol = f.protocol,
                    lastUpdated = f.lastUpdated,
                )
            } else {
                val legacy = legacyByIdentity[f.id]
                    ?.sortedWith(
                        compareByDescending<ServerProfile> { it.isSelected }
                            .thenByDescending { it.verifiedAt }
                            .thenByDescending { it.successCount }
                    )
                    ?.firstOrNull()
                toInsert += if (legacy != null) f.withStatsFrom(legacy) else f
            }
        }
        if (toInsert.isNotEmpty()) toInsert.chunked(SQL_CHUNK).forEach { insertAllIgnore(it) }

        // Stale rows (incl. migrated legacy ids) — chunked to respect the bind-variable limit.
        val toDelete = existing.asSequence().map { it.id }.filter { it !in freshIds }.toList()
        toDelete.chunked(SQL_CHUNK).forEach { deleteByIds(it) }
    }

    @Query("UPDATE servers SET latencyMs = :latencyMs WHERE id = :id")
    abstract suspend fun updateLatency(id: String, latencyMs: Int)

    @Query("UPDATE servers SET speedMbps = :speedMbps WHERE id = :id")
    abstract suspend fun updateSpeed(id: String, speedMbps: Float)

    @Query("UPDATE servers SET successCount = successCount + 1 WHERE id = :id")
    abstract suspend fun incrementSuccess(id: String)

    @Query("UPDATE servers SET failCount = failCount + 1 WHERE id = :id")
    abstract suspend fun incrementFail(id: String)

    @Query("UPDATE servers SET isSelected = 0")
    abstract suspend fun clearSelection()

    @Query("UPDATE servers SET isSelected = 1 WHERE id = :id")
    abstract suspend fun selectServer(id: String)

    /** Atomically pin exactly one server (single statement → never "no pin" / "two pins"). */
    @Query("UPDATE servers SET isSelected = CASE WHEN id = :id THEN 1 ELSE 0 END")
    abstract suspend fun selectOnly(id: String)

    /** The pinned (manual-mode) server, observed. */
    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    abstract fun observeSelectedServer(): Flow<ServerProfile?>

    @Query("DELETE FROM servers")
    abstract suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM servers")
    abstract suspend fun getCount(): Int

    @Query("SELECT * FROM servers WHERE country = :country ORDER BY latencyMs ASC")
    abstract fun getServersByCountry(country: String): Flow<List<ServerProfile>>

    // v2.0: multi-stage validation queries

    /**
     * Success (median > 0): never downgrade VERIFIED / HANDSHAKE_OK to REACHABLE.
     * Failure (median == 0): the route is not working right now → leave the 24h working pool
     * (verifiedAt = 0) and mark a previously VERIFIED server STALE.
     */
    @Query(
        "UPDATE servers SET medianLatencyMs = :median, pingCount = :count, latencyMs = :median, " +
            "validationState = CASE " +
            "WHEN :median > 0 THEN (CASE WHEN validationState IN ('VERIFIED', 'HANDSHAKE_OK') THEN validationState ELSE 'REACHABLE' END) " +
            "WHEN validationState = 'VERIFIED' THEN 'STALE' " +
            "ELSE validationState END, " +
            "verifiedAt = CASE WHEN :median > 0 THEN verifiedAt ELSE 0 END " +
            "WHERE id = :id"
    )
    abstract suspend fun updatePingResult(id: String, median: Int, count: Int)

    @Query("UPDATE servers SET handshakeOk = :ok, validationState = CASE WHEN :ok = 1 THEN 'HANDSHAKE_OK' ELSE validationState END WHERE id = :id")
    abstract suspend fun updateHandshakeResult(id: String, ok: Boolean)

    @Query("UPDATE servers SET validationState = 'VERIFIED', verifiedAt = :time, lastValidatedAt = :time, consecutiveFailures = 0 WHERE id = :id")
    abstract suspend fun markVerified(id: String, time: Long)

    // Clears the "verified working" stamp so the server drops out of the 24h working pool —
    // used when a previously-working route actually fails.
    @Query("UPDATE servers SET verifiedAt = 0, validationState = CASE WHEN validationState = 'VERIFIED' THEN 'STALE' ELSE validationState END WHERE id = :id")
    abstract suspend fun markFailed(id: String)

    /** 3rd consecutive failure → STALE and out of the 24h working pool (verifiedAt = 0). */
    @Query(
        "UPDATE servers SET consecutiveFailures = consecutiveFailures + 1, " +
            "validationState = CASE WHEN consecutiveFailures >= 2 THEN 'STALE' ELSE validationState END, " +
            "verifiedAt = CASE WHEN consecutiveFailures >= 2 THEN 0 ELSE verifiedAt END " +
            "WHERE id = :id"
    )
    abstract suspend fun incrementConsecutiveFailure(id: String)

    @Query("UPDATE servers SET consecutiveFailures = 0 WHERE id = :id")
    abstract suspend fun resetConsecutiveFailures(id: String)

    @Query("SELECT * FROM servers WHERE validationState = 'VERIFIED' AND verifiedAt > :since ORDER BY medianLatencyMs ASC")
    abstract suspend fun getRecentlyVerifiedServers(since: Long): List<ServerProfile>
}

/** Fresh static data + learned stats/pin of [old] (used for the one-time id migration). */
private fun ServerProfile.withStatsFrom(old: ServerProfile): ServerProfile = copy(
    latencyMs = old.latencyMs,
    isSelected = old.isSelected,
    successCount = old.successCount,
    failCount = old.failCount,
    speedMbps = old.speedMbps,
    validationState = old.validationState,
    lastValidatedAt = old.lastValidatedAt,
    medianLatencyMs = old.medianLatencyMs,
    pingCount = old.pingCount,
    consecutiveFailures = old.consecutiveFailures,
    handshakeOk = old.handshakeOk,
    verifiedAt = old.verifiedAt,
)
