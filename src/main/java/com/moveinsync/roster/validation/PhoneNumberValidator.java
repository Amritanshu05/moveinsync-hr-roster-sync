package com.moveinsync.roster.validation;

import java.util.regex.Pattern;

public final class PhoneNumberValidator {

    // E.164 phone format: optional '+' followed by 7 to 15 digits
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+?[1-9]\\d{6,14}$");

    private PhoneNumberValidator() {
    }

    public static boolean isValid(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        String sanitized = phone.replaceAll("[\\s\\-\\(\\)]", "");
        return E164_PATTERN.matcher(sanitized).matches();
    }
}
