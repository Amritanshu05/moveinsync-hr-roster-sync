package com.moveinsync.roster.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter eventsReceivedCounter(MeterRegistry registry) {
        return Counter.builder("roster.events.received.total")
                .description("Total number of HR roster sync events received")
                .register(registry);
    }

    @Bean
    public Counter eventsAppliedCounter(MeterRegistry registry) {
        return Counter.builder("roster.events.applied.total")
                .description("Total number of HR roster events successfully applied")
                .register(registry);
    }

    @Bean
    public Counter eventsStaleCounter(MeterRegistry registry) {
        return Counter.builder("roster.events.stale.total")
                .description("Total number of stale events ignored due to version monotonicity")
                .register(registry);
    }

    @Bean
    public Counter eventsDuplicateCounter(MeterRegistry registry) {
        return Counter.builder("roster.events.duplicate.total")
                .description("Total number of duplicate events handled as idempotent no-ops")
                .register(registry);
    }

    @Bean
    public Counter eventsRejectedCounter(MeterRegistry registry) {
        return Counter.builder("roster.events.rejected.total")
                .description("Total number of events rejected due to boundary validation failure")
                .register(registry);
    }

    @Bean
    public Counter safetyBrakeTriggeredCounter(MeterRegistry registry) {
        return Counter.builder("roster.safety_brake.triggered.total")
                .description("Total number of times nightly safety brake was triggered")
                .register(registry);
    }

    @Bean
    public Timer reconciliationTimer(MeterRegistry registry) {
        return Timer.builder("roster.reconciliation.duration")
                .description("Time taken to execute nightly diff reconciliation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer eventProcessingTimer(MeterRegistry registry) {
        return Timer.builder("roster.events.processing.time")
                .description("Latency of event processing")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
