package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cab_bookings", indexes = {
    @Index(name = "idx_bookings_tenant_emp", columnList = "tenant_id, employee_code, booking_date, status"),
    @Index(name = "idx_bookings_date_status", columnList = "tenant_id, booking_date, status")
})
public class Booking extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_code", nullable = false, length = 64)
    private String employeeCode;

    @Column(name = "cab_id", nullable = false, length = 64)
    private String cabId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "shift_time", nullable = false, length = 32)
    private String shiftTime;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;
}
