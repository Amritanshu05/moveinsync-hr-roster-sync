package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.EventIngestionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * TEST 2 — Employee Row-Level Pessimistic Locking (PESSIMISTIC_WRITE)
 *
 * <p>Verifies that out-of-order events for the same employee are correctly resolved
 * using PostgreSQL row-level locking, even under concurrent delivery.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmployeeRowLockingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired EventIngestionService eventIngestionService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired TenantRepository tenantRepository;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TenantContext.setTenant(tenantId, "TENANT_ALPHA");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // TEST 2a: Out-of-order events — version 7 arrives before version 6
    // Final state must retain version 7 (newer wins); version 6 is stale-ignored
    // =========================================================================
    @Test
    @DisplayName("TEST-2a: Out-of-order version — version 7 applied first, version 6 must be ignored")
    void test2a_staleVersionIgnoredAfterNewerApplied() throws Exception {
        String empCode = "LOCK_OOO_" + UUID.randomUUID().toString().substring(0, 6);

        // Seed employee at version 5
        TestDataFactory.persistEmployee(employeeRepository, tenantId, empCode, 5L);

        // Deliver version 7 first (newest)
        EmployeeEventDto v7 = TestDataFactory.buildUpdateEvent(empCode, 7L, "EVENING");
        BatchIngestResponse respV7 = eventIngestionService.ingestBatch(
                TestDataFactory.singleEventBatch(v7));

        assertThat(respV7.getResults().get(0).getOutcome())
                .as("Version 7 must be APPLIED")
                .isEqualTo(ProcessingOutcome.APPLIED);

        // Deliver version 6 (stale relative to applied version 7)
        EmployeeEventDto v6 = TestDataFactory.buildUpdateEvent(empCode, 6L, "NIGHT");
        BatchIngestResponse respV6 = eventIngestionService.ingestBatch(
                TestDataFactory.singleEventBatch(v6));

        assertThat(respV6.getResults().get(0).getOutcome())
                .as("Version 6 must be STALE_IGNORED after version 7 was applied")
                .isEqualTo(ProcessingOutcome.STALE_IGNORED);

        // Final state: version 7, shift from v7
        Optional<Employee> saved = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, empCode);
        assertThat(saved).isPresent();
        assertThat(saved.get().getSourceVersion())
                .as("Final source_version must be 7")
                .isEqualTo(7L);
        assertThat(saved.get().getShiftCode())
                .as("Shift code must reflect version 7 (EVENING), not version 6 (NIGHT)")
                .isEqualTo("EVENING");
    }

    // =========================================================================
    // TEST 2b: Concurrent updates for the SAME employee — no data corruption
    // Two threads race to apply versions 10 and 8; version 10 wins.
    // =========================================================================
    @Test
    @DisplayName("TEST-2b: Concurrent updates same employee — higher version wins, no corruption")
    void test2b_concurrentUpdatesForSameEmployee() throws Exception {
        String empCode = "LOCK_CONC_" + UUID.randomUUID().toString().substring(0, 6);

        // Seed at version 5
        TestDataFactory.persistEmployee(employeeRepository, tenantId, empCode, 5L);

        EmployeeEventDto v10 = TestDataFactory.buildUpdateEvent(empCode, 10L, "AFTERNOON");
        EmployeeEventDto v8  = TestDataFactory.buildUpdateEvent(empCode, 8L, "MORNING");

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<BatchIngestResponse> responses = new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);
        ExecutorService exec = Executors.newFixedThreadPool(2);

        exec.submit(() -> {
            TenantContext.setTenant(tenantId, "TENANT_ALPHA");
            try {
                barrier.await(5, TimeUnit.SECONDS);
                responses.add(eventIngestionService.ingestBatch(TestDataFactory.singleEventBatch(v10)));
            } catch (Exception e) { errors.incrementAndGet(); }
            finally { TenantContext.clear(); }
        });

        exec.submit(() -> {
            TenantContext.setTenant(tenantId, "TENANT_ALPHA");
            try {
                barrier.await(5, TimeUnit.SECONDS);
                responses.add(eventIngestionService.ingestBatch(TestDataFactory.singleEventBatch(v8)));
            } catch (Exception e) { errors.incrementAndGet(); }
            finally { TenantContext.clear(); }
        });

        exec.shutdown();
        exec.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(errors.get()).as("No exceptions in concurrent update").isZero();
        assertThat(responses).hasSize(2);

        // Regardless of execution order, final version must be 10
        Optional<Employee> emp = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, empCode);
        assertThat(emp).isPresent();
        assertThat(emp.get().getSourceVersion())
                .as("Final version must be 10 (highest)")
                .isEqualTo(10L);
        assertThat(emp.get().getShiftCode())
                .as("Shift code must be AFTERNOON (from version 10)")
                .isEqualTo("AFTERNOON");
    }

    // =========================================================================
    // TEST 2c: Independent employees processed in parallel without interference
    // =========================================================================
    @Test
    @DisplayName("TEST-2c: Independent employees are processed in parallel without cross-contamination")
    void test2c_independentEmployeesParallelProcessing() throws Exception {
        String empCodeA = "LOCK_IND_A_" + UUID.randomUUID().toString().substring(0, 5);
        String empCodeB = "LOCK_IND_B_" + UUID.randomUUID().toString().substring(0, 5);

        EmployeeEventDto createA = TestDataFactory.buildCreateEvent(empCodeA, 1L);
        EmployeeEventDto createB = TestDataFactory.buildCreateEvent(empCodeB, 1L);

        AtomicInteger errors = new AtomicInteger(0);
        List<BatchIngestResponse> responses = new CopyOnWriteArrayList<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService exec = Executors.newFixedThreadPool(2);

        exec.submit(() -> {
            TenantContext.setTenant(tenantId, "TENANT_ALPHA");
            try {
                barrier.await(5, TimeUnit.SECONDS);
                responses.add(eventIngestionService.ingestBatch(TestDataFactory.singleEventBatch(createA)));
            } catch (Exception e) { errors.incrementAndGet(); }
            finally { TenantContext.clear(); }
        });

        exec.submit(() -> {
            TenantContext.setTenant(tenantId, "TENANT_ALPHA");
            try {
                barrier.await(5, TimeUnit.SECONDS);
                responses.add(eventIngestionService.ingestBatch(TestDataFactory.singleEventBatch(createB)));
            } catch (Exception e) { errors.incrementAndGet(); }
            finally { TenantContext.clear(); }
        });

        exec.shutdown();
        exec.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(errors.get()).isZero();
        assertThat(responses).hasSize(2);

        // Both employees must be created independently
        assertThat(employeeRepository.findByTenantIdAndEmployeeCode(tenantId, empCodeA)).isPresent();
        assertThat(employeeRepository.findByTenantIdAndEmployeeCode(tenantId, empCodeB)).isPresent();
    }
}

