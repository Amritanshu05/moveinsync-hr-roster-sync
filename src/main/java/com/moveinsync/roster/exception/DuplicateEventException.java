package com.moveinsync.roster.exception;

import org.springframework.http.HttpStatus;

public class DuplicateEventException extends BusinessException {

    public DuplicateEventException(String employeeCode, Long version) {
        super(String.format("Duplicate event for employee '%s' with version %d processed as idempotent no-op",
                employeeCode, version),
              HttpStatus.OK,
              "DUPLICATE_EVENT_NO_OP");
    }
}
