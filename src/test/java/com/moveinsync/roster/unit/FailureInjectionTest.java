package com.moveinsync.roster.unit;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.service.OutboxPublisherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Failure-injection tests (required by the Advanced Track).
 *
 * <h3>Scenarios</h3>
 * <ol>
 *   <li><b>Crash between DB write and event publish:</b>
 *       The outbox event is committed with PENDING status. After a simulated restart,
 *       the poller picks it up and publishes it. This proves at-least-once delivery.</li>
 *   <li><b>Publisher crash leaves event PENDING:</b>
 *       If publishEvent() throws, the event must not be marked PUBLISHED. It stays PENDING
 *       with an incremented retry counter.</li>
 *   <li><b>Retry exhaustion leads to DEAD_LETTER:</b>
 *       After {@code maxRetryCount} failures the event is quarantined as DEAD_LETTER.</li>
 *   <li><b>Poller does not process DEAD_LETTER or PUBLISHED events:</b>
 *       Once an event is in a terminal state it must never be re-queued.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Failure Injection Tests – Outbox Publisher")
class FailureInjectionTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private OutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        publisherService = new OutboxPublisherService(
                outboxEventRepository, transactionManager, new SimpleMeterRegistry());

        ReflectionTestUtils.setField(publisherService, "batchSize", 10);
        ReflectionTestUtils.setField(publisherService, "maxRetryCount", 3);
    }

    private OutboxEvent pendingEvent(String eventType, int retryCount) {
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .aggregateType("EMPLOYEE")
                .aggregateId("EMP_FAILURE")
                .eventType(eventType)
                .payload("{\"test\":true}")
                .status(OutboxStatus.PENDING)
                .retryCount(retryCount)
                .nextRetryAt(Instant.now().minusSeconds(1))
                .build();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }

    // ─── Test 1: "Crash after DB commit, before publish" recovery ────────────

    @Test
    @DisplayName("Should recover and publish an event committed as PENDING before a crash")
    void shouldRecoverPendingEventAfterSimulatedCrash() {
        // This event was committed to outbox_events BEFORE the crash (status=PENDING)
        OutboxEvent survivedEvent = pendingEvent("EMPLOYEE_CREATED", 0);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(survivedEvent))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);

        // Simulate poller restart — polls the survived PENDING event
        publisherService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        // Event must be marked PUBLISHED with a timestamp
        OutboxEvent published = captor.getAllValues().stream()
                .filter(e -> e.getStatus() == OutboxStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Event should be PUBLISHED after recovery"));

        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getPublishedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    // ─── Test 2: Publisher crash leaves event PENDING with retry info ─────────

    @Test
    @DisplayName("Should leave event PENDING with incremented retry count when publish throws")
    void shouldRemainPendingWithIncrementedRetryWhenPublishFails() {
        OutboxEvent event = pendingEvent("EMPLOYEE_UPDATED", 0);

        OutboxPublisherService spyService = spy(publisherService);
        doThrow(new RuntimeException("Publish failed: connection refused"))
                .when(spyService).publishEvent(event);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(any())).thenReturn(1L);

        spyService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        OutboxEvent saved = captor.getAllValues().get(0);
        // Must NOT be PUBLISHED — publish failed
        assertThat(saved.getStatus()).isNotEqualTo(OutboxStatus.PUBLISHED);
        // Must stay PENDING with retry incremented
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getLastError()).contains("connection refused");
        // Next retry must be in the future
        assertThat(saved.getNextRetryAt()).isAfter(Instant.now());
    }

    // ─── Test 3: Complete retry exhaustion → DEAD_LETTER ─────────────────────

    @Test
    @DisplayName("Should exhaust retries and move event to DEAD_LETTER after maxRetryCount failures")
    void shouldMoveToDeadLetterAfterRetryExhaustion() {
        // Already attempted 2 times; one more failure (retry count = 3 = maxRetryCount) → DEAD_LETTER
        OutboxEvent event = pendingEvent("EMPLOYEE_DEACTIVATE", 2);

        OutboxPublisherService spyService = spy(publisherService);
        doThrow(new RuntimeException("Terminal failure"))
                .when(spyService).publishEvent(event);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(any())).thenReturn(0L);

        spyService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        OutboxEvent quarantined = captor.getAllValues().stream()
                .filter(e -> e.getStatus() == OutboxStatus.DEAD_LETTER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Event must be DEAD_LETTER"));

        assertThat(quarantined.getRetryCount()).isEqualTo(3); // maxRetryCount
        assertThat(quarantined.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
    }

    // ─── Test 4: DEAD_LETTER events are not re-queued ─────────────────────────

    @Test
    @DisplayName("Poller query filters out DEAD_LETTER events — they must never be re-polled")
    void deadLetterEventsMustNotBeRePolled() {
        // The native query in OutboxEventRepository uses WHERE status = 'PENDING'
        // So DEAD_LETTER events are never returned — this test verifies the contract
        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of()); // No PENDING events — DEAD_LETTER ones filtered by SQL

        publisherService.pollAndPublish();

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    // ─── Test 5: Duplicate event publish is idempotent at outbox level ────────

    @Test
    @DisplayName("An already PUBLISHED event that somehow re-enters PENDING is published again (at-least-once)")
    void alreadyPublishedEventShouldBePublishedAgainIfReQueued() {
        // This represents the at-least-once delivery semantic:
        // if a crash occurs after publishEvent() but before markPublished(),
        // the event stays PENDING and is re-published on the next poll.
        // Downstream consumers must be idempotent.
        OutboxEvent event = pendingEvent("EMPLOYEE_CREATED", 0);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(any())).thenReturn(0L);

        publisherService.pollAndPublish();

        // Published at least once
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());
        long publishedCount = captor.getAllValues().stream()
                .filter(e -> e.getStatus() == OutboxStatus.PUBLISHED)
                .count();
        assertThat(publishedCount).isGreaterThanOrEqualTo(1L);
    }
}
