package com.example.price_tracker.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisDistributedLock {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;

    public boolean tryLock(String key, String owner, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, owner, ttl));
    }

    public boolean unlock(String key, String owner) {
        Long result = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), owner);
        return Long.valueOf(1L).equals(result);
    }
}
