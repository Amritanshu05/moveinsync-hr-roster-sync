package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.repository.projection.EmployeeHashProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByTenantIdAndEmployeeCode(UUID tenantId, String employeeCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId AND e.employeeCode = :employeeCode")
    Optional<Employee> findForUpdateByTenantIdAndEmployeeCode(@Param("tenantId") UUID tenantId,
                                                             @Param("employeeCode") String employeeCode);

    int countByTenantIdAndStatus(UUID tenantId, EmployeeStatus status);

    @Query("SELECT e.employeeCode AS employeeCode, e.recordHash AS recordHash, e.sourceVersion AS sourceVersion " +
           "FROM Employee e WHERE e.tenantId = :tenantId AND e.status = :status")
    List<EmployeeHashProjection> findHashProjectionsByTenantIdAndStatus(@Param("tenantId") UUID tenantId,
                                                                      @Param("status") EmployeeStatus status);

    @Query("SELECT e.employeeCode AS employeeCode, e.recordHash AS recordHash, e.sourceVersion AS sourceVersion " +
           "FROM Employee e WHERE e.tenantId = :tenantId AND e.status = :status")
    Stream<EmployeeHashProjection> streamHashProjectionsByTenantIdAndStatus(@Param("tenantId") UUID tenantId,
                                                                            @Param("status") EmployeeStatus status);

    @Modifying
    @Query("UPDATE Employee e SET e.status = :newStatus, e.updatedAt = :now " +
           "WHERE e.tenantId = :tenantId AND e.employeeCode IN :employeeCodes")
    int updateStatusForEmployeeCodes(@Param("tenantId") UUID tenantId,
                                     @Param("employeeCodes") Collection<String> employeeCodes,
                                     @Param("newStatus") EmployeeStatus newStatus,
                                     @Param("now") Instant now);
}
