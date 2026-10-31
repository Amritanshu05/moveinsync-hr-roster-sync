package com.moveinsync.roster.controller;

import com.moveinsync.roster.dto.request.ReconciliationApprovalDto;
import com.moveinsync.roster.dto.response.ApiResponse;
import com.moveinsync.roster.dto.response.ReconciliationErrorResponseDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.mapper.ReconciliationMapper;
import com.moveinsync.roster.service.ReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller for the nightly reconciliation module.
 *
 * <h3>API Surface</h3>
 * <ul>
 *   <li>POST  /api/v1/reconciliation/upload       — Upload CSV, start reconciliation diff</li>
 *   <li>GET   /api/v1/reconciliation/jobs/latest  — Get the latest reconciliation job</li>
 *   <li>GET   /api/v1/reconciliation/jobs/{ref}   — Get a specific job by reference</li>
 *   <li>GET   /api/v1/reconciliation/jobs/{ref}/errors — List errors for a job</li>
 *   <li>GET   /api/v1/reconciliation/approvals    — List jobs awaiting approval</li>
 *   <li>POST  /api/v1/reconciliation/batches/{ref}/approve — Approve safety-brake job</li>
 *   <li>POST  /api/v1/reconciliation/batches/{ref}/reject  — Reject safety-brake job</li>
 * </ul>
 *
 * All endpoints require ROLE_OPERATOR authority.
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_OPERATOR', 'ROLE_ADMIN')")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationMapper reconciliationMapper;

    /**
     * Accepts a multipart CSV file upload and initiates the nightly reconciliation diff.
     * The CSV is streamed row-by-row to avoid loading 200K rows into heap memory.
     *
     * <p>Returns immediately once the diff is complete with a full summary (created,
     * updated, unchanged, missing). If the safety brake fires, the job is paused
     * and the caller must review/approve via the approval endpoint.</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ReconciliationJobResponseDto>> uploadAndReconcile(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Uploaded CSV file is empty", "EMPTY_FILE"));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Only CSV files are accepted", "INVALID_FILE_TYPE"));
        }

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);
        String message = result.isSafetyBrakeTriggered()
                ? String.format("Safety brake triggered: %d employees (%.2f%%) missing. Operator approval required.",
                        result.getMissingCount(), result.getMissingRatio().doubleValue() * 100)
                : String.format("Reconciliation completed: %d created, %d updated, %d unchanged, %d deactivated.",
                        result.getCreatedRecords(), result.getUpdatedRecords(),
                        result.getUnchangedRecords(), result.getMissingCount());

        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }

    /**
     * Returns the most recently started reconciliation job for the current tenant.
     */
    @GetMapping("/jobs/latest")
    public ResponseEntity<ApiResponse<ReconciliationJobResponseDto>> getLatestJob() {
        ReconciliationJobResponseDto result = reconciliationService.getLatestJobStatus();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Returns the reconciliation job identified by its job reference string.
     */
    @GetMapping("/jobs/{jobReference}")
    public ResponseEntity<ApiResponse<ReconciliationJobResponseDto>> getJobByReference(
            @PathVariable String jobReference) {
        ReconciliationJobResponseDto result = reconciliationService.getJobByReference(jobReference);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Returns the list of per-row validation errors and missing-record flags for a given job.
     * Useful for operator review before approving a safety-brake halt.
     */
    @GetMapping("/jobs/{jobReference}/errors")
    public ResponseEntity<ApiResponse<List<ReconciliationErrorResponseDto>>> getJobErrors(
            @PathVariable String jobReference) {
        var errors = reconciliationService.getJobErrors(jobReference);
        List<ReconciliationErrorResponseDto> dtos = errors.stream()
                .map(reconciliationMapper::toErrorResponseDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    /**
     * Lists all reconciliation jobs that have triggered the safety brake and
     * are awaiting operator approval. Operators can use this to see what needs review.
     */
    @GetMapping("/approvals")
    public ResponseEntity<ApiResponse<List<ReconciliationJobResponseDto>>> listPendingApprovals() {
        List<ReconciliationJobResponseDto> jobs = reconciliationService.listPendingApprovals();
        return ResponseEntity.ok(ApiResponse.ok(jobs));
    }

    /**
     * Operator approves a safety-brake-halted reconciliation job.
     * This triggers the deferred batch deactivation of missing employees.
     *
     * <p>The action field in the request body must be {@code APPROVE}.
     * Use the reject endpoint to cancel without deactivation.</p>
     */
    @PostMapping("/batches/{jobReference}/approve")
    public ResponseEntity<ApiResponse<ReconciliationJobResponseDto>> approveJob(
            @PathVariable String jobReference,
            @Valid @RequestBody ReconciliationApprovalDto approval,
            @AuthenticationPrincipal UserDetails userDetails) {

        // Force action to APPROVE regardless of body value — endpoint semantics are explicit
        ReconciliationApprovalDto approveAction = ReconciliationApprovalDto.builder()
                .action("APPROVE")
                .operatorNotes(approval.getOperatorNotes())
                .build();

        ReconciliationJobResponseDto result = reconciliationService.processApproval(
                jobReference, approveAction, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok(
                "Reconciliation approved. Missing employees will be deactivated.", result));
    }

    /**
     * Operator rejects a safety-brake-halted reconciliation job.
     * No employees are deactivated. All existing data is preserved.
     */
    @PostMapping("/batches/{jobReference}/reject")
    public ResponseEntity<ApiResponse<ReconciliationJobResponseDto>> rejectJob(
            @PathVariable String jobReference,
            @Valid @RequestBody ReconciliationApprovalDto approval,
            @AuthenticationPrincipal UserDetails userDetails) {

        ReconciliationApprovalDto rejectAction = ReconciliationApprovalDto.builder()
                .action("REJECT")
                .operatorNotes(approval.getOperatorNotes())
                .build();

        ReconciliationJobResponseDto result = reconciliationService.processApproval(
                jobReference, rejectAction, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok(
                "Reconciliation rejected. No employees were deactivated.", result));
    }
}
