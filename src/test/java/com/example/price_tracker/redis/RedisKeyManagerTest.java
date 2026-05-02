package com.example.price_tracker.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyManagerTest {

    @Test
    void buildsProductAndUserCacheKeys() {
        assertEquals("price-tracker:cache:product:detail:10", RedisKeyManager.productDetailKey(10L));
        assertEquals("price-tracker:cache:product:price:10", RedisKeyManager.productPriceKey(10L));
        assertEquals("price-tracker:cache:user:watchlist:8", RedisKeyManager.userWatchlistKey(8L));
    }

    @Test
    void buildsInfrastructureKeys() {
        assertEquals("price-tracker:cache:null:product:10", RedisKeyManager.nullValueKey("product:10"));
        assertEquals("price-tracker:lock:product-refresh:10", RedisKeyManager.lockKey("product-refresh:10"));
        assertEquals("price-tracker:rate-limit:3:/api/products", RedisKeyManager.rateLimitKey(3L, "/api/products"));
        assertEquals("price-tracker:idempotent:notify:99", RedisKeyManager.notificationIdempotentKey("99"));
    }
}
