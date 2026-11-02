package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link OutboxEventDispatcher}.
 *
 * <p>Publishes an internal Spring ApplicationEvent for local event listeners and outputs
 * structured JSON log events for centralized log shippers (Elasticsearch, CloudWatch).
 * In a distributed architecture with a message broker, this bean can be substituted
 * by a Kafka or RabbitMQ dispatcher.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultOutboxEventDispatcher implements OutboxEventDispatcher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void dispatch(OutboxEvent event) throws Exception {
        // Structured logging for downstream log aggregation
        log.info("OUTBOX_DISPATCH event_id={} tenant_id={} aggregate_type={} aggregate_id={} event_type={} retry={}",
                event.getId(),
                event.getTenantId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getRetryCount());

        // Emit Spring application event for in-process modular monolith consumers
        applicationEventPublisher.publishEvent(event);
    }
}
