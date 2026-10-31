package com.moveinsync.roster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeEventDto {

    private String eventId;

    @NotBlank(message = "Event type is required (CREATE, UPDATE, DEACTIVATE)")
    private String eventType;

    @NotBlank(message = "Employee code is required")
    private String employeeCode;

    @NotNull(message = "Source version is required for ordering")
    private Long sourceVersion;

    @Builder.Default
    private Instant sourceTimestamp = Instant.now();

    // HR-Owned Fields
    private String fullName;
    private String email;
    private String phoneNumber;
    private String managerEmployeeCode;
    private String officeCode;
    private String shiftCode;
    private String status; // ACTIVE, INACTIVE, SUSPENDED

    // Note: Transport admin fields passed in HR event are explicitly ignored
    private String pickupPointId;
    private Boolean specialAssistance;
}
