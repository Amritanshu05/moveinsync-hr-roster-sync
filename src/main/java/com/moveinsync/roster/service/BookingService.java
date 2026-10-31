package com.moveinsync.roster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.entity.Booking;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.BookingStatus;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.domain.enums.SeatStatus;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Cancels future bookings and frees reserved cab seats when an employee is deactivated.
     * Executes in the caller's active database transaction (MANDATORY/REQUIRED).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelFutureBookingsAndFreeSeats(UUID tenantId, String employeeCode, String reason) {
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        List<Booking> futureBookings = bookingRepository
                .findByTenantIdAndEmployeeCodeAndBookingDateGreaterThanEqualAndStatus(
                        tenantId, employeeCode, today, BookingStatus.CONFIRMED);

        if (futureBookings.isEmpty()) {
            log.debug("No active future bookings to cancel for employee: {}", employeeCode);
            return 0;
        }

        List<UUID> bookingIds = futureBookings.stream().map(Booking::getId).toList();

        // 1. Free cab seats
        int freedSeats = seatRepository.freeSeatsForBookings(
                tenantId, bookingIds, SeatStatus.RESERVED, SeatStatus.FREED, now);

        // 2. Cancel bookings
        int cancelledBookings = bookingRepository.cancelFutureBookingsForEmployee(
                tenantId, employeeCode, today, BookingStatus.CONFIRMED, BookingStatus.CANCELLED, reason, now);

        // 3. Emit downstream Outbox Event
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "employeeCode", employeeCode,
                    "cancelledBookingsCount", cancelledBookings,
                    "freedSeatsCount", freedSeats,
                    "reason", reason,
                    "cancelledAt", now.toString()
            ));

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .tenantId(tenantId)
                    .aggregateType("BOOKING")
                    .aggregateId(employeeCode)
                    .eventType("BOOKINGS_CANCELLED_DEACTIVATION")
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .nextRetryAt(now)
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to serialize booking cancellation outbox event for employee: {}", employeeCode, ex);
            throw new IllegalStateException("Outbox serialization failure", ex);
        }

        log.info("Cancelled {} future bookings and freed {} seats for deactivated employee: {}",
                cancelledBookings, freedSeats, employeeCode);

        return cancelledBookings;
    }
}
