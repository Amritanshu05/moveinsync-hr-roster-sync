package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.security.JwtTokenProvider;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.EventIngestionService;
import com.moveinsync.roster.service.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST 7 — Multi-Tenant Isolation Against Real PostgreSQL
 *
 * <p>Validates that:
 * <ol>
 *   <li>Tenant A cannot read Tenant B's employees, even when they share identical employee_codes.</li>
 *   <li>Event ingestion for Tenant A cannot mutate Tenant B's employee records.</li>
 *   <li>Nightly reconciliation executed for Tenant A has zero side-effects on Tenant B's roster.</li>
 *   <li>HTTP layer enforces tenant matching: A valid JWT for Tenant A paired with X-Tenant-ID for Tenant B
 *       is strictly rejected with HTTP 403 TENANT_MISMATCH.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired EmployeeRepository employeeRepository;
    @Autowired EventIngestionService eventIngestionService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MockMvc mockMvc;
    @Autowired com.moveinsync.roster.repository.TenantRepository tenantRepository;
    @Autowired com.moveinsync.roster.repository.OfficeRepository officeRepository;

    private static final UUID TENANT_ALPHA =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_BETA =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // TEST 7a: Identical employee code across tenants — strict repository isolation
    // =========================================================================
    @Test
    @DisplayName("TEST-7a: Repository isolation — identical employee code in Tenant A & B yields separate records")
    void test7a_repositoryTenantIsolation() {
        String sharedCode = "ISO_EMP_" + UUID.randomUUID().toString().substring(0, 5);

        // Persist employee in Tenant Alpha
        Employee empA = TestDataFactory.buildEmployee(TENANT_ALPHA, sharedCode, 1L);
        empA.setFullName("Alpha User " + sharedCode);
        empA.setEmail("alpha." + sharedCode.toLowerCase() + "@alpha.com");
        employeeRepository.save(empA);

        // Persist employee in Tenant Beta with SAME employee_code
        Employee empB = TestDataFactory.buildEmployee(TENANT_BETA, sharedCode, 1L);
        empB.setFullName("Beta User " + sharedCode);
        empB.setEmail("beta." + sharedCode.toLowerCase() + "@beta.com");
        employeeRepository.save(empB);

        // Query under Tenant Alpha
        Optional<Employee> foundA = employeeRepository.findByTenantIdAndEmployeeCode(TENANT_ALPHA, sharedCode);
        assertThat(foundA).isPresent();
        assertThat(foundA.get().getFullName()).isEqualTo("Alpha User " + sharedCode);
        assertThat(foundA.get().getTenantId()).isEqualTo(TENANT_ALPHA);

        // Query under Tenant Beta
        Optional<Employee> foundB = employeeRepository.findByTenantIdAndEmployeeCode(TENANT_BETA, sharedCode);
        assertThat(foundB).isPresent();
        assertThat(foundB.get().getFullName()).isEqualTo("Beta User " + sharedCode);
        assertThat(foundB.get().getTenantId()).isEqualTo(TENANT_BETA);
    }

    // =========================================================================
    // TEST 7b: Ingestion isolation — updating Tenant A does not touch Tenant B
    // =========================================================================
    @Test
    @DisplayName("TEST-7b: Event ingestion isolation — updating Tenant A leaves Tenant B completely unchanged")
    void test7b_ingestionTenantIsolation() {
        String code = "ISO_INGEST_" + UUID.randomUUID().toString().substring(0, 5);

        // Seed both tenants
        TestDataFactory.persistEmployee(employeeRepository, TENANT_ALPHA, code, 1L);
        TestDataFactory.persistEmployee(employeeRepository, TENANT_BETA, code, 1L);

        // Send update event for TENANT_ALPHA changing shift to NIGHT
        TenantContext.setTenant(TENANT_ALPHA, "TENANT_ALPHA");
        EmployeeEventDto updateEvent = TestDataFactory.buildUpdateEvent(code, 2L, "NIGHT");
        BatchIngestResponse response = eventIngestionService.ingestBatch(
                TestDataFactory.singleEventBatch(updateEvent));

        assertThat(response.getResults().get(0).getOutcome()).isEqualTo(ProcessingOutcome.APPLIED);

        // Verify Tenant Alpha is updated to NIGHT, version 2
        Employee alphaEmp = employeeRepository.findByTenantIdAndEmployeeCode(TENANT_ALPHA, code).orElseThrow();
        assertThat(alphaEmp.getShiftCode()).isEqualTo("NIGHT");
        assertThat(alphaEmp.getSourceVersion()).isEqualTo(2L);

        // Verify Tenant Beta is UNTOUCHED: still MORNING, version 1
        Employee betaEmp = employeeRepository.findByTenantIdAndEmployeeCode(TENANT_BETA, code).orElseThrow();
        assertThat(betaEmp.getShiftCode()).isEqualTo("MORNING");
        assertThat(betaEmp.getSourceVersion()).isEqualTo(1L);
    }

    // =========================================================================
    // TEST 7c: Reconciliation isolation — Tenant A recon does not touch Tenant B
    // =========================================================================
    @Test
    @DisplayName("TEST-7c: Reconciliation isolation — nightly diff for Tenant A does not deactivate Tenant B")
    void test7c_reconciliationTenantIsolation() {
        String tenantACode = "ISO_REC_T_" + UUID.randomUUID().toString().substring(0, 6);
        com.moveinsync.roster.domain.entity.Tenant tenantA = TestDataFactory.createAndPersistTenant(
                tenantRepository, tenantACode, "Iso Recon Tenant A");
        TestDataFactory.persistOffice(officeRepository, tenantA.getId(), "BLR_ECOSPACE");

        String alphaCode = "ISO_REC_A_" + UUID.randomUUID().toString().substring(0, 5);
        String betaCode  = "ISO_REC_B_" + UUID.randomUUID().toString().substring(0, 5);

        TestDataFactory.persistEmployee(employeeRepository, tenantA.getId(), alphaCode, 1L);
        TestDataFactory.persistEmployee(employeeRepository, TENANT_BETA, betaCode, 1L);

        // Run reconciliation for tenantA with CSV containing alphaCode (0 missing)
        TenantContext.setTenant(tenantA.getId(), tenantACode);
        String csv = "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n"
                + alphaCode + ",User " + alphaCode + "," + alphaCode.toLowerCase() + "@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "alpha_recon.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        ReconciliationJobResponseDto jobResp = reconciliationService.executeReconciliation(file);
        assertThat(jobResp.isSafetyBrakeTriggered()).isFalse();

        // Tenant Beta's employee MUST remain ACTIVE, not touched or deactivated
        Employee betaEmp = employeeRepository.findByTenantIdAndEmployeeCode(TENANT_BETA, betaCode).orElseThrow();
        assertThat(betaEmp.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    // =========================================================================
    // TEST 7d: JWT Header Mismatch — 403 TENANT_MISMATCH
    // =========================================================================
    @Test
    @DisplayName("TEST-7d: Security Filter — valid JWT for Tenant Alpha with X-Tenant-ID: TENANT_BETA is rejected with 403")
    void test7d_jwtTenantMismatchForbidden() throws Exception {
        // Generate valid JWT signed for TENANT_ALPHA
        String token = jwtTokenProvider.generateToken(
                UUID.randomUUID(), "operator_alpha", TENANT_ALPHA, "TENANT_ALPHA", "ROLE_OPERATOR");

        // Request sent with X-Tenant-ID: TENANT_BETA -> should be rejected 403
        mockMvc.perform(get("/api/v1/employees")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", "TENANT_BETA"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TENANT_MISMATCH")));
    }
}
