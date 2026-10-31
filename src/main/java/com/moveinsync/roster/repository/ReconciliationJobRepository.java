package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ReconciliationJob;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationJobRepository extends JpaRepository<ReconciliationJob, UUID> {
    Optional<ReconciliationJob> findByTenantIdAndJobReference(UUID tenantId, String jobReference);
    Optional<ReconciliationJob> findFirstByTenantIdOrderByStartedAtDesc(UUID tenantId);
    List<ReconciliationJob> findByTenantIdAndStatus(UUID tenantId, ReconciliationJobStatus status);
}
