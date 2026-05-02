package com.example.price_tracker.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    public <T> T get(String key, Class<T> targetType) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return targetType.cast(value);
    }

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    public boolean setIfAbsent(String key, Object value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    public Duration randomTtl(Duration baseTtl, Duration maxJitter) {
        if (baseTtl == null || baseTtl.isNegative() || baseTtl.isZero()) {
            throw new IllegalArgumentException("baseTtl must be positive");
        }
        if (maxJitter == null || maxJitter.isNegative() || maxJitter.isZero()) {
            return baseTtl;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(maxJitter.toMillis() + 1);
        return baseTtl.plusMillis(jitterMillis);
    }
}
