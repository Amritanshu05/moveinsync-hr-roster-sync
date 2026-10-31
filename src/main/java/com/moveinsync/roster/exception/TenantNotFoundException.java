package com.moveinsync.roster.exception;

import org.springframework.http.HttpStatus;

public class TenantNotFoundException extends BusinessException {

    public TenantNotFoundException(String tenantIdentifier) {
        super(String.format("Tenant '%s' was not found or is inactive", tenantIdentifier),
              HttpStatus.BAD_REQUEST,
              "TENANT_NOT_FOUND");
    }
}
