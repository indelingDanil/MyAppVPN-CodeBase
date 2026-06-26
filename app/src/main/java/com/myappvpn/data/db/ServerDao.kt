package com.myappvpn.data.db

import androidx.room.*
import com.myappvpn.data.model.ServerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY CASE WHEN latencyMs = -1 THEN 99999 WHEN latencyMs = 0 THEN 88888 ELSE latencyMs END ASC")
    fun getAllServers(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedServer(): ServerProfile?

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: String): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerProfile>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerProfile)

    @Query("UPDATE servers SET latencyMs = :latencyMs WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Int)

    @Query("UPDATE servers SET speedMbps = :speedMbps WHERE id = :id")
    suspend fun updateSpeed(id: String, speedMbps: Float)

    @Query("UPDATE servers SET successCount = successCount + 1 WHERE id = :id")
    suspend fun incrementSuccess(id: String)

    @Query("UPDATE servers SET failCount = failCount + 1 WHERE id = :id")
    suspend fun incrementFail(id: String)

    @Query("UPDATE servers SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE servers SET isSelected = 1 WHERE id = :id")
    suspend fun selectServer(id: String)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()

    @Query("DELETE FROM servers WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun getCount(): Int

    @Query("SELECT * FROM servers WHERE country = :country ORDER BY latencyMs ASC")
    fun getServersByCountry(country: String): Flow<List<ServerProfile>>

    // v2.0: multi-stage validation queries

    @Query("UPDATE servers SET medianLatencyMs = :median, pingCount = :count, latencyMs = :median, validationState = CASE WHEN :median > 0 THEN 'REACHABLE' ELSE validationState END WHERE id = :id")
    suspend fun updatePingResult(id: String, median: Int, count: Int)

    @Query("UPDATE servers SET handshakeOk = :ok, validationState = CASE WHEN :ok = 1 THEN 'HANDSHAKE_OK' ELSE validationState END WHERE id = :id")
    suspend fun updateHandshakeResult(id: String, ok: Boolean)

    @Query("UPDATE servers SET validationState = 'VERIFIED', verifiedAt = :time, lastValidatedAt = :time, consecutiveFailures = 0 WHERE id = :id")
    suspend fun markVerified(id: String, time: Long)

    // Clears the "verified working" stamp so the server drops out of the 24h working pool —
    // used when a previously-working route actually fails.
    @Query("UPDATE servers SET verifiedAt = 0, validationState = CASE WHEN validationState = 'VERIFIED' THEN 'STALE' ELSE validationState END WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("UPDATE servers SET consecutiveFailures = consecutiveFailures + 1, validationState = CASE WHEN consecutiveFailures >= 2 THEN 'STALE' ELSE validationState END WHERE id = :id")
    suspend fun incrementConsecutiveFailure(id: String)

    @Query("UPDATE servers SET consecutiveFailures = 0 WHERE id = :id")
    suspend fun resetConsecutiveFailures(id: String)

    @Query("SELECT * FROM servers WHERE validationState = 'VERIFIED' AND verifiedAt > :since ORDER BY medianLatencyMs ASC")
    suspend fun getRecentlyVerifiedServers(since: Long): List<ServerProfile>
}
