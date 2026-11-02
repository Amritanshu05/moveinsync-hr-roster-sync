package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.Booking;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.Seat;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.BookingStatus;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.domain.enums.SeatStatus;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.SeatRepository;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.ReconciliationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * TEST 6 — Reconciliation Deactivation Consistency & Safety-Brake Verification
 *
 * <p>Uses real PostgreSQL via Testcontainers.
 * Validates:
 * <ol>
 *   <li>Missing active employee is deactivated.</li>
 *   <li>Future bookings for the deactivated employee are CANCELLED.</li>
 *   <li>Reserved seats are FREED.</li>
 *   <li>Exactly one EMPLOYEE_DEACTIVATE outbox event is emitted.</li>
 *   <li>Already inactive employees do not receive duplicate deactivation events.</li>
 *   <li>Safety brake halts deactivations when missing employee ratio exceeds threshold (30%).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired OfficeRepository officeRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // TEST 6a: Normal deactivation below safety-brake threshold
    // 5 active employees in DB. 4 in CSV, 1 missing (20% missing < 30% threshold).
    // Plus 1 already-inactive employee (should not receive duplicate deactivation event).
    // =========================================================================
    @Test
    @DisplayName("TEST-6a: Missing employee deactivated, bookings cancelled, seats freed, exactly one outbox event")
    void test6a_deactivationConsistencyAndCascadingCancellation() {
        // Create an isolated tenant for clean missing ratio control
        String tenantCode = "REC_A_" + UUID.randomUUID().toString().substring(0, 6);
        Tenant tenant = TestDataFactory.createAndPersistTenant(tenantRepository, tenantCode, "Recon A Tenant");
        UUID tenantId = tenant.getId();
        TestDataFactory.persistOffice(officeRepository, tenantId, "BLR_ECOSPACE");
        TenantContext.setTenant(tenantId, tenantCode);

        String missingEmp = "RECON_MISS_" + UUID.randomUUID().toString().substring(0, 5);
        String keptEmp1 = "RECON_KEPT1_" + UUID.randomUUID().toString().substring(0, 5);
        String keptEmp2 = "RECON_KEPT2_" + UUID.randomUUID().toString().substring(0, 5);
        String keptEmp3 = "RECON_KEPT3_" + UUID.randomUUID().toString().substring(0, 5);
        String keptEmp4 = "RECON_KEPT4_" + UUID.randomUUID().toString().substring(0, 5);
        String alreadyInactive = "RECON_INACT_" + UUID.randomUUID().toString().substring(0, 5);

        // Seed exactly 5 active employees in this tenant
        TestDataFactory.persistEmployee(employeeRepository, tenantId, missingEmp, 1L);
        TestDataFactory.persistEmployee(employeeRepository, tenantId, keptEmp1, 1L);
        TestDataFactory.persistEmployee(employeeRepository, tenantId, keptEmp2, 1L);
        TestDataFactory.persistEmployee(employeeRepository, tenantId, keptEmp3, 1L);
        TestDataFactory.persistEmployee(employeeRepository, tenantId, keptEmp4, 1L);

        // Seed 1 already-inactive employee
        TestDataFactory.persistInactiveEmployee(employeeRepository, tenantId, alreadyInactive);

        // Create future booking and seat for missingEmp
        Booking booking = Booking.builder()
                .tenantId(tenantId)
                .employeeCode(missingEmp)
                .cabId("CAB_101")
                .bookingDate(LocalDate.now().plusDays(1)) // future
                .shiftTime("09:00")
                .status(BookingStatus.CONFIRMED)
                .build();
        booking = bookingRepository.save(booking);

        Seat seat = Seat.builder()
                .tenantId(tenantId)
                .bookingId(booking.getId())
                .cabId("CAB_101")
                .seatNumber("S1")
                .status(SeatStatus.RESERVED)
                .build();
        seatRepository.save(seat);

        // CSV contains 4 kept employees (missingEmp is omitted -> 1/5 = 20% missing, below 30% brake)
        String csvContent = "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n"
                + keptEmp1 + ",User " + keptEmp1 + "," + keptEmp1.toLowerCase() + "@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n"
                + keptEmp2 + ",User " + keptEmp2 + "," + keptEmp2.toLowerCase() + "@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n"
                + keptEmp3 + ",User " + keptEmp3 + "," + keptEmp3.toLowerCase() + "@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n"
                + keptEmp4 + ",User " + keptEmp4 + "," + keptEmp4.toLowerCase() + "@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n";

        MockMultipartFile csvFile = new MockMultipartFile(
                "file", "nightly_roster.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        // Execute reconciliation
        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(csvFile);

        // Verify job completed without triggering safety brake
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.isSafetyBrakeTriggered()).isFalse();

        // 1. Verify missing employee was deactivated in DB
        Optional<Employee> emp = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, missingEmp);
        assertThat(emp).isPresent();
        assertThat(emp.get().getStatus())
                .as("Missing employee must be set to INACTIVE")
                .isEqualTo(EmployeeStatus.INACTIVE);

        // 2. Verify future booking is CANCELLED
        Booking updatedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updatedBooking.getStatus())
                .as("Future booking must be CANCELLED")
                .isEqualTo(BookingStatus.CANCELLED);

        // 3. Verify cab seat is FREED
        Seat updatedSeat = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(updatedSeat.getStatus())
                .as("Reserved seat must be FREED")
                .isEqualTo(SeatStatus.FREED);

        // 4. Verify exactly one deactivation outbox event created for missingEmp
        List<OutboxEvent> deactEvents = outboxEventRepository.findAll().stream()
                .filter(e -> tenantId.equals(e.getTenantId())
                        && missingEmp.equals(e.getAggregateId())
                        && "EMPLOYEE_DEACTIVATE".equals(e.getEventType()))
                .toList();
        assertThat(deactEvents)
                .as("Exactly one EMPLOYEE_DEACTIVATE outbox event must be created")
                .hasSize(1);
        assertThat(deactEvents.get(0).getTenantId()).isEqualTo(tenantId);

        // 5. Already inactive employee must NOT have an outbox deactivation event
        long inactiveEvents = outboxEventRepository.findAll().stream()
                .filter(e -> tenantId.equals(e.getTenantId())
                        && alreadyInactive.equals(e.getAggregateId())
                        && "EMPLOYEE_DEACTIVATE".equals(e.getEventType()))
                .count();
        assertThat(inactiveEvents)
                .as("Already inactive employee must not receive duplicate deactivation event")
                .isZero();
    }

    // =========================================================================
    // TEST 6b: Safety brake triggers when missing ratio >= 30%
    // 10 active employees in DB. 6 in CSV, 4 missing (40% missing >= 30% brake).
    // Safety brake halts deactivation; all 4 remain ACTIVE.
    // =========================================================================
    @Test
    @DisplayName("TEST-6b: Safety brake halts deactivations when missing ratio exceeds 30%")
    void test6b_safetyBrakePreventsDeactivations() {
        // Create an isolated tenant for clean missing ratio control
        String tenantCode = "REC_B_" + UUID.randomUUID().toString().substring(0, 6);
        Tenant tenant = TestDataFactory.createAndPersistTenant(tenantRepository, tenantCode, "Recon B Tenant");
        UUID tenantId = tenant.getId();
        TestDataFactory.persistOffice(officeRepository, tenantId, "BLR_ECOSPACE");
        TenantContext.setTenant(tenantId, tenantCode);

        String pfx = "BRAKE_" + UUID.randomUUID().toString().substring(0, 4) + "_";

        // Seed 10 active employees
        for (int i = 1; i <= 10; i++) {
            TestDataFactory.persistEmployee(employeeRepository, tenantId, pfx + i, 1L);
        }

        // CSV includes only employees 1 to 6 (40% missing: employees 7, 8, 9, 10)
        StringBuilder sb = new StringBuilder(
                "employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");
        for (int i = 1; i <= 6; i++) {
            sb.append(pfx).append(i).append(",User ").append(pfx).append(i)
              .append(",").append(pfx.toLowerCase()).append(i).append("@example.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n");
        }

        MockMultipartFile csvFile = new MockMultipartFile(
                "file", "safety_brake_test.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));

        // Execute reconciliation
        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(csvFile);

        // Safety brake must trigger
        assertThat(result.isSafetyBrakeTriggered())
                .as("Safety brake must be triggered when missing ratio exceeds 30%")
                .isTrue();
        assertThat(result.getStatus())
                .isEqualTo(ReconciliationJobStatus.SAFETY_BRAKE_TRIGGERED);

        // Verify NONE of the missing employees were deactivated
        for (int i = 7; i <= 10; i++) {
            Optional<Employee> emp = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, pfx + i);
            assertThat(emp).isPresent();
            assertThat(emp.get().getStatus())
                    .as("Employee %s must remain ACTIVE because safety brake triggered", pfx + i)
                    .isEqualTo(EmployeeStatus.ACTIVE);
        }

        // Verify NO deactivation outbox events were created for them
        for (int i = 7; i <= 10; i++) {
            String code = pfx + i;
            long deactEvents = outboxEventRepository.findAll().stream()
                    .filter(e -> tenantId.equals(e.getTenantId())
                            && code.equals(e.getAggregateId())
                            && "EMPLOYEE_DEACTIVATE".equals(e.getEventType()))
                    .count();
            assertThat(deactEvents).as("No deactivation outbox event for " + code).isZero();
        }
    }
}
