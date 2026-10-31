package com.moveinsync.roster.unit;

import com.moveinsync.roster.util.PiiMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PII Masker Unit Tests")
class PiiMaskerTest {

    @Test
    @DisplayName("Should mask email addresses while preserving domain")
    void shouldMaskEmail() {
        String input = "Error updating user with email test.user@company.com in system";
        String masked = PiiMasker.mask(input);

        assertFalse(masked.contains("test.user@company.com"));
        assertTrue(masked.contains("t***r@company.com") || masked.contains("@company.com"));
    }

    @Test
    @DisplayName("Should mask phone numbers in log messages")
    void shouldMaskPhone() {
        String input = "Failed to send SMS to +919876543210 for cab notification";
        String masked = PiiMasker.mask(input);

        assertFalse(masked.contains("9876543210"));
    }
}
