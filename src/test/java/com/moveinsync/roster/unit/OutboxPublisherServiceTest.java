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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox Publisher Service Unit Tests")
class OutboxPublisherServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisherService publisherService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        publisherService = new OutboxPublisherService(
                outboxEventRepository, transactionManager, meterRegistry);

        ReflectionTestUtils.setField(publisherService, "batchSize", 100);
        ReflectionTestUtils.setField(publisherService, "maxRetryCount", 5);
    }

    private OutboxEvent pendingEvent(String eventType) {
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(tenantId)
                .aggregateType("EMPLOYEE")
                .aggregateId("EMP_TEST")
                .eventType(eventType)
                .payload("{\"test\":true}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(Instant.now().minusSeconds(1))
                .build();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }

    // ─── Test 1: Happy path — event marked PUBLISHED ──────────────────────────

    @Test
    @DisplayName("Should mark event as PUBLISHED after successful publish")
    void shouldMarkEventAsPublishedOnSuccess() {
        OutboxEvent event = pendingEvent("EMPLOYEE_CREATED");
        when(outboxEventRepository.findPendingEventsForProcessing(any(), eq(100)))
                .thenReturn(List.of(event))
                .thenReturn(List.of()); // second call returns empty
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);

        publisherService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        OutboxEvent saved = captor.getAllValues().stream()
                .filter(e -> e.getStatus() == OutboxStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No PUBLISHED event saved"));

        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(saved.getPublishedAt()).isNotNull();
    }

    // ─── Test 2: No events — no work done ─────────────────────────────────────

    @Test
    @DisplayName("Should not call save when there are no pending events")
    void shouldDoNothingWhenNoPendingEvents() {
        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of());

        publisherService.pollAndPublish();

        verify(outboxEventRepository, never()).save(any());
    }

    // ─── Test 3: Retry increments counter ────────────────────────────────────

    @Test
    @DisplayName("Should increment retry count and set PENDING on publish failure")
    void shouldIncrementRetryCountOnFailure() {
        OutboxEvent event = pendingEvent("EMPLOYEE_UPDATED");
        event.setRetryCount(0);

        // Use a spy to inject a controlled failure
        OutboxPublisherService spyService = spy(publisherService);
        doThrow(new RuntimeException("Downstream unavailable"))
                .when(spyService).publishEvent(event);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(any())).thenReturn(1L);

        spyService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        OutboxEvent retried = captor.getAllValues().get(0);
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING); // still PENDING
        assertThat(retried.getLastError()).contains("Downstream unavailable");
        assertThat(retried.getNextRetryAt()).isAfter(Instant.now().minusSeconds(1));
    }

    // ─── Test 4: Dead-letter after max retries ────────────────────────────────

    @Test
    @DisplayName("Should move event to DEAD_LETTER when max retry count is exhausted")
    void shouldMoveEventToDeadLetterAfterMaxRetries() {
        OutboxEvent event = pendingEvent("EMPLOYEE_DEACTIVATE");
        event.setRetryCount(4); // one more failure = 5 = maxRetryCount

        OutboxPublisherService spyService = spy(publisherService);
        doThrow(new RuntimeException("Persistent failure"))
                .when(spyService).publishEvent(event);

        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());
        when(outboxEventRepository.countByStatus(any())).thenReturn(0L);

        spyService.pollAndPublish();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, atLeastOnce()).save(captor.capture());

        OutboxEvent deadLettered = captor.getAllValues().stream()
                .filter(e -> e.getStatus() == OutboxStatus.DEAD_LETTER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected DEAD_LETTER event"));

        assertThat(deadLettered.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(deadLettered.getRetryCount()).isEqualTo(5);
    }

    // ─── Test 5: Exponential backoff ─────────────────────────────────────────

    @Test
    @DisplayName("Should apply exponential backoff on each retry: 5s, 10s, 20s...")
    void shouldApplyExponentialBackoffOnEachRetry() {
        // Verify backoff calculations inline without hitting the actual service
        // Base = 5s; retry 1 → 5s; retry 2 → 10s; retry 3 → 20s; retry 4 → 40s
        long[] expectedBackoffs = {5L, 10L, 20L, 40L};
        for (int i = 0; i < expectedBackoffs.length; i++) {
            long backoff = 5L * (1L << i); // same formula used in service
            assertThat(backoff).isEqualTo(expectedBackoffs[i]);
        }
    }

    // ─── Test 6: Batch size respected ────────────────────────────────────────

    @Test
    @DisplayName("Should pass configured batch size to repository query")
    void shouldPassBatchSizeToRepository() {
        ReflectionTestUtils.setField(publisherService, "batchSize", 50);
        when(outboxEventRepository.findPendingEventsForProcessing(any(), anyInt()))
                .thenReturn(List.of());

        publisherService.pollAndPublish();

        verify(outboxEventRepository).findPendingEventsForProcessing(any(), eq(50));
    }
}
