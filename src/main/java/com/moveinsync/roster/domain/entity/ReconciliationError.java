package com.moveinsync.roster.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "reconciliation_errors", indexes = {
    @Index(name = "idx_rec_errors_job", columnList = "job_id")
})
public class ReconciliationError implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_code", nullable = false, length = 64)
    private String employeeCode;

    @Column(name = "row_number")
    private Integer rowNumber;

    @Column(name = "error_type", nullable = false, length = 32)
    private String errorType; // VALIDATION_ERROR, MISSING_RECORD_FLAGGED

    @Column(name = "field", length = 64)
    private String field;

    @Column(name = "error_code", nullable = false, length = 64)
    private String errorCode;

    @Column(name = "message", nullable = false, length = 255)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
