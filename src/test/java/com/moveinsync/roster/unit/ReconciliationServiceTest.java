package com.moveinsync.roster.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ReconciliationJob;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.dto.request.ReconciliationApprovalDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.exception.BusinessException;
import com.moveinsync.roster.mapper.EmployeeMapper;
import com.moveinsync.roster.mapper.ReconciliationMapper;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.ReconciliationErrorRepository;
import com.moveinsync.roster.repository.ReconciliationJobRepository;
import com.moveinsync.roster.repository.projection.EmployeeHashProjection;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.OfficeService;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.util.HashUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Reconciliation Service Unit Tests")
class ReconciliationServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private ReconciliationJobRepository reconciliationJobRepository;
    @Mock private ReconciliationErrorRepository reconciliationErrorRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private OfficeService officeService;
    @Mock private BookingService bookingService;
    @Mock private Counter safetyBrakeTriggeredCounter;
    @Mock private Timer reconciliationTimer;

    private ReconciliationMapper reconciliationMapper;
    private MeterRegistry meterRegistry;
    private ObjectMapper objectMapper;
    private ReconciliationService reconciliationService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String tenantCode = "TENANT_ALPHA";

    static EmployeeHashProjection projectionOf(String code, long hash) {
        return new EmployeeHashProjection() {
            @Override public String getEmployeeCode() { return code; }
            @Override public Long getRecordHash() { return hash; }
            @Override public Long getSourceVersion() { return 1L; }
        };
    }

    private static String csvRow(String empCode, String name, String email,
                                  String phone, String office, String shift) {
        return String.format("%s,%s,%s,%s,,%s,%s,ACTIVE%n",
                empCode, name, email, phone, office, shift);
    }

    private static String buildCsv(String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");
        for (String row : rows) sb.append(row);
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId, tenantCode);
        reconciliationMapper = new ReconciliationMapper();
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        lenient().when(reconciliationTimer.record(any(java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

        lenient().when(reconciliationJobRepository.save(any(ReconciliationJob.class)))
                .thenAnswer(inv -> {
                    ReconciliationJob j = inv.getArgument(0);
                    if (j.getId() == null) {
                        ReflectionTestUtils.setField(j, "id", UUID.randomUUID());
                    }
                    return j;
                });

        lenient().when(officeService.getValidOfficeCodesForTenant(tenantId))
                .thenReturn(Set.of("BLR_ECOSPACE", "HYD_HITEC", "DEL_CYBERHUB"));

        reconciliationService = new ReconciliationService(
                employeeRepository, reconciliationJobRepository, reconciliationErrorRepository,
                outboxEventRepository, officeService, bookingService,
                new EmployeeMapper(),
                reconciliationMapper, objectMapper,
                safetyBrakeTriggeredCounter, reconciliationTimer
        );

        ReflectionTestUtils.setField(reconciliationService, "safetyBrakeThreshold", 0.30);
        ReflectionTestUtils.setField(reconciliationService, "streamingBatchSize", 2000);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ─── Test 1: All unchanged rows ───────────────────────────────────────────

    @Test
    @DisplayName("Should report all records unchanged when CSV matches DB hashes exactly")
    void shouldReportAllUnchangedWhenHashesMatch() throws Exception {
        String csv = buildCsv(
                csvRow("EMP001", "Alice Smith", "alice@corp.com", "+911234567890", "BLR_ECOSPACE", "SHIFT_0900"),
                csvRow("EMP002", "Bob Jones", "bob@corp.com", "+911234567891", "HYD_HITEC", "SHIFT_1300")
        );

        long hash1 = HashUtils.calculateHrRecordHash(
                "Alice Smith", "alice@corp.com", "+911234567890", "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
        long hash2 = HashUtils.calculateHrRecordHash(
                "Bob Jones", "bob@corp.com", "+911234567891", "", "HYD_HITEC", "SHIFT_1300", "ACTIVE");

        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(projectionOf("EMP001", hash1), projectionOf("EMP002", hash2)));

        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.getUnchangedRecords()).isEqualTo(2);
        assertThat(result.getUpdatedRecords()).isEqualTo(0);
        assertThat(result.getCreatedRecords()).isEqualTo(0);
        assertThat(result.getMissingCount()).isEqualTo(0);
        assertThat(result.isSafetyBrakeTriggered()).isFalse();

        verify(employeeRepository, never()).save(any());
    }

    // ─── Test 2: Safety brake triggers when > 30% missing ────────────────────

    @Test
    @DisplayName("Should trigger safety brake when missing ratio meets or exceeds 30%")
    void shouldTriggerSafetyBrakeWhenMissingRatioReachesThreshold() throws Exception {
        // Compute matching hashes for the 2 records in the CSV so they are recognized as UNCHANGED
        long hash1 = HashUtils.calculateHrRecordHash("Alice", "alice@corp.com", "+911234567890", "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
        long hash2 = HashUtils.calculateHrRecordHash("Bob", "bob@corp.com", "+911234567891", "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");

        // 4 active employees in DB, file only has 2 → 2 missing = 50% missing >= 30%
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(
                        projectionOf("EMP001", hash1),
                        projectionOf("EMP002", hash2),
                        projectionOf("EMP003", 333L),
                        projectionOf("EMP004", 444L)
                ));

        String csv = buildCsv(
                csvRow("EMP001", "Alice", "alice@corp.com", "+911234567890", "BLR_ECOSPACE", "SHIFT_0900"),
                csvRow("EMP002", "Bob", "bob@corp.com", "+911234567891", "BLR_ECOSPACE", "SHIFT_0900")
        );
        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.isSafetyBrakeTriggered()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED);
        assertThat(result.getUnchangedRecords()).isEqualTo(2);
        assertThat(result.getMissingCount()).isEqualTo(2);
        assertThat(result.getMissingRatio()).isGreaterThanOrEqualTo(BigDecimal.valueOf(0.30));

        // CRITICAL: no employees must be deactivated
        verify(employeeRepository, never()).updateStatusForEmployeeCodes(any(), any(), any(), any());
        verify(outboxEventRepository, never()).saveAll(anyCollection());
        verify(reconciliationErrorRepository, atLeastOnce()).saveAll(anyList());
        verify(safetyBrakeTriggeredCounter, times(1)).increment();
    }

    // ─── Test 3: No safety brake when below 30% ───────────────────────────────

    @Test
    @DisplayName("Should NOT trigger safety brake when missing ratio is below 30%")
    void shouldNotTriggerSafetyBrakeWhenMissingRatioIsBelow30Percent() throws Exception {
        // 10 in DB, 8 in file → 2 missing = 20% < 30%
        List<EmployeeHashProjection> dbProjections = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            long h = HashUtils.calculateHrRecordHash(
                    "Name" + i, "emp" + i + "@corp.com", "+91123456789" + i, "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
            dbProjections.add(projectionOf("EMP00" + i, h));
        }
        dbProjections.add(projectionOf("EMP009", 999L));
        dbProjections.add(projectionOf("EMP010", 1000L));

        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(dbProjections);

        // Active employees returned for deactivation batch
        Employee emp9 = Employee.builder().tenantId(tenantId).employeeCode("EMP009").fullName("Emp Nine")
                .officeCode("BLR_ECOSPACE").shiftCode("SHIFT_0900").status(EmployeeStatus.ACTIVE).sourceVersion(1L).build();
        Employee emp10 = Employee.builder().tenantId(tenantId).employeeCode("EMP010").fullName("Emp Ten")
                .officeCode("BLR_ECOSPACE").shiftCode("SHIFT_0900").status(EmployeeStatus.ACTIVE).sourceVersion(1L).build();

        when(employeeRepository.findByTenantIdAndEmployeeCodeInAndStatus(eq(tenantId), anyCollection(), eq(EmployeeStatus.ACTIVE)))
                .thenReturn(List.of(emp9, emp10));

        StringBuilder csvBuilder = new StringBuilder(
                "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");
        for (int i = 1; i <= 8; i++) {
            csvBuilder.append(String.format("EMP00%d,Name%d,emp%d@corp.com,+91123456789%d,,BLR_ECOSPACE,SHIFT_0900,ACTIVE%n",
                    i, i, i, i));
        }

        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csvBuilder.toString().getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.isSafetyBrakeTriggered()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.getUnchangedRecords()).isEqualTo(8);
        assertThat(result.getMissingCount()).isEqualTo(2);

        verify(safetyBrakeTriggeredCounter, never()).increment();
        // 2 missing employees should be deactivated
        verify(employeeRepository, atLeastOnce()).updateStatusForEmployeeCodes(
                eq(tenantId), anyCollection(), eq(EmployeeStatus.INACTIVE), any());
        // Outbox events must be saved for deactivated employees
        verify(outboxEventRepository, atLeastOnce()).saveAll(anyCollection());
        // Booking cancellation must be triggered
        verify(bookingService, times(2)).cancelFutureBookingsAndFreeSeats(eq(tenantId), anyString(), anyString());
    }

    // ─── Test 4: Exactly 30% missing ─────────────────────────────────────────

    @Test
    @DisplayName("Should trigger safety brake at exactly 30% missing (boundary condition)")
    void shouldTriggerSafetyBrakeAtExactly30Percent() throws Exception {
        // 10 in DB, 7 in file → 3 missing = exactly 30%
        List<EmployeeHashProjection> dbProjections = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            long h = HashUtils.calculateHrRecordHash(
                    "Name" + i, "emp" + i + "@corp.com", "+91123456789" + i, "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
            dbProjections.add(projectionOf("EMP_" + i, h));
        }
        dbProjections.add(projectionOf("EMP_8", 888L));
        dbProjections.add(projectionOf("EMP_9", 999L));
        dbProjections.add(projectionOf("EMP_10", 1000L));

        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(dbProjections);

        StringBuilder csvBuilder = new StringBuilder(
                "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");
        for (int i = 1; i <= 7; i++) {
            csvBuilder.append(String.format("EMP_%d,Name%d,emp%d@corp.com,+91123456789%d,,BLR_ECOSPACE,SHIFT_0900,ACTIVE%n",
                    i, i, i, i));
        }

        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csvBuilder.toString().getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.isSafetyBrakeTriggered()).isTrue();
        assertThat(result.getMissingRatio().doubleValue()).isGreaterThanOrEqualTo(0.30);
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED);
        verify(employeeRepository, never()).updateStatusForEmployeeCodes(any(), any(), any(), any());
    }

    // ─── Test 5: Empty CSV triggers failure gracefully ───────────────────────

    @Test
    @DisplayName("Should complete with zero records when CSV has only the header row")
    void shouldHandleEmptyCsvWithOnlyHeader() throws Exception {
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of());

        String csv = "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n";
        MultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.getTotalRecords()).isEqualTo(0);
        assertThat(result.getMissingCount()).isEqualTo(0);
        assertThat(result.isSafetyBrakeTriggered()).isFalse();
    }

    // ─── Test 6: Operator approves safety-brake halt ─────────────────────────

    @Test
    @DisplayName("Should deactivate missing employees when operator approves safety-brake job")
    void shouldDeactivateMissingEmployeesOnApproval() {
        UUID jobId = UUID.randomUUID();
        ReconciliationJob job = ReconciliationJob.builder()
                .jobReference("RECON_TEST_001")
                .tenantId(tenantId)
                .fileName("roster.csv")
                .status(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED)
                .missingCount(100)
                .activeDbCount(200)
                .safetyBrakeTriggered(true)
                .build();
        ReflectionTestUtils.setField(job, "id", jobId);

        when(reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, "RECON_TEST_001"))
                .thenReturn(Optional.of(job));
        when(reconciliationErrorRepository.findEmployeeCodesByJobIdAndErrorType(jobId, "MISSING_RECORD_FLAGGED"))
                .thenReturn(List.of("EMP_MISSING_1", "EMP_MISSING_2"));
        when(reconciliationJobRepository.save(any())).thenReturn(job);

        Employee m1 = Employee.builder().tenantId(tenantId).employeeCode("EMP_MISSING_1").status(EmployeeStatus.ACTIVE)
                .sourceVersion(1L).fullName("Missing One").officeCode("BLR_ECOSPACE").shiftCode("SHIFT_0900").build();
        Employee m2 = Employee.builder().tenantId(tenantId).employeeCode("EMP_MISSING_2").status(EmployeeStatus.ACTIVE)
                .sourceVersion(1L).fullName("Missing Two").officeCode("BLR_ECOSPACE").shiftCode("SHIFT_0900").build();

        when(employeeRepository.findByTenantIdAndEmployeeCodeInAndStatus(eq(tenantId), anyCollection(), eq(EmployeeStatus.ACTIVE)))
                .thenReturn(List.of(m1, m2));

        ReconciliationApprovalDto approval = ReconciliationApprovalDto.builder()
                .action("APPROVE")
                .operatorNotes("Reviewed and confirmed file is complete")
                .build();

        ReconciliationJobResponseDto result = reconciliationService.processApproval(
                "RECON_TEST_001", approval, "operator_alpha");

        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.APPROVED);
        assertThat(job.getApprovedBy()).isEqualTo("operator_alpha");

        // Deactivation must have been called
        verify(employeeRepository, atLeastOnce()).updateStatusForEmployeeCodes(
                eq(tenantId), anyCollection(), eq(EmployeeStatus.INACTIVE), any());
        // Outbox events must be saved
        verify(outboxEventRepository, atLeastOnce()).saveAll(anyCollection());
    }

    // ─── Test 7: Operator rejects — no deactivation ──────────────────────────

    @Test
    @DisplayName("Should not deactivate any employees when operator rejects safety-brake job")
    void shouldNotDeactivateOnRejection() {
        UUID jobId = UUID.randomUUID();
        ReconciliationJob job = ReconciliationJob.builder()
                .jobReference("RECON_TEST_002")
                .tenantId(tenantId)
                .fileName("roster.csv")
                .status(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED)
                .safetyBrakeTriggered(true)
                .build();
        ReflectionTestUtils.setField(job, "id", jobId);

        when(reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, "RECON_TEST_002"))
                .thenReturn(Optional.of(job));
        when(reconciliationJobRepository.save(any())).thenReturn(job);

        ReconciliationApprovalDto approval = ReconciliationApprovalDto.builder()
                .action("REJECT")
                .operatorNotes("File looks incomplete, re-export required")
                .build();

        ReconciliationJobResponseDto result = reconciliationService.processApproval(
                "RECON_TEST_002", approval, "operator_beta");

        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.REJECTED);
        verify(employeeRepository, never()).updateStatusForEmployeeCodes(any(), any(), any(), any());
        verify(bookingService, never()).cancelFutureBookingsAndFreeSeats(any(), any(), any());
    }

    // ─── Test 8: Cannot approve a non-SAFETY_BRAKE_TRIGGERED job ─────────────

    @Test
    @DisplayName("Should throw BusinessException when approving a job that is not in SAFETY_BRAKE_TRIGGERED state")
    void shouldRejectApprovalForInvalidJobState() {
        ReconciliationJob job = ReconciliationJob.builder()
                .jobReference("RECON_COMPLETED")
                .tenantId(tenantId)
                .fileName("roster.csv")
                .status(ReconciliationJobStatus.COMPLETED)
                .build();
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());

        when(reconciliationJobRepository.findByTenantIdAndJobReference(tenantId, "RECON_COMPLETED"))
                .thenReturn(Optional.of(job));

        ReconciliationApprovalDto approval = ReconciliationApprovalDto.builder()
                .action("APPROVE").build();

        assertThrows(BusinessException.class, () ->
                reconciliationService.processApproval("RECON_COMPLETED", approval, "operator_alpha"));
    }

    // ─── Test 9: Zero active employees in DB ─────────────────────────────────

    @Test
    @DisplayName("Should complete without safety brake when DB has zero active employees")
    void shouldHandleZeroActiveEmployeesInDb() throws Exception {
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of());

        String csv = buildCsv(
                csvRow("NEW001", "New Joiner", "new@corp.com", "+911111111111", "BLR_ECOSPACE", "SHIFT_0900")
        );
        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.isSafetyBrakeTriggered()).isFalse();
        assertThat(result.getMissingRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
    }

    // ─── Test 10: Tenant isolation ────────────────────────────────────────────

    @Test
    @DisplayName("Should only query and update employees belonging to the current tenant")
    void shouldEnforceTenantIsolation() throws Exception {
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of());

        String csv = buildCsv(
                csvRow("EMP001", "Tenant A User", "user@corp.com", "+911234567890", "BLR_ECOSPACE", "SHIFT_0900")
        );
        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        reconciliationService.executeReconciliation(file);

        verify(employeeRepository).findHashProjectionsByTenantIdAndStatus(eq(tenantId), any());
        verify(employeeRepository, never())
                .findHashProjectionsByTenantIdAndStatus(argThat(id -> !id.equals(tenantId)), any());
    }

    // ─── Test 11: HR update applies while preserving Transport Admin fields ───

    @Test
    @DisplayName("Should apply HR updates and preserve Transport Admin owned fields")
    void shouldUpdateHrFieldsAndPreserveTransportFields() throws Exception {
        // Hash in DB differs from CSV
        long oldHash = 123456789L;
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(projectionOf("EMP_TRANSFER", oldHash)));

        Employee existingEmployee = Employee.builder()
                .tenantId(tenantId)
                .employeeCode("EMP_TRANSFER")
                .fullName("John Transfer")
                .email("john@corp.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE") // old office
                .shiftCode("SHIFT_0900")
                .status(EmployeeStatus.ACTIVE)
                .sourceVersion(5L)
                // Transport Admin owned fields that MUST be preserved
                .pickupPointId("PICKUP_ECOSPACE_GATE2")
                .specialAssistance(true)
                .transportNotes("Requires front row seat due to injury")
                .build();

        when(employeeRepository.findByTenantIdAndEmployeeCode(tenantId, "EMP_TRANSFER"))
                .thenReturn(Optional.of(existingEmployee));

        // CSV row has transfer to new office HYD_HITEC and new shift SHIFT_1300
        String csv = buildCsv(
                csvRow("EMP_TRANSFER", "John Transfer", "john@corp.com", "+919876543210", "HYD_HITEC", "SHIFT_1300")
        );
        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.getUpdatedRecords()).isEqualTo(1);
        assertThat(result.getUnchangedRecords()).isEqualTo(0);

        ArgumentCaptor<Employee> empCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(empCaptor.capture());
        Employee savedEmp = empCaptor.getValue();

        // HR fields updated
        assertThat(savedEmp.getOfficeCode()).isEqualTo("HYD_HITEC");
        assertThat(savedEmp.getShiftCode()).isEqualTo("SHIFT_1300");
        assertThat(savedEmp.getSourceVersion()).isEqualTo(6L);

        // Transport Admin fields strictly preserved
        assertThat(savedEmp.getPickupPointId()).isEqualTo("PICKUP_ECOSPACE_GATE2");
        assertThat(savedEmp.getSpecialAssistance()).isTrue();
        assertThat(savedEmp.getTransportNotes()).isEqualTo("Requires front row seat due to injury");

        // Outbox event emitted for update
        verify(outboxEventRepository).save(argThat(event ->
                "EMPLOYEE_RECONCILIATION_UPDATED".equals(event.getEventType()) &&
                "EMP_TRANSFER".equals(event.getAggregateId())));
    }

    // ─── Test 12: Inactive employees do not get duplicate deactivation events ──

    @Test
    @DisplayName("Should not generate duplicate deactivation outbox events for employees already inactive")
    void shouldNotEmitDuplicateDeactivationEventsForAlreadyInactiveEmployees() throws Exception {
        // 10 active employees in DB
        List<EmployeeHashProjection> projections = new ArrayList<>();
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append("employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");

        for (int i = 1; i <= 10; i++) {
            String code = "EMP_" + i;
            String name = "User " + i;
            String email = "u" + i + "@corp.com";
            String phone = "+91987654321" + (i % 10);
            long hash = HashUtils.calculateHrRecordHash(name, email, phone, "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
            projections.add(projectionOf(code, hash));

            // Only 8 employees in CSV -> 2 missing (20% < 30% safety brake)
            if (i <= 8) {
                csvBuilder.append(String.format("%s,%s,%s,%s,,BLR_ECOSPACE,SHIFT_0900,ACTIVE%n",
                        code, name, email, phone));
            }
        }

        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(projections);

        // Of the 2 missing (EMP_9 and EMP_10), only EMP_9 is still ACTIVE in DB; EMP_10 was already changed to INACTIVE
        Employee activeEmp9 = Employee.builder()
                .tenantId(tenantId)
                .employeeCode("EMP_9")
                .fullName("User 9")
                .email("u9@corp.com")
                .phoneNumber("+919876543219")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status(EmployeeStatus.ACTIVE)
                .sourceVersion(1L)
                .build();

        when(employeeRepository.findByTenantIdAndEmployeeCodeInAndStatus(eq(tenantId), anyCollection(), eq(EmployeeStatus.ACTIVE)))
                .thenReturn(List.of(activeEmp9));

        MultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csvBuilder.toString().getBytes(StandardCharsets.UTF_8));

        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(file);

        // Safety brake did not trigger (20% < 30%)
        assertThat(result.isSafetyBrakeTriggered()).isFalse();
        assertThat(result.getMissingCount()).isEqualTo(2);

        // Status update called ONLY for EMP_9 (active)
        verify(employeeRepository).updateStatusForEmployeeCodes(
                eq(tenantId), eq(List.of("EMP_9")), eq(EmployeeStatus.INACTIVE), any());

        // Exactly 1 outbox event generated for EMP_9, none for EMP_10
        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).saveAll(outboxCaptor.capture());
        List<OutboxEvent> savedEvents = outboxCaptor.getValue();
        assertThat(savedEvents).hasSize(1);
        assertThat(savedEvents.get(0).getAggregateId()).isEqualTo("EMP_9");
        assertThat(savedEvents.get(0).getEventType()).isEqualTo("EMPLOYEE_DEACTIVATE");

        // Bookings cancelled only for EMP_9
        verify(bookingService).cancelFutureBookingsAndFreeSeats(
                eq(tenantId), eq("EMP_9"), any());
        verify(bookingService, never()).cancelFutureBookingsAndFreeSeats(
                eq(tenantId), eq("EMP_10"), any());
    }
}
