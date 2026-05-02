package com.example.price_tracker.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RedisRateLimitProperties {

    private Integer defaultLimit = 60;
    private Integer defaultWindowSeconds = 60;
}
