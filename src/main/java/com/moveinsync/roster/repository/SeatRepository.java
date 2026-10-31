package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Seat;
import com.moveinsync.roster.domain.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByTenantIdAndBookingId(UUID tenantId, UUID bookingId);

    @Modifying
    @Query("UPDATE Seat s SET s.status = :freedStatus, s.freedAt = :now, s.updatedAt = :now " +
           "WHERE s.tenantId = :tenantId AND s.bookingId IN :bookingIds AND s.status = :reservedStatus")
    int freeSeatsForBookings(@Param("tenantId") UUID tenantId,
                            @Param("bookingIds") Collection<UUID> bookingIds,
                            @Param("reservedStatus") SeatStatus reservedStatus,
                            @Param("freedStatus") SeatStatus freedStatus,
                            @Param("now") Instant now);
}
