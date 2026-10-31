package com.moveinsync.roster.unit;

import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.RecordErrorDetail;
import com.moveinsync.roster.validation.BoundaryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Boundary Validator Unit Tests")
class BoundaryValidatorTest {

    private BoundaryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BoundaryValidator(Set.of("BLR_ECOSPACE", "HYD_HITEC", "DEL_CYBERHUB"));
    }

    @Test
    @DisplayName("Should accept valid employee event")
    void shouldAcceptValidEmployeeEvent() {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("UPDATE")
                .employeeCode("EMP_1001")
                .sourceVersion(1L)
                .fullName("John Doe")
                .email("john.doe@company.com")
                .phoneNumber("+919876543210")
                .officeCode("BLR_ECOSPACE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        List<RecordErrorDetail> errors = validator.validate(event);
        assertTrue(errors.isEmpty(), "Valid event should have no errors");
    }

    @Test
    @DisplayName("Should reject malformed phone number, unknown office, and invalid email")
    void shouldRejectMalformedFields() {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("UPDATE")
                .employeeCode("EMP_1002")
                .sourceVersion(1L)
                .fullName("Bad Record")
                .email("not-an-email")
                .phoneNumber("12345") // Too short for E.164
                .officeCode("UNKNOWN_OFFICE")
                .shiftCode("SHIFT_0900")
                .status("ACTIVE")
                .build();

        List<RecordErrorDetail> errors = validator.validate(event);
        assertEquals(3, errors.size());

        assertTrue(errors.stream().anyMatch(e -> "INVALID_EMAIL_FORMAT".equals(e.getErrorCode())));
        assertTrue(errors.stream().anyMatch(e -> "INVALID_PHONE_FORMAT".equals(e.getErrorCode())));
        assertTrue(errors.stream().anyMatch(e -> "UNKNOWN_OFFICE_CODE".equals(e.getErrorCode())));
    }

    @Test
    @DisplayName("Should allow DEACTIVATE event with only employeeCode and sourceVersion")
    void shouldAllowDeactivateEventWithoutOtherFields() {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("DEACTIVATE")
                .employeeCode("EMP_1003")
                .sourceVersion(5L)
                .build();

        List<RecordErrorDetail> errors = validator.validate(event);
        assertTrue(errors.isEmpty(), "DEACTIVATE event only requires employeeCode and sourceVersion");
    }

    @Test
    @DisplayName("Should reject null or zero source version")
    void shouldRejectZeroSourceVersion() {
        EmployeeEventDto event = EmployeeEventDto.builder()
                .eventType("UPDATE")
                .employeeCode("EMP_1004")
                .sourceVersion(0L)
                .build();

        List<RecordErrorDetail> errors = validator.validate(event);
        assertTrue(errors.stream().anyMatch(e -> "INVALID_VERSION".equals(e.getErrorCode())));
    }
}
