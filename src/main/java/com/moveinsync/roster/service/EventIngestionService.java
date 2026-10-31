package com.moveinsync.roster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.config.CacheConfig;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.EmployeeEvent;
import com.moveinsync.roster.domain.entity.IdempotencyRecord;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.RecordErrorDetail;
import com.moveinsync.roster.dto.response.RecordResultDto;
import com.moveinsync.roster.mapper.EmployeeMapper;
import com.moveinsync.roster.repository.EmployeeEventRepository;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.IdempotencyRecordRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.util.HashUtils;
import com.moveinsync.roster.validation.BoundaryValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeEventRepository employeeEventRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final BookingService bookingService;
    private final OfficeService officeService;
    private final EmployeeMapper employeeMapper;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final PlatformTransactionManager transactionManager;

    private final Counter eventsReceivedCounter;
    private final Counter eventsAppliedCounter;
    private final Counter eventsStaleCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsRejectedCounter;
    private final Timer eventProcessingTimer;

    /**
     * Ingests a batch of employee roster events.
     * Guarantees per-record validation and fault isolation: one invalid record never fails the batch.
     */
    public BatchIngestResponse ingestBatch(BatchIngestRequestDto batchRequest) {
        UUID tenantId = TenantContext.getTenantId();
        String tenantCode = TenantContext.getTenantCode();

        if (batchRequest == null || batchRequest.getEvents() == null || batchRequest.getEvents().isEmpty()) {
            return BatchIngestResponse.builder()
                    .tenantCode(tenantCode)
                    .totalReceived(0)
                    .build();
        }

        List<EmployeeEventDto> events = batchRequest.getEvents();
        eventsReceivedCounter.increment(events.size());

        Set<String> validOfficeCodes = officeService.getValidOfficeCodesForTenant(tenantId);
        BoundaryValidator validator = new BoundaryValidator(validOfficeCodes);

        List<RecordResultDto> results = new ArrayList<>();
        int appliedCount = 0;
        int staleCount = 0;
        int duplicateCount = 0;
        int rejectedCount = 0;

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        for (EmployeeEventDto event : events) {
            String empCode = event.getEmployeeCode() != null ? event.getEmployeeCode().trim() : "UNKNOWN";
            MDC.put("employeeId", empCode);

            // Step 1: Boundary Validation
            List<RecordErrorDetail> validationErrors = validator.validate(event);
            if (!validationErrors.isEmpty()) {
                rejectedCount++;
                eventsRejectedCounter.increment();
                log.warn("Boundary validation failed for employee: {} with {} error(s)", empCode, validationErrors.size());

                recordValidationFailure(tenantId, event, validationErrors);

                results.add(RecordResultDto.builder()
                        .employeeCode(empCode)
                        .outcome(ProcessingOutcome.VALIDATION_FAILED)
                        .appliedVersion(null)
                        .errors(validationErrors)
                        .build());
                continue;
            }

            // Step 2: Atomic Per-Record Processing in Independent Transaction
            RecordResultDto result = transactionTemplate.execute(status -> {
                try {
                    return processSingleEventInternal(tenantId, event);
                } catch (Exception ex) {
                    log.error("Transaction failure processing event for employee {}: {}", empCode, ex.getMessage(), ex);
                    status.setRollbackOnly();
                    return RecordResultDto.builder()
                            .employeeCode(empCode)
                            .outcome(ProcessingOutcome.VALIDATION_FAILED)
                            .errors(List.of(new RecordErrorDetail("general", "INTERNAL_PROCESSING_ERROR", "Failed to process record")))
                            .build();
                }
            });

            if (result != null) {
                results.add(result);
                switch (result.getOutcome()) {
                    case APPLIED -> appliedCount++;
                    case STALE_IGNORED -> staleCount++;
                    case DUPLICATE_NO_OP -> duplicateCount++;
                    case VALIDATION_FAILED -> rejectedCount++;
                }
            }
        }

        return BatchIngestResponse.builder()
                .tenantCode(tenantCode)
                .totalReceived(events.size())
                .appliedCount(appliedCount)
                .staleCount(staleCount)
                .duplicateCount(duplicateCount)
                .rejectedCount(rejectedCount)
                .results(results)
                .build();
    }

    /**
     * Ingests a single employee event.
     */
    public RecordResultDto ingestSingle(EmployeeEventDto event) {
        BatchIngestResponse response = ingestBatch(BatchIngestRequestDto.builder()
                .events(List.of(event))
                .build());
        return response.getResults().isEmpty() ? null : response.getResults().get(0);
    }

    private RecordResultDto processSingleEventInternal(UUID tenantId, EmployeeEventDto event) {
        return eventProcessingTimer.record(() -> {
            String employeeCode = event.getEmployeeCode().trim();
            long incomingVersion = event.getSourceVersion();
            String rawPayload = serializePayload(event);

            // 1. Idempotency Check via IdempotencyRecord
            String idempotencyKey = String.format("%s:%s:%d", tenantId, employeeCode, incomingVersion);
            String requestHash = HashUtils.calculateSha256(rawPayload);

            Optional<IdempotencyRecord> existingIdempotency = idempotencyRecordRepository
                    .findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);

            if (existingIdempotency.isPresent()) {
                eventsDuplicateCounter.increment();
                log.info("Duplicate event detected via idempotency key: {}. Handled as idempotent no-op.", idempotencyKey);
                saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.DUPLICATE_NO_OP, null);
                return RecordResultDto.builder()
                        .employeeCode(employeeCode)
                        .outcome(ProcessingOutcome.DUPLICATE_NO_OP)
                        .appliedVersion(incomingVersion)
                        .build();
            }

            // 2. Pessimistic Row Lock for Concurrent Ordering Serialization
            Optional<Employee> employeeOpt = employeeRepository
                    .findForUpdateByTenantIdAndEmployeeCode(tenantId, employeeCode);

            if (employeeOpt.isPresent()) {
                Employee employee = employeeOpt.get();
                long currentVersion = employee.getSourceVersion();

                // Check Per-Employee Version Order
                if (incomingVersion < currentVersion) {
                    // Stale version: strictly ignore
                    eventsStaleCounter.increment();
                    log.info("Stale event ignored for employee {}: incoming version {} < current version {}",
                            employeeCode, incomingVersion, currentVersion);

                    recordIdempotency(tenantId, idempotencyKey, employeeCode, incomingVersion, requestHash, "STALE_IGNORED");
                    saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.STALE_IGNORED, null);

                    return RecordResultDto.builder()
                            .employeeCode(employeeCode)
                            .outcome(ProcessingOutcome.STALE_IGNORED)
                            .appliedVersion(currentVersion)
                            .build();
                }

                if (incomingVersion == currentVersion) {
                    // Duplicate version: no-op
                    eventsDuplicateCounter.increment();
                    log.info("Duplicate version event for employee {}: version {}", employeeCode, incomingVersion);

                    recordIdempotency(tenantId, idempotencyKey, employeeCode, incomingVersion, requestHash, "DUPLICATE_NO_OP");
                    saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.DUPLICATE_NO_OP, null);

                    return RecordResultDto.builder()
                            .employeeCode(employeeCode)
                            .outcome(ProcessingOutcome.DUPLICATE_NO_OP)
                            .appliedVersion(currentVersion)
                            .build();
                }

                // incomingVersion > currentVersion: Valid Update / Deactivation
                String eventType = "DEACTIVATE".equalsIgnoreCase(event.getEventType()) ? "DEACTIVATE" : "UPDATE";

                if ("DEACTIVATE".equals(eventType)) {
                    // Deactivation side effects: Cancel future cab bookings and free seats
                    bookingService.cancelFutureBookingsAndFreeSeats(tenantId, employeeCode, "Employee Deactivation (HR Event)");
                }

                employeeMapper.updateHrFields(employee, event);
                employeeRepository.save(employee);

                createOutboxEvent(tenantId, employee, "EMPLOYEE_" + eventType);
                recordIdempotency(tenantId, idempotencyKey, employeeCode, incomingVersion, requestHash, "APPLIED");
                saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.APPLIED, null);
                evictEmployeeCache(tenantId, employeeCode);

                eventsAppliedCounter.increment();
                log.info("Successfully applied {} event for employee {}: version transitioned {} -> {}",
                        eventType, employeeCode, currentVersion, incomingVersion);

                return RecordResultDto.builder()
                        .employeeCode(employeeCode)
                        .outcome(ProcessingOutcome.APPLIED)
                        .appliedVersion(incomingVersion)
                        .build();

            } else {
                // Employee does not exist
                if ("DEACTIVATE".equalsIgnoreCase(event.getEventType())) {
                    log.info("DEACTIVATE received for non-existent employee {}. Recorded as no-op.", employeeCode);
                    recordIdempotency(tenantId, idempotencyKey, employeeCode, incomingVersion, requestHash, "IGNORED_STALE");
                    saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.STALE_IGNORED, "Employee not found for deactivation");
                    return RecordResultDto.builder()
                            .employeeCode(employeeCode)
                            .outcome(ProcessingOutcome.STALE_IGNORED)
                            .appliedVersion(null)
                            .build();
                }

                // New Joiner CREATE
                Employee newEmployee = employeeMapper.toEntity(event, tenantId);
                employeeRepository.save(newEmployee);

                createOutboxEvent(tenantId, newEmployee, "EMPLOYEE_CREATED");
                recordIdempotency(tenantId, idempotencyKey, employeeCode, incomingVersion, requestHash, "APPLIED");
                saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.APPLIED, null);
                evictEmployeeCache(tenantId, employeeCode);

                eventsAppliedCounter.increment();
                log.info("Successfully created new employee {}: version {}", employeeCode, incomingVersion);

                return RecordResultDto.builder()
                        .employeeCode(employeeCode)
                        .outcome(ProcessingOutcome.APPLIED)
                        .appliedVersion(incomingVersion)
                        .build();
            }
        });
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
            log.error("Failed to generate outbox event for employee: {}", employee.getEmployeeCode(), ex);
            throw new IllegalStateException("Outbox event creation failed", ex);
        }
    }

    private void recordIdempotency(UUID tenantId, String key, String employeeCode, long version, String hash, String status) {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .tenantId(tenantId)
                .idempotencyKey(key)
                .targetEntityId(employeeCode)
                .sourceVersion(version)
                .requestHash(hash)
                .responseStatus(status)
                .build();
        idempotencyRecordRepository.save(record);
    }

    private void saveEmployeeEventAudit(UUID tenantId, EmployeeEventDto event, String rawPayload,
                                        ProcessingOutcome outcome, String errorMessage) {
        EmployeeEvent audit = EmployeeEvent.builder()
                .tenantId(tenantId)
                .eventId(event.getEventId() != null ? event.getEventId() : UUID.randomUUID().toString())
                .employeeCode(event.getEmployeeCode() != null ? event.getEmployeeCode() : "UNKNOWN")
                .eventType(event.getEventType() != null ? event.getEventType() : "UNKNOWN")
                .sourceVersion(event.getSourceVersion() != null ? event.getSourceVersion() : 0L)
                .sourceTimestamp(event.getSourceTimestamp() != null ? event.getSourceTimestamp() : Instant.now())
                .rawPayload(rawPayload != null ? rawPayload : "{}")
                .processed(true)
                .processingOutcome(outcome)
                .errorMessage(errorMessage)
                .build();
        employeeEventRepository.save(audit);
    }

    private void recordValidationFailure(UUID tenantId, EmployeeEventDto event, List<RecordErrorDetail> errors) {
        String rawPayload = serializePayload(event);
        String errorMsg = errors.stream()
                .map(e -> e.getField() + ": " + e.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        saveEmployeeEventAudit(tenantId, event, rawPayload, ProcessingOutcome.VALIDATION_FAILED, errorMsg);
    }

    private void evictEmployeeCache(UUID tenantId, String employeeCode) {
        Cache cache = cacheManager.getCache(CacheConfig.EMPLOYEE_CACHE);
        if (cache != null) {
            String cacheKey = tenantId + ":" + employeeCode;
            cache.evict(cacheKey);
            log.debug("Evicted employee cache for key: {}", cacheKey);
        }
    }

    private String serializePayload(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
