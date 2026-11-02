package com.moveinsync.roster.db;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.util.HashUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Factory helpers shared across real-DB integration tests.
 * All methods produce fully-wired JPA entities ready to persist.
 */
public final class TestDataFactory {

    private TestDataFactory() {
    }

    // -------------------------------------------------------------------------
    // Tenant
    // -------------------------------------------------------------------------

    public static Tenant createAndPersistTenant(TenantRepository repo, String code, String name) {
        Tenant tenant = Tenant.builder()
                .tenantCode(code)
                .name(name)
                .active(true)
                .build();
        return repo.save(tenant);
    }

    public static com.moveinsync.roster.domain.entity.Office persistOffice(
            com.moveinsync.roster.repository.OfficeRepository repo, UUID tenantId, String officeCode) {
        com.moveinsync.roster.domain.entity.Office office = com.moveinsync.roster.domain.entity.Office.builder()
                .tenantId(tenantId)
                .officeCode(officeCode)
                .officeName(officeCode + " Branch")
                .city("Bengaluru")
                .active(true)
                .build();
        return repo.save(office);
    }

    // -------------------------------------------------------------------------
    // Employee
    // -------------------------------------------------------------------------

    public static Employee buildEmployee(UUID tenantId, String employeeCode, long sourceVersion) {
        String fullName = "User " + employeeCode;
        String email = employeeCode.toLowerCase() + "@example.com";
        String phone = "+919876543210";
        String officeCode = "BLR_ECOSPACE";
        String shiftCode = "MORNING";

        long hash = HashUtils.calculateHrRecordHash(
                fullName, email, phone, null, officeCode, shiftCode, "ACTIVE");

        return Employee.builder()
                .tenantId(tenantId)
                .employeeCode(employeeCode)
                .fullName(fullName)
                .email(email)
                .phoneNumber(phone)
                .officeCode(officeCode)
                .shiftCode(shiftCode)
                .status(EmployeeStatus.ACTIVE)
                .sourceVersion(sourceVersion)
                .sourceTimestamp(Instant.now())
                .recordHash(hash)
                .specialAssistance(false)
                .build();
    }

    public static Employee persistEmployee(EmployeeRepository repo, UUID tenantId,
                                           String employeeCode, long sourceVersion) {
        return repo.save(buildEmployee(tenantId, employeeCode, sourceVersion));
    }

    public static Employee persistInactiveEmployee(EmployeeRepository repo, UUID tenantId,
                                                   String employeeCode) {
        Employee emp = buildEmployee(tenantId, employeeCode, 1L);
        emp.setStatus(EmployeeStatus.INACTIVE);
        return repo.save(emp);
    }

    // -------------------------------------------------------------------------
    // EmployeeEventDto (for ingestBatch)
    // -------------------------------------------------------------------------

    public static EmployeeEventDto buildCreateEvent(String employeeCode, long version) {
        return EmployeeEventDto.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("CREATE")
                .employeeCode(employeeCode)
                .sourceVersion(version)
                .sourceTimestamp(Instant.now())
                .fullName("User " + employeeCode)
                .email(employeeCode.toLowerCase() + "@example.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("MORNING")
                .status("ACTIVE")
                .build();
    }

    public static EmployeeEventDto buildUpdateEvent(String employeeCode, long version,
                                                    String newShiftCode) {
        return EmployeeEventDto.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("UPDATE")
                .employeeCode(employeeCode)
                .sourceVersion(version)
                .sourceTimestamp(Instant.now())
                .fullName("User " + employeeCode)
                .email(employeeCode.toLowerCase() + "@example.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode(newShiftCode)
                .status("ACTIVE")
                .build();
    }

    public static BatchIngestRequestDto singleEventBatch(EmployeeEventDto event) {
        return BatchIngestRequestDto.builder()
                .events(List.of(event))
                .build();
    }

    // -------------------------------------------------------------------------
    // OutboxEvent
    // -------------------------------------------------------------------------

    public static OutboxEvent buildPendingOutboxEvent(UUID tenantId, String aggregateId) {
        return OutboxEvent.builder()
                .tenantId(tenantId)
                .aggregateType("EMPLOYEE")
                .aggregateId(aggregateId)
                .eventType("EMPLOYEE_UPDATED")
                .payload("{\"employeeCode\":\"" + aggregateId + "\"}")
                .status(OutboxStatus.PENDING)
                .nextRetryAt(Instant.now().minusSeconds(1)) // immediately due
                .build();
    }

    public static List<OutboxEvent> persistPendingOutboxEvents(OutboxEventRepository repo,
                                                               UUID tenantId, int count) {
        List<OutboxEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(buildPendingOutboxEvent(tenantId, "EMP_" + i));
        }
        return repo.saveAll(events);
    }
}
