package com.moveinsync.roster.unit;

import com.moveinsync.roster.util.HashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HashUtils Unit Tests")
class HashUtilsTest {

    @Test
    @DisplayName("Should produce identical hash for same normalized inputs")
    void shouldProduceIdenticalHashForSameInputs() {
        long hash1 = HashUtils.calculateHrRecordHash(
                "John Doe", "john.doe@company.com", "+919876543210",
                "MGR_01", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE"
        );

        long hash2 = HashUtils.calculateHrRecordHash(
                "  john doe  ", "JOHN.DOE@company.com", "+919876543210",
                "mgr_01", "blr_ecospace", "shift_0900", "active"
        );

        assertEquals(hash1, hash2, "Normalized inputs should produce identical 64-bit hash");
    }

    @Test
    @DisplayName("Should produce different hash when any field changes")
    void shouldProduceDifferentHashWhenFieldChanges() {
        long hashOriginal = HashUtils.calculateHrRecordHash(
                "John Doe", "john.doe@company.com", "+919876543210",
                "MGR_01", "BLR_ECOSPACE", "SHIFT_0900", "ACTIVE"
        );

        long hashShiftChanged = HashUtils.calculateHrRecordHash(
                "John Doe", "john.doe@company.com", "+919876543210",
                "MGR_01", "BLR_ECOSPACE", "SHIFT_1300", "ACTIVE"
        );

        assertNotEquals(hashOriginal, hashShiftChanged, "Different shift should change hash");
    }
}
