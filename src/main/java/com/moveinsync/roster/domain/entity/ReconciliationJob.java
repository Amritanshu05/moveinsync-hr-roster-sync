package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "reconciliation_jobs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_rec_jobs_reference", columnNames = {"tenant_id", "job_reference"})
}, indexes = {
    @Index(name = "idx_rec_jobs_tenant_status", columnList = "tenant_id, status")
})
public class ReconciliationJob implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_reference", nullable = false, length = 64)
    private String jobReference;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private ReconciliationJobStatus status = ReconciliationJobStatus.IN_PROGRESS;

    @Builder.Default
    @Column(name = "total_records", nullable = false)
    private int totalRecords = 0;

    @Builder.Default
    @Column(name = "unchanged_records", nullable = false)
    private int unchangedRecords = 0;

    @Builder.Default
    @Column(name = "updated_records", nullable = false)
    private int updatedRecords = 0;

    @Builder.Default
    @Column(name = "created_records", nullable = false)
    private int createdRecords = 0;

    @Builder.Default
    @Column(name = "active_db_count", nullable = false)
    private int activeDbCount = 0;

    @Builder.Default
    @Column(name = "file_employee_count", nullable = false)
    private int fileEmployeeCount = 0;

    @Builder.Default
    @Column(name = "missing_count", nullable = false)
    private int missingCount = 0;

    @Builder.Default
    @Column(name = "missing_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal missingRatio = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "safety_brake_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal safetyBrakeThreshold = new BigDecimal("0.3000");

    @Builder.Default
    @Column(name = "safety_brake_triggered", nullable = false)
    private boolean safetyBrakeTriggered = false;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "operator_notes", columnDefinition = "TEXT")
    private String operatorNotes;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "execution_duration_ms")
    private Long executionDurationMs;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
