package com.example.price_tracker.redis;

public final class RedisKeyManager {

    private static final String PREFIX = "price-tracker:";

    private RedisKeyManager() {
    }

    public static String productDetailKey(Long productId) {
        return PREFIX + "cache:product:detail:" + productId;
    }

    public static String productPriceKey(Long productId) {
        return PREFIX + "cache:product:price:" + productId;
    }

    public static String userWatchlistKey(Long userId) {
        return PREFIX + "cache:user:watchlist:" + userId;
    }

    public static String nullValueKey(String businessKey) {
        return PREFIX + "cache:null:" + businessKey;
    }

    public static String lockKey(String businessKey) {
        return PREFIX + "lock:" + businessKey;
    }

    public static String rateLimitKey(Long userId, String apiPath) {
        return PREFIX + "rate-limit:" + userId + ":" + apiPath;
    }

    public static String notificationIdempotentKey(String businessId) {
        return PREFIX + "idempotent:notify:" + businessId;
    }
}
