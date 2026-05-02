package com.example.price_tracker.redis;

import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.exception.BusinessException;
import org.aspectj.lang.Aspects;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRateLimitAspectTest {

    private final RedisRateLimiter rateLimiter = mock();

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void blocksWhenRateLimitThresholdIsExceeded() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.setCurrentUserId(7L);
        when(rateLimiter.isAllowed(7L, "/api/products", 5, 60)).thenReturn(false);
        TestApi proxy = createProxy();

        BusinessException exception = assertThrows(BusinessException.class, proxy::call);

        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
        assertEquals("request too frequent", exception.getMessage());
    }

    private TestApi createProxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestApi());
        factory.addAspect(new RedisRateLimitAspect(rateLimiter));
        return factory.getProxy();
    }

    static class TestApi {

        @RateLimit(limit = 5, windowSeconds = 60)
        public String call() {
            return "ok";
        }
    }
}
