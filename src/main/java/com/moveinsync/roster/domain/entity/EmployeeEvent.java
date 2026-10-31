package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.ProcessingOutcome;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "employee_events", indexes = {
    @Index(name = "idx_emp_events_lookup", columnList = "tenant_id, employee_code, source_version"),
    @Index(name = "idx_emp_events_event_id", columnList = "tenant_id, event_id")
})
public class EmployeeEvent implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "employee_code", nullable = false, length = 64)
    private String employeeCode;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "source_version", nullable = false)
    private Long sourceVersion;

    @Column(name = "source_timestamp", nullable = false)
    private Instant sourceTimestamp;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Builder.Default
    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_outcome", length = 32)
    private ProcessingOutcome processingOutcome;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
