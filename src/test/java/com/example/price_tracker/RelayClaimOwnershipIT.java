package com.example.price_tracker;

import com.example.price_tracker.entity.NotificationDelivery;
import com.example.price_tracker.entity.NotificationDeliveryStatus;
import com.example.price_tracker.entity.OutboxEvent;
import com.example.price_tracker.entity.OutboxEventStatus;
import com.example.price_tracker.mapper.NotificationDeliveryMapper;
import com.example.price_tracker.mapper.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class RelayClaimOwnershipIT {

    private static final List<OutboxEventStatus> OUTBOX_READY = List.of(
            OutboxEventStatus.PENDING, OutboxEventStatus.FAILED_RETRYABLE);
    private static final List<NotificationDeliveryStatus> DELIVERY_READY = List.of(
            NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.FAILED_RETRYABLE);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("price_tracker")
            .withUsername("price_tracker")
            .withPassword("price_tracker");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private NotificationDeliveryMapper notificationDeliveryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRelayTables() {
        jdbcTemplate.update("DELETE FROM tb_notification_delivery");
        jdbcTemplate.update("DELETE FROM tb_outbox_event");
    }

    @Test
    void staleOutboxOwnerCannotMarkSentAfterLeaseTakeover() {
        LocalDateTime claimAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OutboxEvent event = insertOutboxEvent(claimAt.minusSeconds(1));

        assertThat(outboxEventMapper.claimReadyEvents(
                OUTBOX_READY, claimAt, 1, "worker-a", claimAt.plusSeconds(1))).isEqualTo(1);
        LocalDateTime afterLeaseExpired = claimAt.plusSeconds(2);
        assertThat(outboxEventMapper.claimReadyEvents(
                OUTBOX_READY, afterLeaseExpired, 1, "worker-b", afterLeaseExpired.plusMinutes(1))).isEqualTo(1);

        assertThat(outboxEventMapper.markSent(event.getId(), "worker-a", afterLeaseExpired.plusSeconds(1))).isZero();
        Map<String, Object> ownedByB = relayState("tb_outbox_event", event.getId());
        assertThat(ownedByB.get("claim_owner")).isEqualTo("worker-b");
        assertThat(ownedByB.get("status")).isEqualTo("PENDING");

        assertThat(outboxEventMapper.markSent(event.getId(), "worker-b", afterLeaseExpired.plusSeconds(2))).isEqualTo(1);
        Map<String, Object> completed = relayState("tb_outbox_event", event.getId());
        assertThat(completed.get("status")).isEqualTo("SENT");
        assertThat(completed.get("claim_owner")).isNull();
    }

    @Test
    void staleDeliveryOwnerCannotMarkDeadAfterLeaseTakeover() {
        LocalDateTime claimAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        NotificationDelivery delivery = insertNotificationDelivery(claimAt.minusSeconds(1));

        assertThat(notificationDeliveryMapper.claimReadyDeliveries(
                DELIVERY_READY, claimAt, 1, "worker-a", claimAt.plusSeconds(1))).isEqualTo(1);
        LocalDateTime afterLeaseExpired = claimAt.plusSeconds(2);
        assertThat(notificationDeliveryMapper.claimReadyDeliveries(
                DELIVERY_READY, afterLeaseExpired, 1, "worker-b", afterLeaseExpired.plusMinutes(1))).isEqualTo(1);

        assertThat(notificationDeliveryMapper.markDead(
                delivery.getId(), "worker-a", 1, "late failure", afterLeaseExpired.plusSeconds(1))).isZero();
        Map<String, Object> ownedByB = relayState("tb_notification_delivery", delivery.getId());
        assertThat(ownedByB.get("claim_owner")).isEqualTo("worker-b");
        assertThat(ownedByB.get("status")).isEqualTo("PENDING");

        assertThat(notificationDeliveryMapper.markSent(
                delivery.getId(), "worker-b", afterLeaseExpired.plusSeconds(2))).isEqualTo(1);
        Map<String, Object> completed = relayState("tb_notification_delivery", delivery.getId());
        assertThat(completed.get("status")).isEqualTo("SENT");
        assertThat(completed.get("claim_owner")).isNull();
    }

    private OutboxEvent insertOutboxEvent(LocalDateTime nextRetryAt) {
        OutboxEvent event = OutboxEvent.builder()
                .eventKey("ownership-outbox-" + System.nanoTime())
                .eventType("PRICE_ALERT_TARGET_REACHED_V1")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attempts(0)
                .nextRetryAt(nextRetryAt)
                .createdAt(nextRetryAt)
                .updatedAt(nextRetryAt)
                .build();
        assertThat(outboxEventMapper.insertIgnore(event)).isEqualTo(1);
        return event;
    }

    private NotificationDelivery insertNotificationDelivery(LocalDateTime nextRetryAt) {
        NotificationDelivery delivery = NotificationDelivery.builder()
                .eventKey("ownership-delivery-" + System.nanoTime())
                .channel("WEBHOOK")
                .payload("{}")
                .status(NotificationDeliveryStatus.PENDING)
                .attempts(0)
                .nextRetryAt(nextRetryAt)
                .createdAt(nextRetryAt)
                .updatedAt(nextRetryAt)
                .build();
        assertThat(notificationDeliveryMapper.insertIgnore(delivery)).isEqualTo(1);
        return delivery;
    }

    private Map<String, Object> relayState(String table, Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, claim_owner, claimed_at, claimed_until FROM " + table + " WHERE id = ?",
                id);
    }
}
