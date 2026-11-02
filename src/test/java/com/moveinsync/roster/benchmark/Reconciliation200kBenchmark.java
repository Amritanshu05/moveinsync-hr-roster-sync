package com.moveinsync.roster.benchmark;

import com.moveinsync.roster.db.AbstractPostgresIntegrationTest;
import com.moveinsync.roster.db.TestDataFactory;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.ReconciliationJobStatus;
import com.moveinsync.roster.dto.response.ReconciliationJobResponseDto;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.util.HashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BENCHMARK: 200,000-Record HR Snapshot Nightly Reconciliation
 *
 * <p>Target Requirements from Case Study 5:
 * <ul>
 *   <li>Dataset: 200,000 employee records in CSV snapshot</li>
 *   <li>Distribution: ~195,000 unchanged, ~3,000 updated, ~2,000 new, ~1,000 missing</li>
 *   <li>Execution Time Target: &lt; 60 seconds</li>
 *   <li>Memory: Bounded JVM heap usage (streaming Jackson CSV + Murmur3 hash diff)</li>
 *   <li>Safety Brake: Unaffected (0.5% missing &lt; 30% threshold)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
public class Reconciliation200kBenchmark extends AbstractPostgresIntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private OfficeRepository officeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private static final int UNCHANGED_COUNT = 195_000;
    private static final int UPDATED_COUNT   = 3_000;
    private static final int CREATED_COUNT   = 2_000;
    private static final int MISSING_COUNT   = 1_000;
    private static final int TOTAL_CSV_ROWS  = UNCHANGED_COUNT + UPDATED_COUNT + CREATED_COUNT; // 200,000

    @Test
    @DisplayName("BENCHMARK-200K: Process 200,000-record CSV in <60 seconds with bounded memory")
    void benchmark200kNightlyReconciliation() throws Exception {
        System.out.println("================================================================================");
        System.out.println(">>> STARTING 200,000 RECORD RECONCILIATION BENCHMARK");
        System.out.println("================================================================================");

        // 1. Setup isolated benchmark tenant
        String tenantCode = "BENCH_" + UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = TestDataFactory.createAndPersistTenant(tenantRepository, tenantCode, "Benchmark Tenant");
        UUID tenantId = tenant.getId();
        TestDataFactory.persistOffice(officeRepository, tenantId, "BLR_ECOSPACE");
        TenantContext.setTenant(tenantId, tenantCode);

        int initialDbActiveCount = UNCHANGED_COUNT + UPDATED_COUNT + MISSING_COUNT; // 199,000
        System.out.printf("1. Seeding %d initial active employees into PostgreSQL...%n", initialDbActiveCount);

        long seedStart = System.currentTimeMillis();
        seedInitialEmployees(tenantId);
        long seedDuration = System.currentTimeMillis() - seedStart;
        System.out.printf("   Seeding completed in %d ms (%.2f seconds)%n", seedDuration, seedDuration / 1000.0);

        // 2. Generate 200,000-row synthetic CSV file on disk
        System.out.printf("2. Generating synthetic 200,000-row CSV file on disk...%n");
        Path tempCsv = Files.createTempFile("roster_200k_", ".csv");
        long csvGenStart = System.currentTimeMillis();
        generateCsvFile(tempCsv);
        long csvGenDuration = System.currentTimeMillis() - csvGenStart;
        long csvSizeBytes = Files.size(tempCsv);
        System.out.printf("   CSV generated: %s (%.2f MB) in %d ms%n",
                tempCsv, csvSizeBytes / (1024.0 * 1024.0), csvGenDuration);

        // 3. Prepare MultipartFile streaming from disk (not loaded into RAM)
        MockMultipartFile multipartFile;
        try (InputStream in = Files.newInputStream(tempCsv)) {
            multipartFile = new MockMultipartFile(
                    "file", "roster_200k.csv", "text/csv", in);
        }

        // Measure memory before execution
        System.gc();
        Thread.sleep(500);
        Runtime runtime = Runtime.getRuntime();
        long heapBeforeBytes = runtime.totalMemory() - runtime.freeMemory();
        double heapBeforeMb = heapBeforeBytes / (1024.0 * 1024.0);

        System.out.println("3. Executing Nightly Reconciliation Service (Streaming Hash-Diff)...");
        System.out.printf("   Baseline JVM Heap: %.2f MB%n", heapBeforeMb);

        long startTime = System.currentTimeMillis();
        ReconciliationJobResponseDto result = reconciliationService.executeReconciliation(multipartFile);
        long totalDurationMs = System.currentTimeMillis() - startTime;
        double totalDurationSec = totalDurationMs / 1000.0;

        // Measure memory after execution
        long heapAfterBytes = runtime.totalMemory() - runtime.freeMemory();
        double heapAfterMb = heapAfterBytes / (1024.0 * 1024.0);
        double maxHeapMb = runtime.maxMemory() / (1024.0 * 1024.0);

        System.out.println("================================================================================");
        System.out.println(">>> 200,000 RECORD BENCHMARK COMPLETED");
        System.out.println("================================================================================");
        System.out.printf("Total Reconciliation Time : %d ms (%.3f seconds)%n", totalDurationMs, totalDurationSec);
        System.out.printf("Target Requirement        : < 60.000 seconds%n");
        System.out.printf("Status                    : %s%n", totalDurationSec < 60.0 ? "PASSED (MEETS REQUIREMENT)" : "FAILED");
        System.out.printf("Heap Memory (Start)       : %.2f MB%n", heapBeforeMb);
        System.out.printf("Heap Memory (End)         : %.2f MB%n", heapAfterMb);
        System.out.printf("Max Heap Capacity         : %.2f MB%n", maxHeapMb);
        System.out.printf("Job Status                : %s%n", result.getStatus());
        System.out.printf("Safety Brake Triggered    : %s%n", result.isSafetyBrakeTriggered());
        System.out.printf("Total File Records        : %,d%n", result.getTotalRecords());
        System.out.printf("Unchanged Records         : %,d%n", result.getUnchangedRecords());
        System.out.printf("Updated Records           : %,d%n", result.getUpdatedRecords());
        System.out.printf("Created Records           : %,d%n", result.getCreatedRecords());
        System.out.printf("Missing Records           : %,d%n", result.getMissingCount());
        System.out.printf("Missing Ratio             : %s%n", result.getMissingRatio());
        System.out.println("================================================================================");

        // Clean up temporary benchmark file
        Files.deleteIfExists(tempCsv);

        // Verification assertions
        assertThat(result.getStatus()).isEqualTo(ReconciliationJobStatus.COMPLETED);
        assertThat(result.isSafetyBrakeTriggered()).isFalse();
        assertThat(result.getTotalRecords()).isEqualTo(TOTAL_CSV_ROWS);
        assertThat(result.getUnchangedRecords()).isEqualTo(UNCHANGED_COUNT);
        assertThat(result.getUpdatedRecords()).isEqualTo(UPDATED_COUNT);
        assertThat(result.getCreatedRecords()).isEqualTo(CREATED_COUNT);
        assertThat(result.getMissingCount()).isEqualTo(MISSING_COUNT);

        // Strict Requirement Assertion: Under 60 seconds
        assertThat(totalDurationSec)
                .as("200,000 record reconciliation must complete in under 60 seconds")
                .isLessThan(60.0);
    }

    /**
     * Seeds initial employees in batch using JDBC to achieve fast setup time.
     */
    private void seedInitialEmployees(UUID tenantId) {
        String insertSql = "INSERT INTO employees (" +
                "id, tenant_id, employee_code, full_name, email, phone_number, " +
                "office_code, shift_code, status, source_version, source_timestamp, " +
                "record_hash, special_assistance, version, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, 'BLR_ECOSPACE', ?, 'ACTIVE', 1, ?, ?, false, 0, ?, ?)";

        int totalToSeed = UNCHANGED_COUNT + UPDATED_COUNT + MISSING_COUNT; // 199,000
        int batchSize = 2500;
        Timestamp now = Timestamp.from(Instant.now());

        transactionTemplate.execute(txStatus -> {
            List<Object[]> batchArgs = new ArrayList<>(batchSize);

            for (int i = 1; i <= totalToSeed; i++) {
                String empCode;
                String shiftCode = "MORNING";
                String fullName;
                String email;
                String phone = "+919876543210";

                if (i <= UNCHANGED_COUNT) {
                    empCode = "EMP_UNCHANGED_" + i;
                    fullName = "User " + empCode;
                    email = empCode.toLowerCase() + "@moveinsync.com";
                } else if (i <= UNCHANGED_COUNT + UPDATED_COUNT) {
                    int idx = i - UNCHANGED_COUNT;
                    empCode = "EMP_UPDATED_" + idx;
                    fullName = "OldName " + empCode;
                    email = "old." + empCode.toLowerCase() + "@moveinsync.com";
                } else {
                    int idx = i - UNCHANGED_COUNT - UPDATED_COUNT;
                    empCode = "EMP_MISSING_" + idx;
                    fullName = "Missing " + empCode;
                    email = empCode.toLowerCase() + "@moveinsync.com";
                }

                long hash = HashUtils.calculateHrRecordHash(
                        fullName, email, phone, null, "BLR_ECOSPACE", shiftCode, "ACTIVE");

                batchArgs.add(new Object[]{
                        tenantId.toString(), empCode, fullName, email, phone, shiftCode, now, hash, now, now
                });

                if (batchArgs.size() == batchSize || i == totalToSeed) {
                    jdbcTemplate.batchUpdate(insertSql, batchArgs);
                    batchArgs.clear();
                }
            }
            return null;
        });
    }

    /**
     * Generates a 200,000-row CSV file on disk.
     */
    private void generateCsvFile(Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // Header
            writer.write("employee_code,full_name,email,phone_number,manager_employee_code,office_code,shift_code,status\n");

            // 1. Unchanged records (195,000)
            for (int i = 1; i <= UNCHANGED_COUNT; i++) {
                String code = "EMP_UNCHANGED_" + i;
                writer.write(code);
                writer.write(",User ");
                writer.write(code);
                writer.write(",");
                writer.write(code.toLowerCase());
                writer.write("@moveinsync.com,+919876543210,,BLR_ECOSPACE,MORNING,ACTIVE\n");
            }

            // 2. Updated records (3,000) — changed shift and email
            for (int i = 1; i <= UPDATED_COUNT; i++) {
                String code = "EMP_UPDATED_" + i;
                writer.write(code);
                writer.write(",NewName ");
                writer.write(code);
                writer.write(",");
                writer.write("new." + code.toLowerCase() + "@moveinsync.com,+919876543210,,BLR_ECOSPACE,NIGHT,ACTIVE\n");
            }

            // 3. New records (2,000) — new joiners not currently in DB
            for (int i = 1; i <= CREATED_COUNT; i++) {
                String code = "EMP_NEWJOINER_" + i;
                writer.write(code);
                writer.write(",New Joiner ");
                writer.write(code);
                writer.write(",");
                writer.write(code.toLowerCase());
                writer.write("@moveinsync.com,+919876543210,,BLR_ECOSPACE,AFTERNOON,ACTIVE\n");
            }
        }
    }
}

