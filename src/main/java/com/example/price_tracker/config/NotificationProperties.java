package com.example.price_tracker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private final Webhook webhook = new Webhook();
    private final Delivery delivery = new Delivery();

    @Getter
    @Setter
    public static class Webhook {

        private boolean enabled = false;
        private String url = "";
        private String secret = "";
        private boolean allowHttp = false;
        private Long timeoutMs = 3000L;
        private Long connectTimeoutMs;
        private Long readTimeoutMs;

        public long resolveConnectTimeoutMs() {
            return connectTimeoutMs != null ? connectTimeoutMs : resolveLegacyTimeoutMs();
        }

        public long resolveReadTimeoutMs() {
            return readTimeoutMs != null ? readTimeoutMs : resolveLegacyTimeoutMs();
        }

        private long resolveLegacyTimeoutMs() {
            return timeoutMs != null ? timeoutMs : 3000L;
        }
    }

    @Getter
    @Setter
    public static class Delivery {

        private boolean enabled = true;
        private long fixedDelayMs = 5000L;
        private int batchSize = 20;
        private int maxAttempts = 3;
        private long initialBackoffSeconds = 5L;
        private long maxBackoffSeconds = 300L;
        private long claimLeaseSeconds = 120L;
    }
}
