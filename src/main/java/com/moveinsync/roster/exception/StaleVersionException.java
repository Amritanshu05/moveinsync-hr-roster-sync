package com.moveinsync.roster.exception;

import org.springframework.http.HttpStatus;

public class StaleVersionException extends BusinessException {

    public StaleVersionException(String employeeCode, Long incomingVersion, Long currentVersion) {
        super(String.format("Event for employee '%s' with version %d is stale. Current version is %d",
                employeeCode, incomingVersion, currentVersion),
              HttpStatus.OK,
              "STALE_VERSION_IGNORED");
    }
}
