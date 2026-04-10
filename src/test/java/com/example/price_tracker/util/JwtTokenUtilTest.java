package com.example.price_tracker.util;

import com.example.price_tracker.config.JwtProperties;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JwtTokenUtilTest {

    private final JwtTokenUtil jwtTokenUtil = new JwtTokenUtil(jwtProperties());

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createsAndParsesAccessToken() {
        String token = jwtTokenUtil.generateAccessToken(7L, "alice");

        JwtTokenUtil.TokenPayload payload = jwtTokenUtil.parseAccessToken(token);

        assertEquals(7L, payload.userId());
        assertEquals("alice", payload.username());
    }

    @Test
    void interceptorExtractsBearerTokenAndStoresUserId() throws Exception {
        String token = jwtTokenUtil.generateAccessToken(9L, "bob");
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenUtil);
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + token);

        boolean allowed = interceptor.preHandle(request, response, mock());

        assertTrue(allowed);
        assertEquals(9L, UserContext.getCurrentUserId());
        interceptor.afterCompletion(request, response, mock(), null);
        assertEquals(null, UserContext.getCurrentUserId());
    }

    @Test
    void interceptorRejectsMissingAuthorizationHeader() {
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenUtil);
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        assertThrows(BusinessException.class, () -> interceptor.preHandle(request, response, mock()));
    }

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("12345678901234567890123456789012");
        properties.setAccessTokenExpireMinutes(120L);
        properties.setRefreshTokenExpireDays(7L);
        properties.setIssuer("price-tracker-test");
        return properties;
    }
}
