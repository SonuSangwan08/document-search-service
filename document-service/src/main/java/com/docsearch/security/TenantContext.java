package com.docsearch.security;

/**
 * Holds the authenticated tenant for the current request thread. Populated
 * by {@link TenantFilter} before the request reaches any controller and
 * cleared unconditionally afterwards so nothing ever leaks across the
 * thread-pooled requests that follow.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound to the current request - TenantFilter should have rejected this call earlier");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
