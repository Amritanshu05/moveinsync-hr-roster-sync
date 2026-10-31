package com.moveinsync.roster.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtils {

    private static final long SEED64 = 0xe17a1465L;

    private HashUtils() {
    }

    /**
     * Calculates a 64-bit Murmur3 hash of the canonical HR fields.
     * Guaranteed deterministic across runs.
     */
    public static long calculateHrRecordHash(String fullName, String email, String phoneNumber,
                                           String managerCode, String officeCode, String shiftCode,
                                           String status) {
        String canonical = String.join("|",
                normalize(fullName),
                normalize(email),
                normalize(phoneNumber),
                normalize(managerCode),
                normalize(officeCode),
                normalize(shiftCode),
                normalize(status)
        );
        return murmurHash364(canonical.getBytes(StandardCharsets.UTF_8), SEED64);
    }

    public static String calculateSha256(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String normalize(String val) {
        return val == null ? "" : val.trim().toLowerCase();
    }

    /**
     * 64-bit MurmurHash3 implementation.
     */
    public static long murmurHash364(byte[] data, long seed) {
        long h = seed ^ (data.length * 0x5deece66dL);
        for (int i = 0; i < data.length; i++) {
            h ^= data[i];
            h *= 0x5deece66dL;
            h ^= (h >>> 32);
        }
        return h;
    }
}
