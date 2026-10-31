package com.moveinsync.roster.dto.response;

import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationJobResponseDto {

    private UUID jobId;
    private String jobReference;
    private String fileName;
    private ReconciliationJobStatus status;

    // Diff Statistics
    private int totalRecords;
    private int unchangedRecords;
    private int updatedRecords;
    private int createdRecords;

    // Safety Brake Data
    private int activeDbCount;
    private int fileEmployeeCount;
    private int missingCount;
    private BigDecimal missingRatio;
    private BigDecimal safetyBrakeThreshold;
    private boolean safetyBrakeTriggered;

    // Operator Approval
    private String approvedBy;
    private String operatorNotes;
    private Instant approvedAt;

    // Execution Timing
    private Long executionDurationMs;
    private Instant startedAt;
    private Instant completedAt;
}
