package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cab_seats", indexes = {
    @Index(name = "idx_seats_tenant_booking", columnList = "tenant_id, booking_id")
})
public class Seat extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "cab_id", nullable = false, length = 64)
    private String cabId;

    @Column(name = "seat_number", nullable = false, length = 32)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private SeatStatus status = SeatStatus.RESERVED;

    @Column(name = "freed_at")
    private Instant freedAt;
}
