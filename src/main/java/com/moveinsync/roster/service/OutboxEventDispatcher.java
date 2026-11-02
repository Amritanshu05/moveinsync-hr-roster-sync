package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;

/**
 * Interface for outbox event delivery to downstream consumers.
 *
 * <p>Case Study 5 Section IV ("Downstream Effects") mandates reliable change publishing via
 * the transactional outbox pattern to ensure database mutations and downstream notifications
 * never disagree. The specific external transport mechanism (e.g., Kafka, RabbitMQ, SQS,
 * or HTTP Webhook) is deliberately left unspecified by the assessment's modular monolith architecture.</p>
 *
 * <p>This interface decouples the multi-worker claiming, leasing, retry, and dead-letter
 * lifecycle in {@link OutboxPublisherService} from the physical delivery transport.</p>
 */
public interface OutboxEventDispatcher {

    /**
     * Dispatches an outbox event to downstream consumers.
     *
     * @param event the outbox event to publish
     * @throws Exception if downstream delivery fails, triggering retry/backoff
     */
    void dispatch(OutboxEvent event) throws Exception;
}
