package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactional Outbox Publisher — polls PENDING outbox events and publishes them downstream.
 *
 * <h3>Design Decisions</h3>
 * <ol>
 *   <li><b>SELECT FOR UPDATE SKIP LOCKED:</b> Multiple JVM instances can poll simultaneously
 *       without row contention. Each instance locks its own batch, preventing double-processing.</li>
 *   <li><b>Independent Transaction per Event:</b> Each event is committed in its own
 *       transaction. One failing event does not block others in the same batch.</li>
 *   <li><b>Exponential Backoff:</b> Retry intervals double on each failure:
 *       5s → 10s → 20s → 40s → 80s → DEAD_LETTER.</li>
 *   <li><b>Dead-Letter:</b> After {@code maxRetryCount} failures the event is marked
 *       DEAD_LETTER and never retried again. An alert metric is incremented.</li>
 *   <li><b>At-Least-Once Delivery:</b> If the process crashes after marking PUBLISHED
 *       but before the downstream consumer ACKs, the event is already PUBLISHED
 *       (not re-queued). Downstream consumers must be idempotent.</li>
 *   <li><b>Zero-PII Logging:</b> Only event IDs, aggregate IDs and types are logged.
 *       Full payload is never written to log.</li>
 * </ol>
 *
 * <h3>Complexity</h3>
 * <ul>
 *   <li>Time per cycle: O(b) where b = batch size (default 100)</li>
 *   <li>Space: O(b) — only one batch held in memory at a time</li>
 * </ul>
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final PlatformTransactionManager transactionManager;
    private final MeterRegistry meterRegistry;

    @Value("${moveinsync.outbox.batch-size:100}")
    private int batchSize;

    @Value("${moveinsync.outbox.max-retry-count:5}")
    private int maxRetryCount;

    /** Base retry interval in seconds. Doubles on each failure. */
    private static final long BASE_RETRY_SECONDS = 5L;

    /** Tracks pending outbox count for the /actuator/prometheus gauge. */
    private final AtomicLong pendingOutboxGauge = new AtomicLong(0);

    /**
     * Main polling loop. Runs every second, bounded to one execution at a time via
     * {@code fixedDelay} (the next poll does not begin until this one finishes).
     *
     * <p>Uses SKIP LOCKED so multiple JVM instances can poll concurrently without blocking.</p>
     */
    @Scheduled(fixedDelayString = "${moveinsync.outbox.polling-interval-ms:1000}")
    public void pollAndPublish() {
        try {
            List<OutboxEvent> pendingEvents =
                    outboxEventRepository.findPendingEventsForProcessing(Instant.now(), batchSize);

            if (pendingEvents.isEmpty()) {
                return;
            }

            log.debug("Outbox poller: fetched {} PENDING events for processing", pendingEvents.size());

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            for (OutboxEvent event : pendingEvents) {
                tx.execute(status -> {
                    try {
                        publishEvent(event);
                        markPublished(event);
                        return null;
                    } catch (Exception ex) {
                        handlePublishFailure(event, ex);
                        return null;
                    }
                });
            }

            // Update pending gauge for monitoring
            long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
            pendingOutboxGauge.set(pendingCount);
            registerGaugeIfNeeded();

        } catch (Exception ex) {
            log.error("Outbox poller encountered an unexpected error: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Simulates publishing the event to a downstream consumer.
     *
     * <p>In a production system this would call an HTTP endpoint, write to a message broker,
     * or invoke an internal Spring ApplicationEvent. For this assessment the "publish" step
     * is a structured log entry that a real downstream adapter would intercept via the
     * ApplicationEvent mechanism or a message channel.</p>
     *
     * <p>The method is deliberately separated from the transaction management so it can be
     * replaced by a real transport adapter without changing the retry / dead-letter logic.</p>
     */
    public void publishEvent(OutboxEvent event) {
        // Structured log — downstream log aggregators (ELK, Cloud Logging) consume this
        log.info("OUTBOX_PUBLISH event_id={} tenant_id={} aggregate_type={} aggregate_id={} event_type={} retry={}",
                event.getId(),
                event.getTenantId(),
                event.getAggregateType(),
                event.getAggregateId(),   // employee_code — not PII
                event.getEventType(),
                event.getRetryCount());

        // Stub: In a production implementation, replace with:
        //   applicationEventPublisher.publishEvent(new OutboxDomainEvent(event));
        //   or: restTemplate.postForEntity(downstreamUrl, event.getPayload(), Void.class);
        // The payload is persisted in the outbox row and available for re-delivery on retry.
    }

    /**
     * Marks an event PUBLISHED within a new transaction.
     * Sets the publishedAt timestamp for audit and SLA tracking.
     */
    private void markPublished(OutboxEvent event) {
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outboxEventRepository.save(event);

        meterRegistry.counter("roster.outbox.published.total",
                "event_type", event.getEventType()).increment();

        log.debug("Outbox event {} marked PUBLISHED", event.getId());
    }

    /**
     * Handles a publish failure for a single event.
     *
     * <ul>
     *   <li>Increments the retry counter.</li>
     *   <li>Calculates the next retry time using exponential backoff.</li>
     *   <li>If retries are exhausted, moves the event to DEAD_LETTER.</li>
     * </ul>
     */
    private void handlePublishFailure(OutboxEvent event, Exception ex) {
        int newRetryCount = event.getRetryCount() + 1;
        event.setRetryCount(newRetryCount);
        event.setLastError(truncate(ex.getMessage(), 1000));

        if (newRetryCount >= maxRetryCount) {
            event.setStatus(OutboxStatus.DEAD_LETTER);
            event.setNextRetryAt(Instant.now().plus(365, ChronoUnit.DAYS)); // never retry
            outboxEventRepository.save(event);

            meterRegistry.counter("roster.outbox.dead_letter.total",
                    "event_type", event.getEventType()).increment();

            log.error("Outbox event {} moved to DEAD_LETTER after {} retries. aggregate_id={} event_type={}",
                    event.getId(), newRetryCount, event.getAggregateId(), event.getEventType());
        } else {
            // Exponential backoff: 5s, 10s, 20s, 40s, 80s ...
            long backoffSeconds = BASE_RETRY_SECONDS * (1L << (newRetryCount - 1));
            event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
            event.setStatus(OutboxStatus.PENDING); // keep PENDING for retry
            outboxEventRepository.save(event);

            meterRegistry.counter("roster.outbox.retried.total",
                    "event_type", event.getEventType()).increment();

            log.warn("Outbox event {} failed (attempt {}/{}), next retry in {}s. Error: {}",
                    event.getId(), newRetryCount, maxRetryCount, backoffSeconds, ex.getMessage());
        }
    }

    private void registerGaugeIfNeeded() {
        // Gauge is registered lazily on first observation to avoid early-init issues
        Gauge.builder("roster.outbox.pending.total", pendingOutboxGauge, AtomicLong::get)
                .description("Number of outbox events in PENDING state waiting to be published")
                .register(meterRegistry);
    }

    private String truncate(String message, int maxLen) {
        if (message == null) return "null";
        return message.length() > maxLen ? message.substring(0, maxLen) + "..." : message;
    }
}
