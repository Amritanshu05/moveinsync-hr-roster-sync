package com.moveinsync.roster.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.EmployeeEvent;
import com.moveinsync.roster.domain.entity.IdempotencyRecord;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.RecordResultDto;
import com.moveinsync.roster.mapper.EmployeeMapper;
import com.moveinsync.roster.repository.EmployeeEventRepository;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.IdempotencyRecordRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.EventIngestionService;
import com.moveinsync.roster.service.OfficeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Event Ingestion Service Unit Tests")
class EventIngestionServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private EmployeeEventRepository employeeEventRepository;
    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private BookingService bookingService;
    @Mock
    private OfficeService officeService;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache cache;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Counter eventsReceivedCounter;
    @Mock
    private Counter eventsAppliedCounter;
    @Mock
    private Counter eventsStaleCounter;
    @Mock
    private Counter eventsDuplicateCounter;
    @Mock
    private Counter eventsRejectedCounter;
    @Mock
    private Timer eventProcessingTimer;

    private EmployeeMapper employeeMapper;
    private ObjectMapper objectMapper;
    private EventIngestionService ingestionService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String tenantCode = "TENANT_ALPHA";

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.setTenant(tenantId, tenantCode);

        employeeMapper = new EmployeeMapper();
        objectMapper = new ObjectMapper();

        // Stub transaction manager to execute synchronously in tests
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        // Stub timer to record supplier
        lenient().when(eventProcessingTimer.record(any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });

        lenient().when(officeService.getValidOfficeCodesForTenant(tenantId))
                .thenReturn(Set.of("BLR_ECOSPACE", "HYD_HITEC", "DEL_CYBERHUB"));

        lenient().when(cacheManager.getCache(anyString())).thenReturn(cache);

        ingestionService = new EventIngestionService(
                employeeRepository,
                employeeEventRepository,
                idempotencyRecordRepository,
                outboxEventRepository,
                bookingService,
                officeService,
                employeeMapper,
                objectMapper,
                cacheManager,
                transactionManager,
                eventsReceivedCounter,
                eventsAppliedCounter,
                eventsStaleCounter,
                eventsDuplicateCounter,
                eventsRejectedCounter,
                eventProcessingTimer
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Should successfully create a new employee when event is valid")
    void shouldCreateNewEmployeeOnValidEvent() {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_1001")
                .sourceVersion(1L)
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        when(idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(eq(tenantId), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findForUpdateByTenantIdAndEmployeeCode(tenantId, "EMP_1001"))
                .thenReturn(Optional.empty());

        RecordResultDto result = ingestionService.ingestSingle(event);

        assertNotNull(result);
        assertEquals(ProcessingOutcome.APPLIED, result.getOutcome());
        assertEquals(1L, result.getAppliedVersion());

        verify(employeeRepository, times(1)).save(any(Employee.class));
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
        verify(idempotencyRecordRepository, times(1)).save(any(IdempotencyRecord.class));
        verify(employeeEventRepository, times(1)).save(any(EmployeeEvent.class));
        verify(eventsAppliedCounter, times(1)).increment();
    }

    @Test
    @DisplayName("Should ignore stale event when incoming version is lower than current version")
    void shouldIgnoreStaleVersionEvent() {
        // Current employee in DB is at version 7
        Employee existingEmployee = Employee.builder()
                .tenantId(tenantId)
                .employeeCode("EMP_1001")
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status(EmployeeStatus.ACTIVE)
                .sourceVersion(7L)
                .build();

        // Stale event arrives with version 6
        EmployeeEventDto staleEvent = EmployeeEventDto.builder()
                .eventType("UPDATE")
                .employeeCode("EMP_1001")
                .sourceVersion(6L)
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_1300") // Stale shift change
                .status("ACTIVE")
                .build();

        when(idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(eq(tenantId), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findForUpdateByTenantIdAndEmployeeCode(tenantId, "EMP_1001"))
                .thenReturn(Optional.of(existingEmployee));

        RecordResultDto result = ingestionService.ingestSingle(staleEvent);

        assertNotNull(result);
        assertEquals(ProcessingOutcome.STALE_IGNORED, result.getOutcome());
        assertEquals(7L, result.getAppliedVersion(), "Should preserve current version 7");

        // Verify state was NOT mutated
        verify(employeeRepository, never()).save(any(Employee.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventsStaleCounter, times(1)).increment();
    }

    @Test
    @DisplayName("Should treat replayed duplicate event as an idempotent no-op")
    void shouldTreatDuplicateReplayAsNoOp() {
        // Existing employee already processed version 7
        Employee existingEmployee = Employee.builder()
                .tenantId(tenantId)
                .employeeCode("EMP_1001")
                .sourceVersion(7L)
                .status(EmployeeStatus.ACTIVE)
                .build();

        EmployeeEventDto duplicateEvent = EmployeeEventDto.builder()
                .eventType("UPDATE")
                .employeeCode("EMP_1001")
                .sourceVersion(7L)
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        // Idempotency record already exists
        IdempotencyRecord record = IdempotencyRecord.builder()
                .tenantId(tenantId)
                .idempotencyKey(tenantId + ":EMP_1001:7")
                .targetEntityId("EMP_1001")
                .sourceVersion(7L)
                .responseStatus("APPLIED")
                .build();

        when(idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(eq(tenantId), anyString()))
                .thenReturn(Optional.of(record));

        RecordResultDto result = ingestionService.ingestSingle(duplicateEvent);

        assertNotNull(result);
        assertEquals(ProcessingOutcome.DUPLICATE_NO_OP, result.getOutcome());

        // Zero database modifications
        verify(employeeRepository, never()).save(any(Employee.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventsDuplicateCounter, times(1)).increment();
    }

    @Test
    @DisplayName("Should deactivate employee, trigger booking cancellation, and free seats")
    void shouldDeactivateEmployeeAndCancelBookings() {
        Employee existingEmployee = Employee.builder()
                .tenantId(tenantId)
                .employeeCode("EMP_1001")
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status(EmployeeStatus.ACTIVE)
                .sourceVersion(5L)
                .pickupPointId("PP_BELLANDUR")
                .specialAssistance(true)
                .build();

        EmployeeEventDto deactivateEvent = EmployeeEventDto.builder()
                .eventType("DEACTIVATE")
                .employeeCode("EMP_1001")
                .sourceVersion(6L)
                .build();

        when(idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(eq(tenantId), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findForUpdateByTenantIdAndEmployeeCode(tenantId, "EMP_1001"))
                .thenReturn(Optional.of(existingEmployee));

        RecordResultDto result = ingestionService.ingestSingle(deactivateEvent);

        assertNotNull(result);
        assertEquals(ProcessingOutcome.APPLIED, result.getOutcome());
        assertEquals(EmployeeStatus.INACTIVE, existingEmployee.getStatus());

        // Transport fields MUST be preserved
        assertEquals("PP_BELLANDUR", existingEmployee.getPickupPointId());
        assertTrue(existingEmployee.getSpecialAssistance());

        // Downstream cancellation MUST be triggered
        verify(bookingService, times(1)).cancelFutureBookingsAndFreeSeats(
                eq(tenantId), eq("EMP_1001"), anyString());
        verify(employeeRepository, times(1)).save(existingEmployee);
    }

    @Test
    @DisplayName("One bad record in a batch must not fail the entire batch")
    void shouldIsolateBadRecordWithoutFailingBatch() {
        EmployeeEventDto validEvent = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_VALID")
                .sourceVersion(1L)
                .fullName("Valid User")
                .email("valid.user@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        EmployeeEventDto badPhoneEvent = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_BAD_PHONE")
                .sourceVersion(1L)
                .fullName("Bad Phone User")
                .email("bad.phone@company.com")
                .phoneNumber("999") // Invalid phone number
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        EmployeeEventDto badOfficeEvent = EmployeeEventDto.builder()
                .eventType("CREATE")
                .employeeCode("EMP_BAD_OFFICE")
                .sourceVersion(1L)
                .fullName("Bad Office User")
                .email("bad.office@company.com")
                .phoneNumber("+919876543210")
                .officeCode("UNKNOWN_CAMPUS") // Unknown office code
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        when(idempotencyRecordRepository.findByTenantIdAndIdempotencyKey(eq(tenantId), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findForUpdateByTenantIdAndEmployeeCode(tenantId, "EMP_VALID"))
                .thenReturn(Optional.empty());

        BatchIngestRequestDto batchRequest = BatchIngestRequestDto.builder()
                .events(List.of(validEvent, badPhoneEvent, badOfficeEvent))
                .build();

        BatchIngestResponse response = ingestionService.ingestBatch(batchRequest);

        assertNotNull(response);
        assertEquals(3, response.getTotalReceived());
        assertEquals(1, response.getAppliedCount(), "Valid record must be applied");
        assertEquals(2, response.getRejectedCount(), "Bad records must be rejected individually");
        assertEquals(3, response.getResults().size());

        // Validate individual outcomes
        RecordResultDto validResult = response.getResults().stream()
                .filter(r -> "EMP_VALID".equals(r.getEmployeeCode())).findFirst().orElseThrow();
        assertEquals(ProcessingOutcome.APPLIED, validResult.getOutcome());
        assertTrue(validResult.getErrors().isEmpty());

        RecordResultDto badPhoneResult = response.getResults().stream()
                .filter(r -> "EMP_BAD_PHONE".equals(r.getEmployeeCode())).findFirst().orElseThrow();
        assertEquals(ProcessingOutcome.VALIDATION_FAILED, badPhoneResult.getOutcome());
        assertTrue(badPhoneResult.getErrors().stream().anyMatch(e -> "INVALID_PHONE_FORMAT".equals(e.getErrorCode())));

        RecordResultDto badOfficeResult = response.getResults().stream()
                .filter(r -> "EMP_BAD_OFFICE".equals(r.getEmployeeCode())).findFirst().orElseThrow();
        assertEquals(ProcessingOutcome.VALIDATION_FAILED, badOfficeResult.getOutcome());
        assertTrue(badOfficeResult.getErrors().stream().anyMatch(e -> "UNKNOWN_OFFICE_CODE".equals(e.getErrorCode())));
    }
}
