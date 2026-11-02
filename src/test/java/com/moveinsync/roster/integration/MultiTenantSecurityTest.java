package com.moveinsync.roster.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.controller.EmployeeController;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.dto.response.EmployeeResponseDto;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.JwtAuthenticationFilter;
import com.moveinsync.roster.security.JwtTokenProvider;
import com.moveinsync.roster.security.SecurityConfig;
import com.moveinsync.roster.security.TenantFilter;
import com.moveinsync.roster.service.EmployeeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EmployeeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TenantFilter.class})
@DisplayName("Multi-Tenant Security and Isolation Regression Tests")
class MultiTenantSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmployeeService employeeService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TenantRepository tenantRepository;

    private final UUID tenantAlphaId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String tenantAlphaCode = "TENANT_ALPHA";
    private final String tenantBetaCode = "TENANT_BETA";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void mockAlphaToken(String token) {
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("operator_alpha");
        when(jwtTokenProvider.getTenantIdFromToken(token)).thenReturn(tenantAlphaId);
        when(jwtTokenProvider.getTenantCodeFromToken(token)).thenReturn(tenantAlphaCode);
        when(jwtTokenProvider.getRoleFromToken(token)).thenReturn("ROLE_OPERATOR");
        when(jwtTokenProvider.getUserIdFromToken(token)).thenReturn(UUID.randomUUID());
    }

    // ─── Test 1: Valid JWT and matching tenant header ─────────────────────────

    @Test
    @DisplayName("1. Valid JWT and matching tenant header -> 200 OK")
    void shouldAllowAccessWhenValidJwtAndMatchingTenantHeader() throws Exception {
        String token = "valid-alpha-jwt";
        mockAlphaToken(token);

        EmployeeResponseDto emp = EmployeeResponseDto.builder()
                .employeeCode("EMP001")
                .fullName("Alice Alpha")
                .status(EmployeeStatus.ACTIVE)
                .build();
        when(employeeService.getEmployee("EMP001")).thenReturn(emp);

        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", tenantAlphaCode)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.employeeCode").value("EMP001"));
    }

    // ─── Test 2: Valid JWT and mismatching tenant header ───────────────────────

    @Test
    @DisplayName("2. Valid JWT and mismatching tenant header -> 403 Forbidden with TENANT_MISMATCH")
    void shouldRejectWith403WhenValidJwtAndMismatchingTenantHeader() throws Exception {
        String token = "valid-alpha-jwt";
        mockAlphaToken(token);

        // Client requests TENANT_BETA using TENANT_ALPHA JWT
        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", tenantBetaCode)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TENANT_MISMATCH")));
    }

    // ─── Test 3: Valid JWT without tenant header ──────────────────────────────

    @Test
    @DisplayName("3. Valid JWT without tenant header -> 200 OK (JWT is authoritative)")
    void shouldAllowAccessWhenValidJwtWithoutTenantHeader() throws Exception {
        String token = "valid-alpha-jwt";
        mockAlphaToken(token);

        EmployeeResponseDto emp = EmployeeResponseDto.builder()
                .employeeCode("EMP001")
                .fullName("Alice Alpha")
                .status(EmployeeStatus.ACTIVE)
                .build();
        when(employeeService.getEmployee("EMP001")).thenReturn(emp);

        // No X-Tenant-ID header supplied
        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.employeeCode").value("EMP001"));
    }

    // ─── Test 4: Missing or invalid JWT ───────────────────────────────────────

    @Test
    @DisplayName("4. Missing or invalid JWT -> 401 Unauthorized")
    void shouldRejectWhenMissingOrInvalidJwt() throws Exception {
        // Missing Authorization header
        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("X-Tenant-ID", tenantAlphaCode)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // Invalid JWT
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("Authorization", "Bearer invalid-token")
                        .header("X-Tenant-ID", tenantAlphaCode)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 5: Attempts to read another tenant's employee data ──────────────

    @Test
    @DisplayName("5. Enforce tenant isolation in business execution context")
    void shouldEnforceTenantIsolationInBusinessContext() throws Exception {
        String token = "valid-alpha-jwt";
        mockAlphaToken(token);

        AtomicReference<UUID> capturedTenantId = new AtomicReference<>();
        when(employeeService.getEmployee("EMP_TARGET")).thenAnswer(inv -> {
            capturedTenantId.set(TenantContext.getTenantId());
            return EmployeeResponseDto.builder()
                    .employeeCode("EMP_TARGET")
                    .fullName("Target Employee")
                    .status(EmployeeStatus.ACTIVE)
                    .build();
        });

        mockMvc.perform(get("/api/v1/employees/EMP_TARGET")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // The business method executed strictly under TENANT_ALPHA identity from the JWT
        assertThat(capturedTenantId.get()).isEqualTo(tenantAlphaId);
    }

    // ─── Test 6: Tenant context cleanup between requests ───────────────────────

    @Test
    @DisplayName("6. Clean up TenantContext between requests (no thread-local leaks)")
    void shouldCleanUpTenantContextBetweenRequests() throws Exception {
        String token = "valid-alpha-jwt";
        mockAlphaToken(token);

        when(employeeService.getEmployee("EMP001")).thenReturn(
                EmployeeResponseDto.builder().employeeCode("EMP001").status(EmployeeStatus.ACTIVE).build());

        mockMvc.perform(get("/api/v1/employees/EMP001")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Post-request: ThreadLocal must be pristine
        assertThat(TenantContext.getTenantCode()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
