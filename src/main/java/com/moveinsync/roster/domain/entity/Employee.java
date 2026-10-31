package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.EmployeeStatus;
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
@Table(name = "employees", uniqueConstraints = {
    @UniqueConstraint(name = "uk_employees_tenant_emp_code", columnNames = {"tenant_id", "employee_code"})
}, indexes = {
    @Index(name = "idx_employees_tenant_emp_code", columnList = "tenant_id, employee_code"),
    @Index(name = "idx_employees_tenant_status", columnList = "tenant_id, status"),
    @Index(name = "idx_employees_tenant_office", columnList = "tenant_id, office_code"),
    @Index(name = "idx_employees_tenant_hash", columnList = "tenant_id, record_hash")
})
public class Employee extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_code", nullable = false, length = 64)
    private String employeeCode;

    // --- HR-Owned Fields ---
    @Column(name = "full_name", nullable = false, length = 128)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Column(name = "manager_employee_code", length = 64)
    private String managerEmployeeCode;

    @Column(name = "office_code", nullable = false, length = 32)
    private String officeCode;

    @Column(name = "shift_code", nullable = false, length = 32)
    private String shiftCode;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Builder.Default
    @Column(name = "source_version", nullable = false)
    private Long sourceVersion = 1L;

    @Builder.Default
    @Column(name = "source_timestamp", nullable = false)
    private Instant sourceTimestamp = Instant.now();

    @Builder.Default
    @Column(name = "record_hash", nullable = false)
    private Long recordHash = 0L;

    // --- Transport-Admin-Owned Fields ---
    @Column(name = "pickup_point_id", length = 64)
    private String pickupPointId;

    @Builder.Default
    @Column(name = "special_assistance", nullable = false)
    private Boolean specialAssistance = false;

    @Column(name = "transport_notes", length = 255)
    private String transportNotes;

    // --- JPA Optimistic Locking ---
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
