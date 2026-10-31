package com.moveinsync.roster.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.controller.EmployeeController;
import com.moveinsync.roster.controller.EventIngestionController;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.RecordErrorDetail;
import com.moveinsync.roster.dto.response.RecordResultDto;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.JwtAuthenticationFilter;
import com.moveinsync.roster.security.JwtTokenProvider;
import com.moveinsync.roster.security.SecurityConfig;
import com.moveinsync.roster.security.TenantFilter;
import com.moveinsync.roster.service.EmployeeService;
import com.moveinsync.roster.service.EventIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {EventIngestionController.class, EmployeeController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TenantFilter.class})
@DisplayName("Event Ingestion Web MVC Integration Tests")
class EventIngestionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventIngestionService eventIngestionService;

    @MockBean
    private EmployeeService employeeService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TenantRepository tenantRepository;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String tenantCode = "TENANT_ALPHA";

    @Test
    @DisplayName("Should ingest batch successfully and return per-record result with HTTP 200")
    void shouldIngestBatchSuccessfully() throws Exception {
        String token = "valid-operator-jwt-token";

        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("operator_alpha");
        when(jwtTokenProvider.getTenantIdFromToken(token)).thenReturn(tenantId);
        when(jwtTokenProvider.getTenantCodeFromToken(token)).thenReturn(tenantCode);
        when(jwtTokenProvider.getRoleFromToken(token)).thenReturn("ROLE_OPERATOR");

        BatchIngestResponse mockResponse = BatchIngestResponse.builder()
                .tenantCode(tenantCode)
                .totalReceived(2)
                .appliedCount(1)
                .rejectedCount(1)
                .results(List.of(
                        RecordResultDto.builder()
                                .employeeCode("EMP_1001")
                                .outcome(ProcessingOutcome.APPLIED)
                                .appliedVersion(1L)
                                .build(),
                        RecordResultDto.builder()
                                .employeeCode("EMP_1002")
                                .outcome(ProcessingOutcome.VALIDATION_FAILED)
                                .errors(List.of(new RecordErrorDetail("phoneNumber", "INVALID_PHONE_FORMAT", "Phone number invalid")))
                                .build()
                ))
                .build();

        when(eventIngestionService.ingestBatch(any())).thenReturn(mockResponse);

        EmployeeEventDto validEvent = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_1001")
                .sourceVersion(1L)
                .fullName("John Doe")
                .email("john@corp.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        EmployeeEventDto badEvent = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_1002")
                .sourceVersion(1L)
                .phoneNumber("123")
                .build();

        BatchIngestRequestDto requestDto = BatchIngestRequestDto.builder()
                .events(List.of(validEvent, badEvent))
                .build();

        mockMvc.perform(post("/api/v1/events/ingest")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", tenantCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalReceived").value(2))
                .andExpect(jsonPath("$.data.appliedCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].employeeCode").value("EMP_1001"))
                .andExpect(jsonPath("$.data.results[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$.data.results[1].employeeCode").value("EMP_1002"))
                .andExpect(jsonPath("$.data.results[1].outcome").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.results[1].errors[0].errorCode").value("INVALID_PHONE_FORMAT"));
    }

    @Test
    @DisplayName("Should return 403 Forbidden when caller does not have ROLE_OPERATOR or ROLE_ADMIN")
    void shouldRejectUnauthorizedRole() throws Exception {
        String token = "employee-jwt-token";

        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("john_doe");
        when(jwtTokenProvider.getTenantIdFromToken(token)).thenReturn(tenantId);
        when(jwtTokenProvider.getTenantCodeFromToken(token)).thenReturn(tenantCode);
        when(jwtTokenProvider.getRoleFromToken(token)).thenReturn("ROLE_EMPLOYEE");

        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_1001")
                .sourceVersion(1L)
                .build();

        BatchIngestRequestDto requestDto = BatchIngestRequestDto.builder()
                .events(List.of(event))
                .build();

        mockMvc.perform(post("/api/v1/events/ingest")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", tenantCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when Authorization header is missing")
    void shouldRejectMissingAuth() throws Exception {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_1001")
                .sourceVersion(1L)
                .build();

        BatchIngestRequestDto requestDto = BatchIngestRequestDto.builder()
                .events(List.of(event))
                .build();

        mockMvc.perform(post("/api/v1/events/ingest")
                        .header("X-Tenant-ID", tenantCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isUnauthorized());
    }
}
