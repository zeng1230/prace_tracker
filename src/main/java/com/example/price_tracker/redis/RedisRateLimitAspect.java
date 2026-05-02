package com.example.price_tracker.redis;

import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Slf4j
public class RedisRateLimitAspect {

    private final RedisRateLimiter rateLimiter;
    private final RedisRateLimitProperties properties;

    @Autowired
    public RedisRateLimitAspect(RedisRateLimiter rateLimiter, RedisRateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public RedisRateLimitAspect(RedisRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.properties = new RedisRateLimitProperties();
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        Long userId = UserContext.getCurrentUserId();
        String apiPath = currentRequestPath();
        int limit = rateLimit.limit() > 0 ? rateLimit.limit() : properties.getDefaultLimit();
        int windowSeconds = rateLimit.windowSeconds() > 0
                ? rateLimit.windowSeconds()
                : properties.getDefaultWindowSeconds();

        if (!rateLimiter.isAllowed(userId, apiPath, limit, windowSeconds)) {
            log.info("rate limited, userId={}, apiPath={}, limit={}, windowSeconds={}",
                    userId, apiPath, limit, windowSeconds);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "request too frequent");
        }
        return joinPoint.proceed();
    }

    private String currentRequestPath() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        return request.getRequestURI();
    }
}
