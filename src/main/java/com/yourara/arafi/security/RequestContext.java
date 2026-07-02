package com.yourara.arafi.security;

import java.util.UUID;

public final class RequestContext {
    private static final ThreadLocal<UUID> currentAppId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentMode = new ThreadLocal<>();
    private static final ThreadLocal<UUID> currentUserId = new ThreadLocal<>();

    public static void setContext(UUID appId, String mode) {
        currentAppId.set(appId);
        currentMode.set(mode);
    }

    public static void setUserId(UUID userId) {
        currentUserId.set(userId);
    }

    public static UUID getAppId() { return currentAppId.get(); }
    public static String getMode() { return currentMode.get(); }
    public static UUID getUserId() { return currentUserId.get(); }

    public static void clear() {
        currentAppId.remove();
        currentMode.remove();
        currentUserId.remove();
    }
}