package com.moveinsync.roster.dto.response;

import com.moveinsync.roster.domain.enums.EmployeeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeResponseDto {
    private UUID id;
    private UUID tenantId;
    private String employeeCode;
    private String fullName;
    private String email;
    private String phoneNumber;
    private String managerEmployeeCode;
    private String officeCode;
    private String shiftCode;
    private EmployeeStatus status;
    private Long sourceVersion;
    private Instant sourceTimestamp;
    private String pickupPointId;
    private Boolean specialAssistance;
    private String transportNotes;
    private Instant updatedAt;
}
