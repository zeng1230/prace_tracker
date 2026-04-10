package com.example.price_tracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;
    private Long accessTokenExpireMinutes;
    private Long refreshTokenExpireDays;
    private String issuer;
}
