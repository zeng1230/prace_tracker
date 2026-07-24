package com.example.price_tracker;

import com.example.price_tracker.entity.Product;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.provider.PriceProvider;
import com.example.price_tracker.provider.PriceProviderRouter;
import com.example.price_tracker.provider.PriceQuote;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class PriceRefreshMatchedRowsIT {

    private static final LocalDateTime CAPTURED_AT = LocalDateTime.of(2026, 7, 21, 10, 0);
    private static final LocalDateTime ORIGINAL_UPDATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

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
    private PriceService priceService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PriceProviderRouter priceProviderRouter;

    @MockBean
    private RedisCacheService cacheService;

    private PriceProvider priceProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM tb_outbox_event");
        jdbcTemplate.execute("DELETE FROM tb_price_history");
        jdbcTemplate.execute("DELETE FROM tb_product");
        priceProvider = mock(PriceProvider.class);
        when(priceProvider.providerCode()).thenReturn("TEST");
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
    }

    @Test
    void matchedRowsNoOpStillSkipsHistoryAndOutbox() {
        Product product = Product.builder()
                .productName("Matched Row Product")
                .productUrl("https://example.com/matched")
                .platform("test")
                .currentPrice(new BigDecimal("100.00"))
                .currency("USD")
                .status(1)
                .lastCheckedAt(CAPTURED_AT)
                .createdAt(ORIGINAL_UPDATED_AT)
                .updatedAt(ORIGINAL_UPDATED_AT)
                .build();
        assertThat(productMapper.insert(product)).isEqualTo(1);
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(new PriceQuote(
                new BigDecimal("100.00"), "USD", "TEST", CAPTURED_AT, null, "Matched Row Product"));

        priceService.refreshProductPrice(product.getId());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_price_history", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_outbox_event", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tb_product WHERE id = ?", LocalDateTime.class, product.getId()))
                .isEqualTo(ORIGINAL_UPDATED_AT);
        verify(cacheService).delete(RedisKeyManager.productDetailKey(product.getId()));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(product.getId()));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:detail:" + product.getId()));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:price:" + product.getId()));
    }
}
