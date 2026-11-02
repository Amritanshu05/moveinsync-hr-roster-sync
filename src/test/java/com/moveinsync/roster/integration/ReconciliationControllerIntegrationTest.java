package com.moveinsync.roster.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.controller.ReconciliationController;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.dto.request.ReconciliationApprovalDto;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.mapper.ReconciliationMapper;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.JwtAuthenticationFilter;
import com.moveinsync.roster.security.JwtTokenProvider;
import com.moveinsync.roster.security.SecurityConfig;
import com.moveinsync.roster.security.TenantFilter;
import com.moveinsync.roster.service.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReconciliationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TenantFilter.class})
@DisplayName("Reconciliation Controller Principal Injection and Access Control Tests")
class ReconciliationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReconciliationService reconciliationService;

    @MockBean
    private ReconciliationMapper reconciliationMapper;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TenantRepository tenantRepository;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String tenantCode = "TENANT_ALPHA";

    private void mockToken(String token, String username, String role) {
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn(username);
        when(jwtTokenProvider.getTenantIdFromToken(token)).thenReturn(tenantId);
        when(jwtTokenProvider.getTenantCodeFromToken(token)).thenReturn(tenantCode);
        when(jwtTokenProvider.getRoleFromToken(token)).thenReturn(role);
        when(jwtTokenProvider.getUserIdFromToken(token)).thenReturn(UUID.randomUUID());
    }

    // ─── Test 1: Operator successfully approves a job ─────────────────────────

    @Test
    @DisplayName("Should approve safety-brake job and record operator username from JWT principal")
    void shouldApproveJobAndRecordOperatorUsername() throws Exception {
        String token = "operator-token";
        mockToken(token, "operator_john", "ROLE_OPERATOR");

        ReconciliationJobResponseDto mockResponse = ReconciliationJobResponseDto.builder()
                .jobReference("RECON_JOB_123")
                .status(ReconciliationJobStatus.APPROVED)
                .approvedBy("operator_john")
                .approvedAt(Instant.now())
                .build();

        when(reconciliationService.processApproval(eq("RECON_JOB_123"), any(), eq("operator_john")))
                .thenReturn(mockResponse);

        ReconciliationApprovalDto body = ReconciliationApprovalDto.builder()
                .action("APPROVE")
                .operatorNotes("Approved after manual audit")
                .build();

        mockMvc.perform(post("/api/v1/reconciliation/batches/RECON_JOB_123/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBy").value("operator_john"));

        ArgumentCaptor<String> operatorCaptor = ArgumentCaptor.forClass(String.class);
        verify(reconciliationService).processApproval(eq("RECON_JOB_123"), any(), operatorCaptor.capture());
        assertThat(operatorCaptor.getValue()).isEqualTo("operator_john");
    }

    // ─── Test 2: Operator successfully rejects a job ──────────────────────────

    @Test
    @DisplayName("Should reject safety-brake job and record operator username from JWT principal")
    void shouldRejectJobAndRecordOperatorUsername() throws Exception {
        String token = "operator-token";
        mockToken(token, "operator_mary", "ROLE_OPERATOR");

        ReconciliationJobResponseDto mockResponse = ReconciliationJobResponseDto.builder()
                .jobReference("RECON_JOB_456")
                .status(ReconciliationJobStatus.REJECTED)
                .approvedBy("operator_mary")
                .approvedAt(Instant.now())
                .build();

        when(reconciliationService.processApproval(eq("RECON_JOB_456"), any(), eq("operator_mary")))
                .thenReturn(mockResponse);

        ReconciliationApprovalDto body = ReconciliationApprovalDto.builder()
                .action("REJECT")
                .operatorNotes("Export file corrupted, rejected")
                .build();

        mockMvc.perform(post("/api/v1/reconciliation/batches/RECON_JOB_456/reject")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        ArgumentCaptor<String> operatorCaptor = ArgumentCaptor.forClass(String.class);
        verify(reconciliationService).processApproval(eq("RECON_JOB_456"), any(), operatorCaptor.capture());
        assertThat(operatorCaptor.getValue()).isEqualTo("operator_mary");
    }

    // ─── Test 3: Unauthorized role rejected with 403 ──────────────────────────

    @Test
    @DisplayName("Should reject non-operator user with HTTP 403 Forbidden")
    void shouldRejectUnauthorizedRole() throws Exception {
        String token = "employee-token";
        mockToken(token, "regular_employee", "ROLE_EMPLOYEE");

        ReconciliationApprovalDto body = ReconciliationApprovalDto.builder()
                .action("APPROVE")
                .build();

        mockMvc.perform(post("/api/v1/reconciliation/batches/RECON_JOB_123/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(reconciliationService, never()).processApproval(any(), any(), any());
    }

    // ─── Test 4: Missing authentication rejected with 401 (not uncontrolled 500) ─

    @Test
    @DisplayName("Should reject unauthenticated request with HTTP 401 without throwing NPE or 500")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        ReconciliationApprovalDto body = ReconciliationApprovalDto.builder()
                .action("APPROVE")
                .build();

        mockMvc.perform(post("/api/v1/reconciliation/batches/RECON_JOB_123/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());

        verify(reconciliationService, never()).processApproval(any(), any(), any());
    }
}
