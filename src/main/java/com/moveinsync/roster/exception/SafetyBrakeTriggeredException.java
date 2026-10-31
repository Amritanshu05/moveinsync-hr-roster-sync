package com.moveinsync.roster.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

@Getter
public class SafetyBrakeTriggeredException extends BusinessException {

    private final String jobReference;
    private final int missingCount;
    private final int totalActive;
    private final BigDecimal missingRatio;
    private final BigDecimal threshold;

    public SafetyBrakeTriggeredException(String jobReference, int missingCount, int totalActive,
                                         BigDecimal missingRatio, BigDecimal threshold) {
        super(String.format("SAFETY BRAKE TRIGGERED: Nightly export missing %d out of %d active employees (ratio: %s, threshold: %s). Deactivations halted awaiting operator approval.",
                missingCount, totalActive, missingRatio.toPlainString(), threshold.toPlainString()),
              HttpStatus.UNPROCESSABLE_ENTITY,
              "SAFETY_BRAKE_TRIGGERED");
        this.jobReference = jobReference;
        this.missingCount = missingCount;
        this.totalActive = totalActive;
        this.missingRatio = missingRatio;
        this.threshold = threshold;
    }
}
