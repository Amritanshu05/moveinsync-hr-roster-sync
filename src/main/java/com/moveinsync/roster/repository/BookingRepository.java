package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Booking;
import com.moveinsync.roster.domain.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByTenantIdAndEmployeeCodeAndBookingDateGreaterThanEqualAndStatus(
            UUID tenantId, String employeeCode, LocalDate bookingDate, BookingStatus status);

    List<Booking> findByTenantIdAndEmployeeCodeInAndBookingDateGreaterThanEqualAndStatus(
            UUID tenantId, Collection<String> employeeCodes, LocalDate bookingDate, BookingStatus status);

    @Modifying
    @Query("UPDATE Booking b SET b.status = :cancelledStatus, b.cancelledAt = :now, b.cancellationReason = :reason, b.updatedAt = :now " +
           "WHERE b.tenantId = :tenantId AND b.employeeCode = :employeeCode " +
           "AND b.bookingDate >= :fromDate AND b.status = :activeStatus")
    int cancelFutureBookingsForEmployee(@Param("tenantId") UUID tenantId,
                                       @Param("employeeCode") String employeeCode,
                                       @Param("fromDate") LocalDate fromDate,
                                       @Param("activeStatus") BookingStatus activeStatus,
                                       @Param("cancelledStatus") BookingStatus cancelledStatus,
                                       @Param("reason") String reason,
                                       @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Booking b SET b.status = :cancelledStatus, b.cancelledAt = :now, b.cancellationReason = :reason, b.updatedAt = :now " +
           "WHERE b.tenantId = :tenantId AND b.employeeCode IN :employeeCodes " +
           "AND b.bookingDate >= :fromDate AND b.status = :activeStatus")
    int cancelFutureBookingsForEmployeeBatch(@Param("tenantId") UUID tenantId,
                                            @Param("employeeCodes") Collection<String> employeeCodes,
                                            @Param("fromDate") LocalDate fromDate,
                                            @Param("activeStatus") BookingStatus activeStatus,
                                            @Param("cancelledStatus") BookingStatus cancelledStatus,
                                            @Param("reason") String reason,
                                            @Param("now") Instant now);
}
