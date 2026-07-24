package com.example.price_tracker;

import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.entity.OutboxEvent;
import com.example.price_tracker.entity.OutboxEventStatus;
import com.example.price_tracker.entity.PriceHistory;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.exception.PriceRefreshConflictException;
import com.example.price_tracker.exception.ProductRefreshUnavailableException;
import com.example.price_tracker.mapper.OutboxEventMapper;
import com.example.price_tracker.mapper.PriceHistoryMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import com.example.price_tracker.mq.message.PriceAlertEventKeyBuilder;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.provider.PriceProvider;
import com.example.price_tracker.provider.PriceProviderRouter;
import com.example.price_tracker.provider.PriceQuote;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.PriceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class PriceRefreshConsistencyIT {

    private static final LocalDateTime CAPTURED_AT = LocalDateTime.of(2026, 7, 21, 9, 30);
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 30;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("price_tracker")
            .withUsername("price_tracker")
            .withPassword("price_tracker");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> appendQueryParameter(mysql.getJdbcUrl(), "useAffectedRows=true"));
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private PriceService priceService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private WatchlistMapper watchlistMapper;

    @SpyBean
    private PriceHistoryMapper priceHistoryMapper;

    @SpyBean
    private OutboxEventMapper outboxEventMapper;

    @SpyBean
    private RedisCacheService cacheService;

    @SpyBean
    private ObjectMapper objectMapper;

    @MockBean
    private PriceProviderRouter priceProviderRouter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private PriceProvider priceProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM tb_outbox_event");
        jdbcTemplate.execute("DELETE FROM tb_price_history");
        jdbcTemplate.execute("DELETE FROM tb_watchlist");
        jdbcTemplate.execute("DELETE FROM tb_product");
        redisConnectionFactory.getConnection().serverCommands().flushDb();

        priceProvider = mock(PriceProvider.class);
        when(priceProvider.providerCode()).thenReturn("TEST");
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        clearInvocations(cacheService, priceHistoryMapper, outboxEventMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        reset(priceProviderRouter, priceHistoryMapper, outboxEventMapper, cacheService, objectMapper);
    }

    @Test
    void concurrentAdminDisablePreventsRefreshAndRollsBackDependentWrites() throws Exception {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch continueProvider = new CountDownLatch(1);
        when(priceProvider.fetchPrice(any(Product.class))).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(continueProvider.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return quote("79.00", "USD", CAPTURED_AT);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh = executor.submit(() -> priceService.refreshProductPrice(product.getId()));
            assertThat(providerEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.update("UPDATE tb_product SET status = 0 WHERE id = ?", product.getId()))
                    .isEqualTo(1);
            continueProvider.countDown();

            assertFutureCause(refresh, ProductRefreshUnavailableException.class);
        } finally {
            continueProvider.countDown();
            executor.shutdownNow();
        }

        assertThat(productStatus(product.getId())).isZero();
        assertThat(productPrice(product.getId())).isEqualByComparingTo("100.00");
        assertThat(countRows("tb_price_history")).isZero();
        assertThat(countRows("tb_outbox_event")).isZero();
        verifyNoProductCacheDeletion(product.getId());
    }

    @Test
    void concurrentRefreshCannotOverwriteFirstCommittedPrice() throws Exception {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        CountDownLatch firstProviderEntered = new CountDownLatch(1);
        CountDownLatch bothProvidersEntered = new CountDownLatch(2);
        CountDownLatch firstMayContinue = new CountDownLatch(1);
        CountDownLatch secondMayContinue = new CountDownLatch(1);
        AtomicInteger invocationIndex = new AtomicInteger();
        when(priceProvider.fetchPrice(any(Product.class))).thenAnswer(invocation -> {
            int index = invocationIndex.getAndIncrement();
            if (index == 0) {
                firstProviderEntered.countDown();
            }
            bothProvidersEntered.countDown();
            CountDownLatch gate = index == 0 ? firstMayContinue : secondMayContinue;
            assertThat(gate.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return quote(index == 0 ? "90.00" : "80.00", "USD", CAPTURED_AT.plusSeconds(index));
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> priceService.refreshProductPrice(product.getId()));
            assertThat(firstProviderEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> priceService.refreshProductPrice(product.getId()));
            assertThat(bothProvidersEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            firstMayContinue.countDown();
            first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondMayContinue.countDown();
            assertFutureCause(second, PriceRefreshConflictException.class);
        } finally {
            firstMayContinue.countDown();
            secondMayContinue.countDown();
            executor.shutdownNow();
        }

        assertThat(productPrice(product.getId())).isEqualByComparingTo("90.00");
        List<PriceHistory> histories = priceHistoryMapper.selectList(null);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getOldPrice()).isEqualByComparingTo("100.00");
        assertThat(histories.get(0).getNewPrice()).isEqualByComparingTo("90.00");
        assertThat(countRows("tb_outbox_event")).isZero();
    }

    @Test
    void historyInsertFailureRollsBackProductAndDoesNotEvictProductCache() {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(quote("79.00", "USD", CAPTURED_AT));
        doReturn(0).when(priceHistoryMapper).insert(any(PriceHistory.class));

        assertThatThrownBy(() -> priceService.refreshProductPrice(product.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("price history insert affected unexpected rows");

        assertThat(productPrice(product.getId())).isEqualByComparingTo("100.00");
        assertThat(countRows("tb_price_history")).isZero();
        assertThat(countRows("tb_outbox_event")).isZero();
        verifyNoProductCacheDeletion(product.getId());
    }

    @Test
    void missingDatabasePriceIsPersistedWhenQuoteEqualsDefaultPrice() {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(jdbcTemplate.update(
                "UPDATE tb_product SET current_price = NULL WHERE id = ?",
                product.getId())).isEqualTo(1);
        insertWatchlist(product.getId(), "100.00");
        when(priceProvider.fetchPrice(any(Product.class)))
                .thenReturn(quote("100.00", "USD", CAPTURED_AT));

        priceService.refreshProductPrice(product.getId());

        assertThat(productPrice(product.getId())).isEqualByComparingTo("100.00");
        List<PriceHistory> histories = priceHistoryMapper.selectList(null);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getOldPrice()).isEqualByComparingTo("100.00");
        assertThat(histories.get(0).getNewPrice()).isEqualByComparingTo("100.00");
        assertThat(countRows("tb_outbox_event")).isEqualTo(1);
    }

    @Test
    void duplicateOutboxEventIsIdempotentButOtherDatabaseErrorsRollBack() {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        Watchlist watchlist = insertWatchlist(product.getId(), "80.00");
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(quote("79.00", "USD", CAPTURED_AT));
        String eventKey = PriceAlertEventKeyBuilder.buildTargetPriceReachedKey(
                watchlist.getUserId(), product.getId(), watchlist.getId(),
                watchlist.getTargetPrice(), new BigDecimal("79.00"), CAPTURED_AT);
        insertOutbox(eventKey, CAPTURED_AT);

        priceService.refreshProductPrice(product.getId());

        assertThat(productPrice(product.getId())).isEqualByComparingTo("79.00");
        assertThat(countRows("tb_price_history")).isEqualTo(1);
        assertThat(countRows("tb_outbox_event")).isEqualTo(1);

        Product failingProduct = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        insertWatchlist(failingProduct.getId(), "80.00");
        String idempotentKey = RedisKeyManager.notificationIdempotentKey(
                99L + ":" + failingProduct.getId() + ":80.00");
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(outboxEventMapper).insertEvent(any(OutboxEvent.class));

        assertThatThrownBy(() -> priceService.refreshProductPrice(failingProduct.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(productPrice(failingProduct.getId())).isEqualByComparingTo("100.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_price_history WHERE product_id = ?",
                Integer.class,
                failingProduct.getId())).isZero();
        assertThat(cacheService.get(idempotentKey, String.class)).isNull();
        verifyNoProductCacheDeletion(failingProduct.getId());
    }

    @Test
    void cacheEvictionRunsAfterCommitAndFailureDoesNotChangeCommittedResult() {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(quote("79.00", "USD", CAPTURED_AT));
        doAnswer(invocation -> {
            verifyNoProductCacheDeletion(product.getId());
            return jdbcTemplate.update(
                    "INSERT INTO tb_price_history(product_id, old_price, new_price, captured_at, source) VALUES (?, ?, ?, ?, ?)",
                    product.getId(), new BigDecimal("100.00"), new BigDecimal("79.00"), CAPTURED_AT, "TEST");
        }).when(priceHistoryMapper).insert(any(PriceHistory.class));
        doThrow(new RuntimeException("redis unavailable"))
                .when(cacheService).delete(RedisKeyManager.productDetailKey(product.getId()));

        priceService.refreshProductPrice(product.getId());

        assertThat(productPrice(product.getId())).isEqualByComparingTo("79.00");
        assertThat(countRows("tb_price_history")).isEqualTo(1);
        verify(cacheService).delete(RedisKeyManager.productDetailKey(product.getId()));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(product.getId()));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:detail:" + product.getId()));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:price:" + product.getId()));
    }

    @Test
    void serializationFailurePreservesCauseRollsBackAndCleansIdempotentKey() throws Exception {
        Product product = insertProduct("100.00", "USD", 1, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        insertWatchlist(product.getId(), "80.00");
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(quote("79.00", "USD", CAPTURED_AT));
        JsonProcessingException jsonException = new JsonProcessingException("serialization failed") { };
        doThrow(jsonException).when(objectMapper).writeValueAsString(any(PriceAlertMessage.class));
        String idempotentKey = RedisKeyManager.notificationIdempotentKey(
                99L + ":" + product.getId() + ":80.00");

        assertThatThrownBy(() -> priceService.refreshProductPrice(product.getId()))
                .isInstanceOf(BusinessException.class)
                .hasCause(jsonException);

        assertThat(productPrice(product.getId())).isEqualByComparingTo("100.00");
        assertThat(countRows("tb_price_history")).isZero();
        assertThat(countRows("tb_outbox_event")).isZero();
        assertThat(cacheService.get(idempotentKey, String.class)).isNull();
        verifyNoProductCacheDeletion(product.getId());
    }

    @Test
    void affectedRowsNoOpIsAcceptedAndUpdatedAtRemainsUnchanged() {
        LocalDateTime originalUpdatedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        Product product = insertProduct("100.00", "USD", 1, CAPTURED_AT, originalUpdatedAt);
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(quote("100.00", "USD", CAPTURED_AT));

        priceService.refreshProductPrice(product.getId());

        assertThat(productUpdatedAt(product.getId())).isEqualTo(originalUpdatedAt);
        assertThat(countRows("tb_price_history")).isZero();
        assertThat(countRows("tb_outbox_event")).isZero();
        verifyNoProductCacheDeletion(product.getId());
    }

    private Product insertProduct(String price,
                                  String currency,
                                  int status,
                                  LocalDateTime lastCheckedAt,
                                  LocalDateTime updatedAt) {
        Product product = Product.builder()
                .productName("Test Product")
                .productUrl("https://example.com/product")
                .platform("test")
                .currentPrice(new BigDecimal(price))
                .currency(currency)
                .status(status)
                .lastCheckedAt(lastCheckedAt)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
        assertThat(productMapper.insert(product)).isEqualTo(1);
        return product;
    }

    private Watchlist insertWatchlist(Long productId, String targetPrice) {
        Watchlist watchlist = Watchlist.builder()
                .userId(99L)
                .productId(productId)
                .targetPrice(new BigDecimal(targetPrice))
                .notifyEnabled(1)
                .status(1)
                .createdAt(CAPTURED_AT.minusDays(1))
                .updatedAt(CAPTURED_AT.minusDays(1))
                .build();
        assertThat(watchlistMapper.insert(watchlist)).isEqualTo(1);
        return watchlist;
    }

    private void insertOutbox(String eventKey, LocalDateTime now) {
        OutboxEvent event = OutboxEvent.builder()
                .eventKey(eventKey)
                .eventType("PRICE_ALERT_TARGET_REACHED_V1")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attempts(0)
                .nextRetryAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        assertThat(outboxEventMapper.insertEvent(event)).isEqualTo(1);
        clearInvocations(outboxEventMapper);
    }

    private PriceQuote quote(String price, String currency, LocalDateTime capturedAt) {
        return new PriceQuote(new BigDecimal(price), currency, "TEST", capturedAt, null, "Test Product");
    }

    private BigDecimal productPrice(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_price FROM tb_product WHERE id = ?", BigDecimal.class, productId);
    }

    private int productStatus(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tb_product WHERE id = ?", Integer.class, productId);
    }

    private LocalDateTime productUpdatedAt(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tb_product WHERE id = ?", LocalDateTime.class, productId);
    }

    private int countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private void verifyNoProductCacheDeletion(Long productId) {
        verify(cacheService, never()).delete(RedisKeyManager.productDetailKey(productId));
        verify(cacheService, never()).delete(RedisKeyManager.productPriceKey(productId));
        verify(cacheService, never()).delete(RedisKeyManager.nullValueKey("product:detail:" + productId));
        verify(cacheService, never()).delete(RedisKeyManager.nullValueKey("product:price:" + productId));
    }

    private void assertFutureCause(Future<?> future, Class<? extends Throwable> expectedCause) {
        assertThatThrownBy(() -> future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(expectedCause);
    }

    private static String appendQueryParameter(String url, String parameter) {
        return url + (url.contains("?") ? "&" : "?") + parameter;
    }
}
