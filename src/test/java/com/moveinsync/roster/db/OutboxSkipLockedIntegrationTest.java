package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.service.OutboxPublisherService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * TEST 3 — SKIP LOCKED: two concurrent workers claim non-overlapping events.
 * TEST 4 — Lease Recovery: PROCESSING event with expired lease is re-claimed and published.
 *
 * <p>Uses real PostgreSQL. The outbox scheduler is disabled in application-test.yml
 * so tests invoke {@code claimEvents()} and {@code pollAndPublish()} manually.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxSkipLockedIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired OutboxPublisherService outboxPublisherService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    // =========================================================================
    // TEST 3: SKIP LOCKED — concurrent workers claim distinct, non-overlapping rows
    // =========================================================================
    @Test
    @DisplayName("TEST-3: FOR UPDATE SKIP LOCKED — two workers claim distinct, non-overlapping events")
    void test3_skipLockedNonOverlappingClaims() throws Exception {
        // Insert 6 PENDING outbox events, all immediately due
        List<OutboxEvent> seeded = TestDataFactory.persistPendingOutboxEvents(
                outboxEventRepository, TENANT_ID, 6);

        int batchSize = 3; // each worker claims up to 3
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<UUID> worker1Claims = new CopyOnWriteArrayList<>();
        List<UUID> worker2Claims = new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(2);

        exec.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                List<OutboxEvent> claimed = outboxPublisherService.claimEvents(batchSize);
                claimed.forEach(e -> worker1Claims.add(e.getId()));
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        exec.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                List<OutboxEvent> claimed = outboxPublisherService.claimEvents(batchSize);
                claimed.forEach(e -> worker2Claims.add(e.getId()));
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        exec.shutdown();
        exec.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(errors.get()).as("No claim exceptions").isZero();

        // Combined claims must cover all 6 seeded events
        Set<UUID> allClaimed = new HashSet<>(worker1Claims);
        allClaimed.addAll(worker2Claims);
        Set<UUID> seededIds = seeded.stream().map(OutboxEvent::getId).collect(Collectors.toSet());
        assertThat(allClaimed).containsExactlyInAnyOrderElementsOf(seededIds);

        // No overlap between workers (SKIP LOCKED guarantee)
        Set<UUID> intersection = new HashSet<>(worker1Claims);
        intersection.retainAll(worker2Claims);
        assertThat(intersection)
                .as("Workers must not claim the same event (SKIP LOCKED)")
                .isEmpty();

        // All claimed events must now be in PROCESSING status
        List<OutboxEvent> reloaded = outboxEventRepository.findAllById(allClaimed);
        reloaded.forEach(e ->
                assertThat(e.getStatus())
                        .as("Claimed event %s must be PROCESSING", e.getId())
                        .isEqualTo(OutboxStatus.PROCESSING));
    }

    // =========================================================================
    // TEST 4: Lease Recovery — PROCESSING event with expired lease is re-claimed
    // =========================================================================
    @Test
    @DisplayName("TEST-4: Lease recovery — expired PROCESSING event is re-claimed and published")
    void test4_expiredLeaseRecovery() throws Exception {
        // Seed one event as PROCESSING with next_retry_at already in the past
        OutboxEvent event = TestDataFactory.buildPendingOutboxEvent(TENANT_ID, "LEASE_EMP_001");
        event.setStatus(OutboxStatus.PROCESSING);
        event.setNextRetryAt(Instant.now().minusSeconds(10)); // expired lease
        event = outboxEventRepository.save(event);

        UUID eventId = event.getId();

        // Verify it's PROCESSING before re-claim
        OutboxEvent before = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(OutboxStatus.PROCESSING);

        // pollAndPublish should re-claim expired events because findEventsToClaim
        // includes: status = 'PROCESSING' AND next_retry_at <= now
        outboxPublisherService.pollAndPublish();

        // Give async operations a moment to complete (polling is synchronous in test context)
        Thread.sleep(200);

        // After publish, event must be PUBLISHED (not stuck in PROCESSING)
        OutboxEvent after = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(after.getStatus())
                .as("Expired PROCESSING event must be re-claimed and reach PUBLISHED state")
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.getPublishedAt())
                .as("publishedAt timestamp must be set")
                .isNotNull();
    }
}

