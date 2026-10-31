package com.moveinsync.roster.security;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT_CODE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenant(UUID tenantId, String tenantCode) {
        CURRENT_TENANT_ID.set(tenantId);
        CURRENT_TENANT_CODE.set(tenantCode);
    }

    public static UUID getTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    public static String getTenantCode() {
        return CURRENT_TENANT_CODE.get();
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
        CURRENT_TENANT_CODE.remove();
    }
}
