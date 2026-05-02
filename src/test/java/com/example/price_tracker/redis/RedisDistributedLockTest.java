package com.example.price_tracker.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDistributedLockTest {

    private final RedisTemplate<String, Object> redisTemplate = mock();
    private final ValueOperations<String, Object> valueOperations = mock();
    private final RedisDistributedLock distributedLock = new RedisDistributedLock(redisTemplate);

    @Test
    void tryLockRequiresTtlAndUsesSetIfAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("lock:key", "owner", Duration.ofSeconds(5))).thenReturn(true);

        assertTrue(distributedLock.tryLock("lock:key", "owner", Duration.ofSeconds(5)));
        assertFalse(distributedLock.tryLock("lock:key", "owner", Duration.ZERO));
    }

    @Test
    void unlockUsesOwnerCheckScript() {
        when(redisTemplate.execute(any(), eq(java.util.List.of("lock:key")), eq("owner"))).thenReturn(1L);

        assertTrue(distributedLock.unlock("lock:key", "owner"));

        verify(redisTemplate).execute(any(), eq(java.util.List.of("lock:key")), eq("owner"));
    }
}
