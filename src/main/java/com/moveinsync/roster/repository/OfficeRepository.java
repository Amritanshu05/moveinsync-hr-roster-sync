package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Office;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface OfficeRepository extends JpaRepository<Office, UUID> {

    Optional<Office> findByTenantIdAndOfficeCode(UUID tenantId, String officeCode);

    @Query("SELECT o.officeCode FROM Office o WHERE o.tenantId = :tenantId AND o.active = true")
    Set<String> findActiveOfficeCodesByTenantId(@Param("tenantId") UUID tenantId);
}
