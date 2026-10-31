package com.moveinsync.roster.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String message) {
        super(message, HttpStatus.FORBIDDEN, "FORBIDDEN");
    }
}
