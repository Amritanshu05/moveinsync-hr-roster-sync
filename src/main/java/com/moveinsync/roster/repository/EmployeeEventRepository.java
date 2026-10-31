package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.EmployeeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeEventRepository extends JpaRepository<EmployeeEvent, UUID> {
    Optional<EmployeeEvent> findByTenantIdAndEventId(UUID tenantId, String eventId);
    List<EmployeeEvent> findByTenantIdAndEmployeeCodeOrderBySourceVersionAsc(UUID tenantId, String employeeCode);
}
