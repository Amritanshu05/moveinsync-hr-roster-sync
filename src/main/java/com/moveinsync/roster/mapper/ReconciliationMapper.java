package com.moveinsync.roster.mapper;

import com.moveinsync.roster.domain.entity.ReconciliationError;
import com.moveinsync.roster.domain.entity.ReconciliationJob;
import com.moveinsync.roster.dto.response.ReconciliationErrorResponseDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationMapper {

    public ReconciliationJobResponseDto toResponseDto(ReconciliationJob job) {
        return ReconciliationJobResponseDto.builder()
                .jobId(job.getId())
                .jobReference(job.getJobReference())
                .fileName(job.getFileName())
                .status(job.getStatus())
                .totalRecords(job.getTotalRecords())
                .unchangedRecords(job.getUnchangedRecords())
                .updatedRecords(job.getUpdatedRecords())
                .createdRecords(job.getCreatedRecords())
                .activeDbCount(job.getActiveDbCount())
                .fileEmployeeCount(job.getFileEmployeeCount())
                .missingCount(job.getMissingCount())
                .missingRatio(job.getMissingRatio())
                .safetyBrakeThreshold(job.getSafetyBrakeThreshold())
                .safetyBrakeTriggered(job.isSafetyBrakeTriggered())
                .approvedBy(job.getApprovedBy())
                .operatorNotes(job.getOperatorNotes())
                .approvedAt(job.getApprovedAt())
                .executionDurationMs(job.getExecutionDurationMs())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }

    public ReconciliationErrorResponseDto toErrorResponseDto(ReconciliationError error) {
        return ReconciliationErrorResponseDto.builder()
                .employeeCode(error.getEmployeeCode())
                .rowNumber(error.getRowNumber())
                .errorType(error.getErrorType())
                .field(error.getField())
                .errorCode(error.getErrorCode())
                .message(error.getMessage())
                .build();
    }
}
