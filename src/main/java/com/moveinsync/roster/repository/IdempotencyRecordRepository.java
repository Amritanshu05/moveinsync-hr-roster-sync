package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    boolean existsByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Atomically inserts an idempotency record. If a record with (tenant_id, idempotency_key)
     * already exists (e.g. from a concurrent thread or previous request), DO NOTHING is performed.
     * Returns 1 if the record was inserted (winner), or 0 if conflict was detected (duplicate/loser).
     * Prevents unique constraint violation exceptions from marking the transaction rollback-only.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_records (id, tenant_id, idempotency_key, target_entity_id, source_version, request_hash, response_status, created_at) " +
                   "VALUES (gen_random_uuid(), :tenantId, :key, :entityId, :version, :hash, :status, CURRENT_TIMESTAMP) " +
                   "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(@Param("tenantId") UUID tenantId,
                          @Param("key") String key,
                          @Param("entityId") String entityId,
                          @Param("version") long version,
                          @Param("hash") String hash,
                          @Param("status") String status);

    @Modifying
    @Query("UPDATE IdempotencyRecord r SET r.responseStatus = :status WHERE r.tenantId = :tenantId AND r.idempotencyKey = :key")
    int updateResponseStatus(@Param("tenantId") UUID tenantId,
                             @Param("key") String key,
                             @Param("status") String status);
}
