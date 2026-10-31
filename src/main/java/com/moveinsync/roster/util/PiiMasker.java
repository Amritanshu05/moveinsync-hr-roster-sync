package com.moveinsync.roster.util;

import java.util.regex.Pattern;

public final class PiiMasker {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?[0-9]{1,3})?[-. ]?([0-9]{3})[-. ]?([0-9]{3})[-. ]?([0-9]{4})");

    private PiiMasker() {
    }

    public static String mask(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String masked = EMAIL_PATTERN.matcher(input).replaceAll(match -> {
            String name = match.group(1);
            String domain = match.group(2);
            if (name.length() <= 2) {
                return "**@" + domain;
            }
            return name.charAt(0) + "***" + name.charAt(name.length() - 1) + "@" + domain;
        });

        masked = PHONE_PATTERN.matcher(masked).replaceAll("****-****-$4");
        return masked;
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***@***.com";
        }
        int atIndex = email.indexOf('@');
        String name = email.substring(0, atIndex);
        String domain = email.substring(atIndex + 1);
        if (name.length() <= 2) {
            return "**@" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + "@" + domain;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "******";
        }
        return "******" + phone.substring(phone.length() - 4);
    }
}
