package com.moveinsync.roster.mapper;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.EmployeeResponseDto;
import com.moveinsync.roster.util.HashUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class EmployeeMapper {

    public Employee toEntity(EmployeeEventDto event, UUID tenantId) {
        String statusStr = event.getStatus() != null ? event.getStatus().toUpperCase() : "ACTIVE";
        EmployeeStatus status;
        try {
            status = EmployeeStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = EmployeeStatus.ACTIVE;
        }

        long hash = HashUtils.calculateHrRecordHash(
                event.getFullName(),
                event.getEmail(),
                event.getPhoneNumber(),
                event.getManagerEmployeeCode(),
                event.getOfficeCode(),
                event.getShiftCode(),
                status.name()
        );

        return Employee.builder()
                .tenantId(tenantId)
                .employeeCode(event.getEmployeeCode().trim())
                .fullName(event.getFullName() != null ? event.getFullName().trim() : "")
                .email(event.getEmail() != null ? event.getEmail().trim().toLowerCase() : "")
                .phoneNumber(event.getPhoneNumber() != null ? event.getPhoneNumber().trim() : "")
                .managerEmployeeCode(event.getManagerEmployeeCode() != null ? event.getManagerEmployeeCode().trim() : null)
                .officeCode(event.getOfficeCode() != null ? event.getOfficeCode().trim().toUpperCase() : "")
                .shiftCode(event.getShiftCode() != null ? event.getShiftCode().trim().toUpperCase() : "")
                .status(status)
                .sourceVersion(event.getSourceVersion())
                .sourceTimestamp(event.getSourceTimestamp() != null ? event.getSourceTimestamp() : Instant.now())
                .recordHash(hash)
                // Transport fields are null for newly created HR employee
                .pickupPointId(null)
                .specialAssistance(false)
                .transportNotes(null)
                .build();
    }

    public void updateHrFields(Employee existing, EmployeeEventDto event) {
        if ("DEACTIVATE".equalsIgnoreCase(event.getEventType())) {
            existing.setStatus(EmployeeStatus.INACTIVE);
            existing.setSourceVersion(event.getSourceVersion());
            existing.setSourceTimestamp(event.getSourceTimestamp() != null ? event.getSourceTimestamp() : Instant.now());
            // Recalculate hash with INACTIVE status
            long hash = HashUtils.calculateHrRecordHash(
                    existing.getFullName(),
                    existing.getEmail(),
                    existing.getPhoneNumber(),
                    existing.getManagerEmployeeCode(),
                    existing.getOfficeCode(),
                    existing.getShiftCode(),
                    EmployeeStatus.INACTIVE.name()
            );
            existing.setRecordHash(hash);
            return;
        }

        if (event.getFullName() != null && !event.getFullName().isBlank()) {
            existing.setFullName(event.getFullName().trim());
        }
        if (event.getEmail() != null && !event.getEmail().isBlank()) {
            existing.setEmail(event.getEmail().trim().toLowerCase());
        }
        if (event.getPhoneNumber() != null && !event.getPhoneNumber().isBlank()) {
            existing.setPhoneNumber(event.getPhoneNumber().trim());
        }
        if (event.getManagerEmployeeCode() != null) {
            existing.setManagerEmployeeCode(event.getManagerEmployeeCode().trim());
        }
        if (event.getOfficeCode() != null && !event.getOfficeCode().isBlank()) {
            existing.setOfficeCode(event.getOfficeCode().trim().toUpperCase());
        }
        if (event.getShiftCode() != null && !event.getShiftCode().isBlank()) {
            existing.setShiftCode(event.getShiftCode().trim().toUpperCase());
        }
        if (event.getStatus() != null && !event.getStatus().isBlank()) {
            try {
                existing.setStatus(EmployeeStatus.valueOf(event.getStatus().trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        existing.setSourceVersion(event.getSourceVersion());
        existing.setSourceTimestamp(event.getSourceTimestamp() != null ? event.getSourceTimestamp() : Instant.now());

        // Field Ownership Guarantee: Transport admin fields (pickupPointId, specialAssistance, transportNotes)
        // are NEVER modified by HR events.

        long hash = HashUtils.calculateHrRecordHash(
                existing.getFullName(),
                existing.getEmail(),
                existing.getPhoneNumber(),
                existing.getManagerEmployeeCode(),
                existing.getOfficeCode(),
                existing.getShiftCode(),
                existing.getStatus().name()
        );
        existing.setRecordHash(hash);
    }

    public EmployeeResponseDto toResponseDto(Employee employee) {
        return EmployeeResponseDto.builder()
                .id(employee.getId())
                .tenantId(employee.getTenantId())
                .employeeCode(employee.getEmployeeCode())
                .fullName(employee.getFullName())
                .email(employee.getEmail())
                .phoneNumber(employee.getPhoneNumber())
                .managerEmployeeCode(employee.getManagerEmployeeCode())
                .officeCode(employee.getOfficeCode())
                .shiftCode(employee.getShiftCode())
                .status(employee.getStatus())
                .sourceVersion(employee.getSourceVersion())
                .sourceTimestamp(employee.getSourceTimestamp())
                .pickupPointId(employee.getPickupPointId())
                .specialAssistance(employee.getSpecialAssistance())
                .transportNotes(employee.getTransportNotes())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }
}
