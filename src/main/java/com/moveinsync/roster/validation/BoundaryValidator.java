package com.moveinsync.roster.validation;

import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.RecordErrorDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BoundaryValidator {

    private final Set<String> knownOfficeCodes;

    public BoundaryValidator(Set<String> knownOfficeCodes) {
        this.knownOfficeCodes = knownOfficeCodes != null ? knownOfficeCodes : Set.of();
    }

    public List<RecordErrorDetail> validate(EmployeeEventDto event) {
        List<RecordErrorDetail> errors = new ArrayList<>();

        if (event == null) {
            errors.add(new RecordErrorDetail("event", "NULL_EVENT", "Event payload cannot be null"));
            return errors;
        }

        // Validate eventType
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            errors.add(new RecordErrorDetail("eventType", "MISSING_EVENT_TYPE", "Event type is required"));
        } else {
            String type = event.getEventType().toUpperCase();
            if (!type.equals("CREATE") && !type.equals("UPDATE") && !type.equals("DEACTIVATE")) {
                errors.add(new RecordErrorDetail("eventType", "INVALID_EVENT_TYPE",
                        "Event type must be CREATE, UPDATE, or DEACTIVATE"));
            }
        }

        // Validate employeeCode
        if (event.getEmployeeCode() == null || event.getEmployeeCode().isBlank()) {
            errors.add(new RecordErrorDetail("employeeCode", "MISSING_EMPLOYEE_CODE", "Employee code is mandatory"));
        } else if (event.getEmployeeCode().length() > 64) {
            errors.add(new RecordErrorDetail("employeeCode", "INVALID_LENGTH", "Employee code exceeds 64 characters"));
        }

        // Validate sourceVersion
        if (event.getSourceVersion() == null) {
            errors.add(new RecordErrorDetail("sourceVersion", "MISSING_SOURCE_VERSION", "Source version is required"));
        } else if (event.getSourceVersion() <= 0) {
            errors.add(new RecordErrorDetail("sourceVersion", "INVALID_VERSION", "Source version must be a positive integer"));
        }

        // DEACTIVATE only requires employeeCode and sourceVersion
        if ("DEACTIVATE".equalsIgnoreCase(event.getEventType())) {
            return errors;
        }

        // Validate fullName
        if (event.getFullName() == null || event.getFullName().isBlank()) {
            errors.add(new RecordErrorDetail("fullName", "MISSING_FULL_NAME", "Full name is required for active records"));
        }

        // Validate email
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            errors.add(new RecordErrorDetail("email", "MISSING_EMAIL", "Email address is required"));
        } else if (!EmailValidator.isValid(event.getEmail())) {
            errors.add(new RecordErrorDetail("email", "INVALID_EMAIL_FORMAT", "Field contains invalid email structure"));
        }

        // Validate phone number
        if (event.getPhoneNumber() == null || event.getPhoneNumber().isBlank()) {
            errors.add(new RecordErrorDetail("phoneNumber", "MISSING_PHONE", "Phone number is required"));
        } else if (!PhoneNumberValidator.isValid(event.getPhoneNumber())) {
            errors.add(new RecordErrorDetail("phoneNumber", "INVALID_PHONE_FORMAT", "Phone number must follow E.164 specification"));
        }

        // Validate officeCode
        if (event.getOfficeCode() == null || event.getOfficeCode().isBlank()) {
            errors.add(new RecordErrorDetail("officeCode", "MISSING_OFFICE_CODE", "Office code is required"));
        } else if (!knownOfficeCodes.isEmpty() && !knownOfficeCodes.contains(event.getOfficeCode().trim().toUpperCase())) {
            errors.add(new RecordErrorDetail("officeCode", "UNKNOWN_OFFICE_CODE", "Office code not recognized in tenant registry"));
        }

        return errors;
    }
}
