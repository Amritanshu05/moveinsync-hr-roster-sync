package com.moveinsync.roster.validation;

import java.util.regex.Pattern;

public final class EmailValidator {

    private static final Pattern RFC5322_EMAIL = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private EmailValidator() {
    }

    public static boolean isValid(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return RFC5322_EMAIL.matcher(email.trim()).matches();
    }
}
