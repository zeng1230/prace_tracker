package com.example.price_tracker.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceTest {

    private final RedisTemplate<String, Object> redisTemplate = mock();
    private final ValueOperations<String, Object> valueOperations = mock();
    private final RedisCacheService cacheService = new RedisCacheService(redisTemplate);

    @Test
    void supportsGetSetDeleteAndSetIfAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("key")).thenReturn("value");
        when(valueOperations.setIfAbsent("lock", "owner", Duration.ofSeconds(3))).thenReturn(true);

        assertEquals("value", cacheService.get("key", String.class));
        cacheService.set("key", "value", Duration.ofMinutes(5));
        cacheService.delete("key");
        assertTrue(cacheService.setIfAbsent("lock", "owner", Duration.ofSeconds(3)));

        verify(valueOperations).set("key", "value", Duration.ofMinutes(5));
        verify(redisTemplate).delete("key");
    }

    @Test
    void createsRandomTtlWithinExpectedRange() {
        Duration ttl = cacheService.randomTtl(Duration.ofMinutes(10), Duration.ofMinutes(2));

        assertTrue(ttl.compareTo(Duration.ofMinutes(10)) >= 0);
        assertTrue(ttl.compareTo(Duration.ofMinutes(12)) <= 0);
    }
}
