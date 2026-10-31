package com.moveinsync.roster.service;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ReconciliationError;
import com.moveinsync.roster.domain.entity.ReconciliationJob;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.dto.request.ReconciliationApprovalDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.exception.BusinessException;
import com.moveinsync.roster.exception.EntityNotFoundException;
import com.moveinsync.roster.exception.SafetyBrakeTriggeredException;
import com.moveinsync.roster.mapper.EmployeeMapper;
import com.moveinsync.roster.mapper.ReconciliationMapper;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.ReconciliationErrorRepository;
import com.moveinsync.roster.repository.ReconciliationJobRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.util.HashUtils;
import com.moveinsync.roster.validation.BoundaryValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Nightly Reconciliation Service implementing an efficient streaming diff algorithm.
 *
 * <h3>Algorithm Overview</h3>
 * <ol>
 *   <li><b>Phase 1 - Load DB Hashes:</b> Stream all ACTIVE employee hash projections from the database
 *       into a {@code HashMap<employeeCode, recordHash>}. Memory is bounded to O(n) where n is the
 *       number of active employees (~200K entries ≈ 32MB for hash map).</li>
 *   <li><b>Phase 2 - Stream CSV Rows:</b> Stream CSV rows one-by-one using Jackson CSV streaming parser.
 *       For each row:
 *       <ul>
 *         <li>Compute the hash of the CSV row's HR fields.</li>
 *         <li>Look up the employee code in the DB hash map.</li>
 *         <li>If found and hash matches → unchanged (remove from map).</li>
 *         <li>If found and hash differs → updated (apply update, remove from map).</li>
 *         <li>If not found → new employee (create).</li>
 *       </ul>
 *   </li>
 *   <li><b>Phase 3 - Safety Brake Check:</b> After streaming, all remaining entries in the DB hash map
 *       represent employees present in the DB but missing from the file. If the missing ratio
 *       exceeds the configurable threshold (default 30%), trigger the safety brake, halt
 *       deactivation, and require operator approval.</li>
 *   <li><b>Phase 4 - Deactivation:</b> If the safety brake is NOT triggered (or the operator approves),
 *       batch-deactivate the missing employees in chunks.</li>
 * </ol>
 *
 * <h3>Complexity</h3>
 * <ul>
 *   <li>Time: O(n + m) where n = DB employees, m = CSV rows</li>
 *   <li>Space: O(n) for the DB hash map; CSV rows are streamed one at a time</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final EmployeeRepository employeeRepository;
    private final ReconciliationJobRepository reconciliationJobRepository;
    private final ReconciliationErrorRepository reconciliationErrorRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OfficeService officeService;
    private final BookingService bookingService;
    private final EmployeeMapper employeeMapper;
    private final ReconciliationMapper reconciliationMapper;
    private final ObjectMapper objectMapper;

    private final Counter safetyBrakeTriggeredCounter;
    private final Timer reconciliationTimer;

    @Value("${moveinsync.reconciliation.safety-brake-threshold:0.30}")
    private double safetyBrakeThreshold;

    @Value("${moveinsync.reconciliation.streaming-batch-size:2000}")
    private int streamingBatchSize;

    /**
     * Executes the nightly reconciliation diff for the current tenant.
     * Streams the CSV file row-by-row, compares against DB hashes, and produces a diff report.
     */
    @Transactional
    public ReconciliationJobResponseDto executeReconciliation(MultipartFile file) {
        UUID tenantId = TenantContext.getTenantId();
        String tenantCode = TenantContext.getTenantCode();
        long startTime = System.currentTimeMillis();

        String jobReference = "RECON_" + tenantCode + "_" + Instant.now().toEpochMilli();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.csv";

        log.info("Starting nightly reconciliation for tenant {}: file={}, jobRef={}", tenantCode, fileName, jobReference);

        // Initialize Reconciliation Job
        ReconciliationJob job = ReconciliationJob.builder()
                .jobReference(jobReference)
                .tenantId(tenantId)
                .fileName(fileName)
                .status(ReconciliationJobStatus.IN_PROGRESS)
                .safetyBrakeThreshold(BigDecimal.valueOf(safetyBrakeThreshold))
                .build();
        job = reconciliationJobRepository.save(job);

        try {
            // PHASE 1: Load DB active employee hashes into memory
            // Uses hash projection to minimize memory: only (employeeCode, recordHash) per entry
            Map<String, Long> dbHashMap = loadDbHashMap(tenantId);
            int activeDbCount = dbHashMap.size();
            job.setActiveDbCount(activeDbCount);
            log.info("Phase 1 complete: Loaded {} active employee hashes from DB for tenant {}", activeDbCount, tenantCode);

            // Prepare validation context
            Set<String> validOfficeCodes = officeService.getValidOfficeCodesForTenant(tenantId);
            BoundaryValidator validator = new BoundaryValidator(validOfficeCodes);

            // PHASE 2: Stream CSV and compute diff
            DiffResult diffResult = streamCsvAndDiff(file.getInputStream(), tenantId, job.getId(),
                    dbHashMap, validator);

            // Update job with diff statistics
            job.setTotalRecords(diffResult.totalFileRecords);
            job.setFileEmployeeCount(diffResult.totalFileRecords);
            job.setUnchangedRecords(diffResult.unchangedCount);
            job.setUpdatedRecords(diffResult.updatedCount);
            job.setCreatedRecords(diffResult.createdCount);
            log.info("Phase 2 complete: CSV streamed. total={}, unchanged={}, updated={}, created={}",
                    diffResult.totalFileRecords, diffResult.unchangedCount, diffResult.updatedCount, diffResult.createdCount);

            // PHASE 3: Safety Brake Check
            // After Phase 2, remaining entries in dbHashMap are employees in DB but missing from the file
            int missingCount = dbHashMap.size();
            job.setMissingCount(missingCount);

            BigDecimal missingRatio = activeDbCount > 0
                    ? BigDecimal.valueOf(missingCount).divide(BigDecimal.valueOf(activeDbCount), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            job.setMissingRatio(missingRatio);

            log.info("Phase 3: Safety brake check — missing={}, activeDb={}, ratio={}, threshold={}",
                    missingCount, activeDbCount, missingRatio, safetyBrakeThreshold);

            if (missingCount > 0 && missingRatio.doubleValue() >= safetyBrakeThreshold) {
                // SAFETY BRAKE TRIGGERED: Do not deactivate anyone
                job.setSafetyBrakeTriggered(true);
                job.setStatus(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED);
                safetyBrakeTriggeredCounter.increment();

                // Record each missing employee as a flagged reconciliation error
                recordMissingEmployees(job.getId(), tenantId, dbHashMap.keySet());

                long durationMs = System.currentTimeMillis() - startTime;
                job.setExecutionDurationMs(durationMs);
                job.setCompletedAt(Instant.now());
                reconciliationJobRepository.save(job);

                log.warn("SAFETY BRAKE TRIGGERED for tenant {}: {} employees ({} %) missing from export. " +
                                "Deactivation halted. Operator approval required. Job: {}",
                        tenantCode, missingCount, missingRatio.movePointRight(2), jobReference);

                return reconciliationMapper.toResponseDto(job);
            }

            // PHASE 4: Deactivate missing employees (no safety brake)
            if (!dbHashMap.isEmpty()) {
                batchDeactivateMissing(tenantId, dbHashMap.keySet(), "Nightly reconciliation: not in export file");
                recordMissingEmployees(job.getId(), tenantId, dbHashMap.keySet());
            }

            job.setStatus(ReconciliationJobStatus.COMPLETED);
            long durationMs = System.currentTimeMillis() - startTime;
            job.setExecutionDurationMs(durationMs);
            job.setCompletedAt(Instant.now());
            reconciliationJobRepository.save(job);

            log.info("Reconciliation COMPLETED for tenant {}: duration={}ms, total={}, unchanged={}, updated={}, created={}, missing={}",
                    tenantCode, durationMs, diffResult.totalFileRecords, diffResult.unchangedCount,
                    diffResult.updatedCount, diffResult.createdCount, missingCount);

            final ReconciliationJob completedJob = job;
            return reconciliationTimer.record(() -> reconciliationMapper.toResponseDto(completedJob));

        } catch (SafetyBrakeTriggeredException ex) {
            throw ex; // Re-throw, already handled
        } catch (Exception ex) {
            log.error("Reconciliation FAILED for tenant {}: {}", tenantCode, ex.getMessage(), ex);
            job.setStatus(ReconciliationJobStatus.FAILED);
            job.setExecutionDurationMs(System.currentTimeMillis() - startTime);
            job.setCompletedAt(Instant.now());
            reconciliationJobRepository.save(job);
            throw new BusinessException(
                    "Reconciliation failed: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "RECONCILIATION_FAILED"
            );
        }
    }

    /**
     * Loads all ACTIVE employee hash projections from the DB into a HashMap.
     * Memory-efficient: only stores (employeeCode → recordHash).
     * For 200,000 employees: ~32MB (key ≈ 40 bytes + hash ≈ 8 bytes per entry + HashMap overhead).
     */
    private Map<String, Long> loadDbHashMap(UUID tenantId) {
        var projections = employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE);
        Map<String, Long> hashMap = new HashMap<>(projections.size() * 4 / 3 + 1);
        for (var proj : projections) {
            hashMap.put(proj.getEmployeeCode(), proj.getRecordHash());
        }
        return hashMap;
    }

    /**
     * Streams CSV file row-by-row using Jackson CSV streaming parser.
     * Each row is validated, hashed, and compared against the DB hash map.
     *
     * Memory: O(1) per CSV row — only one row is in memory at a time.
     *
     * Expected CSV columns (header row required):
     * employee_code, full_name, email, phone_number, manager_employee_code, office_code, shift_code, status
     */
    private DiffResult streamCsvAndDiff(InputStream csvStream, UUID tenantId, UUID jobId,
                                         Map<String, Long> dbHashMap, BoundaryValidator validator) throws Exception {
        CsvMapper csvMapper = new CsvMapper();
        csvMapper.enable(CsvParser.Feature.TRIM_SPACES);
        CsvSchema schema = CsvSchema.emptySchema().withHeader();

        int totalFileRecords = 0;
        int unchangedCount = 0;
        int updatedCount = 0;
        int createdCount = 0;
        List<ReconciliationError> errorBatch = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8));
             MappingIterator<Map<String, String>> iterator = csvMapper
                     .readerFor(Map.class)
                     .with(schema)
                     .readValues(reader)) {

            while (iterator.hasNext()) {
                Map<String, String> row = iterator.next();
                totalFileRecords++;

                String employeeCode = getField(row, "employee_code");
                if (employeeCode == null || employeeCode.isBlank()) {
                    errorBatch.add(buildReconciliationError(jobId, tenantId, "UNKNOWN", totalFileRecords,
                            "VALIDATION_ERROR", "employee_code", "MISSING_EMPLOYEE_CODE",
                            "Row is missing employee_code"));
                    continue;
                }
                employeeCode = employeeCode.trim();

                String fullName = getField(row, "full_name");
                String email = getField(row, "email");
                String phoneNumber = getField(row, "phone_number");
                String managerCode = getField(row, "manager_employee_code");
                String officeCode = getField(row, "office_code");
                String shiftCode = getField(row, "shift_code");
                String statusStr = getField(row, "status");

                // Validate row fields using BoundaryValidator-compatible inline validation
                List<String> rowErrors = validateCsvRow(fullName, email, phoneNumber, officeCode, validator);
                if (!rowErrors.isEmpty()) {
                    for (String err : rowErrors) {
                        errorBatch.add(buildReconciliationError(jobId, tenantId, employeeCode, totalFileRecords,
                                "VALIDATION_ERROR", null, "CSV_ROW_VALIDATION", err));
                    }
                    // Still count it as a file record but skip diff processing
                    flushErrorBatchIfNeeded(errorBatch, false);
                    continue;
                }

                // Calculate HR record hash from CSV row
                String normalizedStatus = (statusStr != null && !statusStr.isBlank()) ? statusStr.trim().toUpperCase() : "ACTIVE";
                long csvRowHash = HashUtils.calculateHrRecordHash(
                        fullName, email, phoneNumber, managerCode,
                        officeCode != null ? officeCode.trim().toUpperCase() : "",
                        shiftCode != null ? shiftCode.trim().toUpperCase() : "",
                        normalizedStatus
                );

                // Compare with DB
                Long dbHash = dbHashMap.get(employeeCode);

                if (dbHash != null) {
                    // Employee exists in DB
                    dbHashMap.remove(employeeCode);

                    if (dbHash.equals(csvRowHash)) {
                        // UNCHANGED: hash matches, skip
                        unchangedCount++;
                    } else {
                        // UPDATED: hash differs, apply update via ingestion-style mapper
                        applyReconciliationUpdate(tenantId, employeeCode, row, csvRowHash);
                        updatedCount++;
                    }
                } else {
                    // NEW EMPLOYEE: not in DB
                    createReconciliationEmployee(tenantId, row, csvRowHash);
                    createdCount++;
                }

                // Periodically flush error batch
                flushErrorBatchIfNeeded(errorBatch, false);
            }
        }

        // Flush any remaining errors
        flushErrorBatchIfNeeded(errorBatch, true);

        return new DiffResult(totalFileRecords, unchangedCount, updatedCount, createdCount);
    }

    /**
     * Applies a reconciliation update to an existing employee using the CSV row data.
     * Only updates HR-owned fields; transport-admin fields are preserved.
     */
    private void applyReconciliationUpdate(UUID tenantId, String employeeCode,
                                            Map<String, String> row, long newHash) {
        Optional<Employee> employeeOpt = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, employeeCode);
        if (employeeOpt.isEmpty()) {
            log.warn("Reconciliation update: employee {} not found in DB despite hash map presence", employeeCode);
            return;
        }

        Employee employee = employeeOpt.get();

        // Apply only HR-owned fields
        String fullName = getField(row, "full_name");
        if (fullName != null && !fullName.isBlank()) employee.setFullName(fullName.trim());

        String email = getField(row, "email");
        if (email != null && !email.isBlank()) employee.setEmail(email.trim().toLowerCase());

        String phone = getField(row, "phone_number");
        if (phone != null && !phone.isBlank()) employee.setPhoneNumber(phone.trim());

        String manager = getField(row, "manager_employee_code");
        if (manager != null) employee.setManagerEmployeeCode(manager.trim());

        String office = getField(row, "office_code");
        if (office != null && !office.isBlank()) employee.setOfficeCode(office.trim().toUpperCase());

        String shift = getField(row, "shift_code");
        if (shift != null && !shift.isBlank()) employee.setShiftCode(shift.trim().toUpperCase());

        String status = getField(row, "status");
        if (status != null && !status.isBlank()) {
            try {
                employee.setStatus(EmployeeStatus.valueOf(status.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Keep existing status
            }
        }

        employee.setRecordHash(newHash);
        // Increment source version to mark reconciliation update
        employee.setSourceVersion(employee.getSourceVersion() + 1);
        employee.setSourceTimestamp(Instant.now());

        employeeRepository.save(employee);

        // Emit outbox event for downstream consistency
        createOutboxEvent(tenantId, employee, "EMPLOYEE_RECONCILIATION_UPDATED");
    }

    /**
     * Creates a new employee from a reconciliation CSV row (new joiner found in the nightly export).
     */
    private void createReconciliationEmployee(UUID tenantId, Map<String, String> row, long hash) {
        String statusStr = getField(row, "status");
        EmployeeStatus status = EmployeeStatus.ACTIVE;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = EmployeeStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        Employee employee = Employee.builder()
                .tenantId(tenantId)
                .employeeCode(getField(row, "employee_code").trim())
                .fullName(safeGet(row, "full_name"))
                .email(safeGet(row, "email").toLowerCase())
                .phoneNumber(safeGet(row, "phone_number"))
                .managerEmployeeCode(getField(row, "manager_employee_code"))
                .officeCode(safeGet(row, "office_code").toUpperCase())
                .shiftCode(safeGet(row, "shift_code").toUpperCase())
                .status(status)
                .sourceVersion(1L)
                .sourceTimestamp(Instant.now())
                .recordHash(hash)
                .build();

        employeeRepository.save(employee);
        createOutboxEvent(tenantId, employee, "EMPLOYEE_RECONCILIATION_CREATED");
    }

    /**
     * Batch deactivates employees who are in the DB but missing from the nightly export.
     * Processes in configurable chunks to avoid overwhelming the DB.
     */
    private void batchDeactivateMissing(UUID tenantId, Set<String> missingCodes, String reason) {
        List<String> codeList = new ArrayList<>(missingCodes);

        for (int i = 0; i < codeList.size(); i += streamingBatchSize) {
            int end = Math.min(i + streamingBatchSize, codeList.size());
            List<String> batch = codeList.subList(i, end);

            employeeRepository.updateStatusForEmployeeCodes(
                    tenantId, batch, EmployeeStatus.INACTIVE, Instant.now());

            log.info("Batch deactivated {} employees for tenant {} (batch {}/{})",
                    batch.size(), tenantId, (i / streamingBatchSize) + 1,
                    (int) Math.ceil((double) codeList.size() / streamingBatchSize));
        }

        // Cancel future bookings for deactivated employees
        for (String empCode : missingCodes) {
            try {
                bookingService.cancelFutureBookingsAndFreeSeats(tenantId, empCode, reason);
            } catch (Exception ex) {
                log.warn("Failed to cancel bookings for deactivated employee {}: {}", empCode, ex.getMessage());
            }
        }
    }

    /**
     * Retrieves the current job status for the most recent reconciliation job.
     */
    @Transactional(readOnly = true)
    public ReconciliationJobResponseDto getLatestJobStatus() {
        UUID tenantId = TenantContext.getTenantId();
        ReconciliationJob job = reconciliationJobRepository.findFirstByTenantIdOrderByStartedAtDesc(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("ReconciliationJob", "latest"));
        return reconciliationMapper.toResponseDto(job);
    }

    /**
     * Retrieves a specific reconciliation job by its reference.
     */
    @Transactional(readOnly = true)
    public ReconciliationJobResponseDto getJobByReference(String jobReference) {
        UUID tenantId = TenantContext.getTenantId();
        ReconciliationJob job = reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, jobReference)
                .orElseThrow(() -> new EntityNotFoundException("ReconciliationJob", jobReference));
        return reconciliationMapper.toResponseDto(job);
    }

    /**
     * Lists all reconciliation jobs awaiting operator approval (safety brake triggered).
     */
    @Transactional(readOnly = true)
    public List<ReconciliationJobResponseDto> listPendingApprovals() {
        UUID tenantId = TenantContext.getTenantId();
        List<ReconciliationJob> jobs = reconciliationJobRepository
                .findByTenantIdAndStatus(tenantId, ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED);
        return jobs.stream().map(reconciliationMapper::toResponseDto).toList();
    }

    /**
     * Operator approves or rejects a reconciliation job where the safety brake was triggered.
     *
     * <ul>
     *   <li><b>APPROVE:</b> Proceeds with deactivation of the missing employees.</li>
     *   <li><b>REJECT:</b> Cancels the reconciliation; no employees are deactivated.</li>
     * </ul>
     */
    @Transactional
    public ReconciliationJobResponseDto processApproval(String jobReference, ReconciliationApprovalDto approval, String operatorUsername) {
        UUID tenantId = TenantContext.getTenantId();

        ReconciliationJob job = reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, jobReference)
                .orElseThrow(() -> new EntityNotFoundException("ReconciliationJob", jobReference));

        if (job.getStatus() != ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED) {
            throw new BusinessException(
                    "Only jobs with SAFETY_BRAKE_TRIGGERED status can be approved/rejected. Current status: " + job.getStatus(),
                    HttpStatus.CONFLICT,
                    "INVALID_JOB_STATE"
            );
        }

        String action = approval.getAction().trim().toUpperCase();

        if ("APPROVE".equals(action)) {
            log.info("Operator {} APPROVED reconciliation job {} for tenant {}. Proceeding with deactivation of {} missing employees.",
                    operatorUsername, jobReference, TenantContext.getTenantCode(), job.getMissingCount());

            // Retrieve missing employee codes from reconciliation errors
            List<String> missingCodes = reconciliationErrorRepository
                    .findEmployeeCodesByJobIdAndErrorType(job.getId(), "MISSING_RECORD_FLAGGED");

            if (!missingCodes.isEmpty()) {
                batchDeactivateMissing(tenantId, new HashSet<>(missingCodes),
                        "Nightly reconciliation: approved by operator " + operatorUsername);
            }

            job.setStatus(ReconciliationJobStatus.APPROVED);
            job.setApprovedBy(operatorUsername);
            job.setOperatorNotes(approval.getOperatorNotes());
            job.setApprovedAt(Instant.now());

        } else if ("REJECT".equals(action)) {
            log.info("Operator {} REJECTED reconciliation job {} for tenant {}. No employees deactivated.",
                    operatorUsername, jobReference, TenantContext.getTenantCode());

            job.setStatus(ReconciliationJobStatus.REJECTED);
            job.setApprovedBy(operatorUsername);
            job.setOperatorNotes(approval.getOperatorNotes());
            job.setApprovedAt(Instant.now());

        } else {
            throw new BusinessException(
                    "Invalid action: must be APPROVE or REJECT",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTION"
            );
        }

        reconciliationJobRepository.save(job);
        return reconciliationMapper.toResponseDto(job);
    }

    /**
     * Retrieves reconciliation errors (validation and missing records) for a specific job.
     */
    @Transactional(readOnly = true)
    public List<ReconciliationError> getJobErrors(String jobReference) {
        UUID tenantId = TenantContext.getTenantId();
        ReconciliationJob job = reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, jobReference)
                .orElseThrow(() -> new EntityNotFoundException("ReconciliationJob", jobReference));
        return reconciliationErrorRepository.findByJobId(job.getId());
    }

    // ──────────────────────────────────────────────
    // Helper Methods
    // ──────────────────────────────────────────────

    private List<String> validateCsvRow(String fullName, String email, String phone,
                                         String officeCode, BoundaryValidator validator) {
        List<String> errors = new ArrayList<>();
        if (fullName == null || fullName.isBlank()) errors.add("Missing full_name");
        if (email == null || email.isBlank()) errors.add("Missing email");
        if (phone == null || phone.isBlank()) errors.add("Missing phone_number");
        if (officeCode == null || officeCode.isBlank()) errors.add("Missing office_code");
        return errors;
    }

    private void recordMissingEmployees(UUID jobId, UUID tenantId, Set<String> missingCodes) {
        List<ReconciliationError> errors = new ArrayList<>();
        for (String code : missingCodes) {
            errors.add(ReconciliationError.builder()
                    .jobId(jobId)
                    .tenantId(tenantId)
                    .employeeCode(code)
                    .errorType("MISSING_RECORD_FLAGGED")
                    .errorCode("NOT_IN_EXPORT")
                    .message("Active employee not found in nightly export file")
                    .build());

            if (errors.size() >= streamingBatchSize) {
                reconciliationErrorRepository.saveAll(errors);
                errors.clear();
            }
        }
        if (!errors.isEmpty()) {
            reconciliationErrorRepository.saveAll(errors);
        }
    }

    private ReconciliationError buildReconciliationError(UUID jobId, UUID tenantId, String employeeCode,
                                                          int rowNumber, String errorType, String field,
                                                          String errorCode, String message) {
        return ReconciliationError.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .employeeCode(employeeCode)
                .rowNumber(rowNumber)
                .errorType(errorType)
                .field(field)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    private void flushErrorBatchIfNeeded(List<ReconciliationError> errorBatch, boolean force) {
        if (force || errorBatch.size() >= streamingBatchSize) {
            if (!errorBatch.isEmpty()) {
                reconciliationErrorRepository.saveAll(errorBatch);
                errorBatch.clear();
            }
        }
    }

    private void createOutboxEvent(UUID tenantId, Employee employee, String eventType) {
        try {
            Map<String, Object> payloadMap = Map.of(
                    "employeeCode", employee.getEmployeeCode(),
                    "fullName", employee.getFullName(),
                    "officeCode", employee.getOfficeCode(),
                    "shiftCode", employee.getShiftCode(),
                    "status", employee.getStatus().name(),
                    "sourceVersion", employee.getSourceVersion(),
                    "timestamp", Instant.now().toString()
            );

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .tenantId(tenantId)
                    .aggregateType("EMPLOYEE")
                    .aggregateId(employee.getEmployeeCode())
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payloadMap))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .nextRetryAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to create outbox event for reconciliation employee: {}", employee.getEmployeeCode(), ex);
        }
    }

    private String getField(Map<String, String> row, String key) {
        String value = row.get(key);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    private String safeGet(Map<String, String> row, String key) {
        String value = getField(row, key);
        return value != null ? value : "";
    }

    /**
     * Immutable record holding the result of the CSV streaming diff.
     */
    record DiffResult(int totalFileRecords, int unchangedCount, int updatedCount, int createdCount) {}
}
