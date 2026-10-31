package com.moveinsync.roster.unit;

import com.moveinsync.roster.domain.entity.ReconciliationJob;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.dto.request.ReconciliationApprovalDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.exception.BusinessException;
import com.moveinsync.roster.exception.EntityNotFoundException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
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

    // ─── Minimal stub to represent a DB hash projection ──────────────────────
    static EmployeeHashProjection projectionOf(String code, long hash) {
        return new EmployeeHashProjection() {
            @Override public String getEmployeeCode() { return code; }
            @Override public Long getRecordHash() { return hash; }
            @Override public Long getSourceVersion() { return 1L; }
        };
    }

    // ─── Minimal CSV builder ──────────────────────────────────────────────────
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

        // Stub timer to pass through immediately (Timer.record takes Supplier<T>)
        lenient().when(reconciliationTimer.record(any(java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

        // Stub job save to return the job passed in
        lenient().when(reconciliationJobRepository.save(any(ReconciliationJob.class)))
                .thenAnswer(inv -> {
                    ReconciliationJob j = inv.getArgument(0);
                    if (j.getId() == null) {
                        // Simulate id assignment on first save
                        ReflectionTestUtils.setField(j, "id", UUID.randomUUID());
                    }
                    return j;
                });

        lenient().when(officeService.getValidOfficeCodesForTenant(tenantId))
                .thenReturn(Set.of("BLR_ECOSPACE", "HYD_HITEC", "DEL_CYBERHUB"));

        reconciliationService = new ReconciliationService(
                employeeRepository, reconciliationJobRepository, reconciliationErrorRepository,
                outboxEventRepository, officeService, bookingService,
                new com.moveinsync.roster.mapper.EmployeeMapper(),
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

        // Simulate DB hash projections that match the CSV rows exactly
        // We need hashes computed from the same fields the service computes
        long hash1 = com.moveinsync.roster.util.HashUtils.calculateHrRecordHash(
                "Alice Smith", "alice@corp.com", "+911234567890", "", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE");
        long hash2 = com.moveinsync.roster.util.HashUtils.calculateHrRecordHash(
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
        // 4 active employees in DB, file only has 2 → 50% missing > 30%
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(
                        projectionOf("EMP001", 111L),
                        projectionOf("EMP002", 222L),
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
        assertThat(result.getMissingCount()).isEqualTo(2);
        assertThat(result.getMissingRatio()).isGreaterThanOrEqualTo(BigDecimal.valueOf(0.30));

        // CRITICAL: no employees must be deactivated
        verify(employeeRepository, never()).updateStatusForEmployeeCodes(any(), any(), any(), any());
        // Missing records must be recorded for operator review
        verify(reconciliationErrorRepository, atLeastOnce()).saveAll(anyList());
        verify(safetyBrakeTriggeredCounter, times(1)).increment();
    }

    // ─── Test 3: No safety brake when below 30% ───────────────────────────────

    @Test
    @DisplayName("Should NOT trigger safety brake when missing ratio is below 30%")
    void shouldNotTriggerSafetyBrakeWhenMissingRatioIsBelow30Percent() throws Exception {
        // 10 in DB, 8 in file → 20% missing < 30%
        List<EmployeeHashProjection> dbProjections = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            dbProjections.add(projectionOf("EMP00" + i, (long) i * 100));
        }
        when(employeeRepository.findHashProjectionsByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE))
                .thenReturn(dbProjections);

        // CSV with 8 entries (EMP001–EMP008) with hash-mismatching data to trigger update path
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
        verify(safetyBrakeTriggeredCounter, never()).increment();
        // 2 missing employees should be deactivated
        verify(employeeRepository, atLeastOnce()).updateStatusForEmployeeCodes(
                eq(tenantId), anyCollection(), eq(EmployeeStatus.INACTIVE), any());
    }

    // ─── Test 4: Exactly 30% missing ─────────────────────────────────────────

    @Test
    @DisplayName("Should trigger safety brake at exactly 30% missing (boundary condition)")
    void shouldTriggerSafetyBrakeAtExactly30Percent() throws Exception {
        // 10 in DB, 7 in file → 3 missing = exactly 30%
        List<EmployeeHashProjection> dbProjections = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            dbProjections.add(projectionOf("EMP_" + i, (long) i));
        }
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
        // CRITICAL: zero employees deactivated
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

        // No missing employees → no safety brake
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

        // All DB calls must use the current tenant ID — never a different tenant
        verify(employeeRepository).findHashProjectionsByTenantIdAndStatus(eq(tenantId), any());
        verify(employeeRepository, never())
                .findHashProjectionsByTenantIdAndStatus(argThat(id -> !id.equals(tenantId)), any());
    }
}
