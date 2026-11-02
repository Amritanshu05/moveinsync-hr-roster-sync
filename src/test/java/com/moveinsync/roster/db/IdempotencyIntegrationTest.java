package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.IdempotencyRecord;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.RecordResultDto;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.IdempotencyRecordRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.EventIngestionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * TEST 1 — Concurrent Idempotency Reservation
 * TEST 5 — Transactional Rollback Atomicity
 *
 * <p>Both tests use real PostgreSQL (Testcontainers). No mocks for persistence.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdempotencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired EventIngestionService eventIngestionService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private UUID tenantId;
    private static final String TENANT_CODE = "IDEM_TEST_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setup() {
        // Use seeded TENANT_ALPHA so offices (BLR_ECOSPACE) are available
        tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TenantContext.setTenant(tenantId, "TENANT_ALPHA");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // TEST 1: Concurrent Idempotency — two threads, same event key
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("TEST-1: Concurrent idempotency reservation — exactly one winner, one DUPLICATE_NO_OP")
    void test1_concurrentIdempotencyReservation() throws Exception {
        String empCode = "IDEM_CONC_" + UUID.randomUUID().toString().substring(0, 6);
        long version = 1L;

        EmployeeEventDto event = TestDataFactory.buildCreateEvent(empCode, version);
        BatchIngestRequestDto request = TestDataFactory.singleEventBatch(event);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<BatchIngestResponse> responses = new CopyOnWriteArrayList<>();
        AtomicInteger exceptionCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(exec.submit(() -> {
                TenantContext.setTenant(tenantId, "TENANT_ALPHA");
                try {
                    barrier.await(5, TimeUnit.SECONDS); // synchronize start
                    BatchIngestResponse resp = eventIngestionService.ingestBatch(request);
                    responses.add(resp);
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    TenantContext.clear();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        exec.shutdown();

        // No exceptions should escape — idempotency handles the race at DB level
        assertThat(exceptionCount.get())
                .as("No exceptions should escape concurrent idempotency check")
                .isZero();

        // Both responses arrive; exactly one APPLIED and one DUPLICATE_NO_OP
        assertThat(responses).hasSize(2);
        long appliedCount = responses.stream()
                .flatMap(r -> r.getResults().stream())
                .filter(r -> r.getOutcome() == ProcessingOutcome.APPLIED)
                .count();
        long dupCount = responses.stream()
                .flatMap(r -> r.getResults().stream())
                .filter(r -> r.getOutcome() == ProcessingOutcome.DUPLICATE_NO_OP)
                .count();

        assertThat(appliedCount).as("Exactly one APPLIED outcome").isEqualTo(1);
        assertThat(dupCount).as("Exactly one DUPLICATE_NO_OP outcome").isEqualTo(1);

        // Only one employee row in DB
        long employeeRows = employeeRepository.findAll().stream()
                .filter(e -> e.getTenantId().equals(tenantId) && e.getEmployeeCode().equals(empCode))
                .count();
        assertThat(employeeRows).as("Exactly one employee row persisted").isEqualTo(1);

        // Only one idempotency record
        String idempKey = tenantId + ":" + empCode + ":" + version;
        long idempRows = idempotencyRecordRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId) && r.getIdempotencyKey().equals(idempKey))
                .count();
        assertThat(idempRows).as("Exactly one idempotency record in DB").isEqualTo(1);
    }

    // =========================================================================
    // TEST 5: Transactional Rollback — employee + outbox row must both be absent
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("TEST-5: Transactional rollback — employee and outbox rows absent after exception")
    void test5_transactionalRollbackAtomicity() {
        String empCode = "ROLLBACK_" + UUID.randomUUID().toString().substring(0, 6);

        long outboxBefore = outboxEventRepository.count();
        long employeeCountBefore = employeeRepository.findAll().stream()
                .filter(e -> e.getTenantId().equals(tenantId) && e.getEmployeeCode().equals(empCode))
                .count();
        assertThat(employeeCountBefore).isZero();

        // Execute a transaction that inserts an employee but then rolls back
        try {
            transactionTemplate.execute(status -> {
                // Manually persist an employee (simulating partial work)
                Employee emp = TestDataFactory.buildEmployee(tenantId, empCode, 1L);
                employeeRepository.save(emp);

                OutboxEvent outbox = TestDataFactory.buildPendingOutboxEvent(tenantId, empCode);
                outboxEventRepository.save(outbox);

                // Force rollback by setting rollback-only
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ex) {
            // rollback may propagate as an exception in some Spring configurations — acceptable
        }

        // Verify: employee row must not exist
        long employeeCountAfter = employeeRepository.findAll().stream()
                .filter(e -> e.getTenantId().equals(tenantId) && e.getEmployeeCode().equals(empCode))
                .count();
        assertThat(employeeCountAfter)
                .as("Employee row must be absent after transaction rollback")
                .isZero();

        // Verify: outbox row count must not have increased
        long outboxAfter = outboxEventRepository.count();
        assertThat(outboxAfter)
                .as("Outbox row count must not increase after rollback")
                .isEqualTo(outboxBefore);
    }
}

