package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ReconciliationError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationErrorRepository extends JpaRepository<ReconciliationError, UUID> {

    List<ReconciliationError> findByJobId(UUID jobId);

    long countByJobId(UUID jobId);

    @Query("SELECT r.employeeCode FROM ReconciliationError r WHERE r.jobId = :jobId AND r.errorType = :errorType")
    List<String> findEmployeeCodesByJobIdAndErrorType(@Param("jobId") UUID jobId, @Param("errorType") String errorType);
}
