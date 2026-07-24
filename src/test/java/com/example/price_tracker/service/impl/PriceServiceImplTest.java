package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.mq.producer.PriceAlertProducer;
import com.example.price_tracker.provider.PriceProvider;
import com.example.price_tracker.provider.PriceProviderException;
import com.example.price_tracker.provider.PriceProviderFailureType;
import com.example.price_tracker.provider.PriceProviderRouter;
import com.example.price_tracker.provider.PriceQuote;
import com.example.price_tracker.metrics.PriceTrackerMetrics;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.redis.ProductCacheEvictionCoordinator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PriceServiceImplTest {

    private static final LocalDateTime CAPTURED_AT = LocalDateTime.of(2026, 6, 21, 11, 0);

    @Mock
    private ProductMapper productMapper;

    @Mock
    private PriceHistoryMapper priceHistoryMapper;

    @Mock
    private WatchlistMapper watchlistMapper;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private PriceAlertProducer priceAlertProducer;

    @Mock
    private PriceProviderRouter priceProviderRouter;

    @Mock
    private PriceProvider priceProvider;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private ProductCacheEvictionCoordinator productCacheEvictionCoordinator;

    @Mock
    private PriceTrackerMetrics metrics;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private PlatformTransactionManager transactionManager;

    private PriceServiceImpl priceService;

    @BeforeEach
    void setUp() {
        TransactionStatus mockStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mockStatus);
        lenient().when(productMapper.updateRefreshMetadataIfPriceMatches(
                any(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(productMapper.updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(priceHistoryMapper.insert(any(PriceHistory.class))).thenReturn(1);

        priceService = new PriceServiceImpl(
                productMapper,
                priceHistoryMapper,
                watchlistMapper,
                outboxEventMapper,
                priceProviderRouter,
                cacheService,
                productCacheEvictionCoordinator,
                metrics,
                objectMapper,
                transactionManager
        );
    }

    @Test
    void refreshProductPriceCreatesHistoryAndAlertMessageWhenTargetReached() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "CNY");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(
                RedisKeyManager.notificationIdempotentKey("99:1:80.00"),
                "1",
                java.time.Duration.ofMinutes(10))).thenReturn(true);
        when(outboxEventMapper.insertEvent(any(OutboxEvent.class))).thenReturn(1);

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateRefreshPriceIfPriceMatches(
                eq(1L), eq(1), eq(new BigDecimal("100.00")), eq(new BigDecimal("79.00")),
                eq("CNY"), eq(CAPTURED_AT), eq(CAPTURED_AT));
        verify(priceHistoryMapper).insert(argThat(createdPriceHistory("79.00")));
        verify(priceAlertProducer, never()).send(any(PriceAlertMessage.class));
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insertEvent(outboxCaptor.capture());
        assertThat(createdOutboxEvent().matches(outboxCaptor.getValue())).isTrue();
        verify(productCacheEvictionCoordinator).registerProductCacheEvictionAfterCommit(1L);
    }

    @Test
    void refreshProductPriceRecordsHistoryButDoesNotSendAlertWhenChangedPriceIsAboveTarget() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("81.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateRefreshPriceIfPriceMatches(
                eq(1L), eq(1), eq(new BigDecimal("100.00")), eq(new BigDecimal("81.00")),
                eq("USD"), eq(CAPTURED_AT), eq(CAPTURED_AT));
        verify(priceHistoryMapper).insert(any(PriceHistory.class));
        verify(priceAlertProducer, never()).send(any(PriceAlertMessage.class));
        verify(productCacheEvictionCoordinator).registerProductCacheEvictionAfterCommit(1L);
    }

    @Test
    void refreshProductPriceSkipsDuplicateAlertWhenIdempotentKeyAlreadyExists() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(
                RedisKeyManager.notificationIdempotentKey("99:1:80.00"),
                "1",
                java.time.Duration.ofMinutes(10))).thenReturn(false);

        priceService.refreshProductPrice(1L);

        verify(priceAlertProducer, never()).send(any(PriceAlertMessage.class));
    }

    @Test
    void refreshProductPriceSkipsDuplicateOutboxEventKeyWithoutFailingRefresh() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "CNY");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(
                RedisKeyManager.notificationIdempotentKey("99:1:80.00"),
                "1",
                java.time.Duration.ofMinutes(10))).thenReturn(true);
        when(outboxEventMapper.insertEvent(any(OutboxEvent.class)))
                .thenThrow(new DuplicateKeyException("duplicate event key"));

        priceService.refreshProductPrice(1L);

        verify(priceHistoryMapper).insert(any(PriceHistory.class));
        verify(priceAlertProducer, never()).send(any(PriceAlertMessage.class));
        verify(outboxEventMapper).insertEvent(any(OutboxEvent.class));
    }

    @Test
    void refreshActiveProductsProcessesAllActiveProductsByPage() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 2);
        when(productMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<Product> request = invocation.getArgument(0);
            Page<Product> page = new Page<>(request.getCurrent(), request.getSize());
            page.setTotal(3);
            if (request.getCurrent() == 1L) {
                page.setRecords(List.of(activeProduct(1L), activeProduct(2L)));
            } else if (request.getCurrent() == 2L) {
                page.setRecords(List.of(activeProduct(3L)));
            } else {
                page.setRecords(List.of());
            }
            return page;
        });
        when(productMapper.selectById(1L)).thenReturn(activeProduct(1L));
        when(productMapper.selectById(2L)).thenReturn(activeProduct(2L));
        when(productMapper.selectById(3L)).thenReturn(activeProduct(3L));
        mockQuote("101.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of());

        priceService.refreshActiveProducts();

        verify(productMapper, times(3)).updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any());
        verify(productMapper, times(2)).selectPage(any(Page.class), any());
    }

    @Test
    void refreshActiveProductsContinuesWhenSingleProductFails() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 2);
        when(productMapper.selectPage(any(Page.class), any())).thenReturn(activeProductPage(activeProduct(1L), activeProduct(2L)));
        when(productMapper.selectById(1L)).thenThrow(new RuntimeException("refresh failed"));
        when(productMapper.selectById(2L)).thenReturn(activeProduct(2L));
        mockQuote("101.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of());

        priceService.refreshActiveProducts();

        verify(productMapper).updateRefreshPriceIfPriceMatches(
                eq(2L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refreshActiveProductsRetriesFailedProductAtMostTwoTimes() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 1);
        when(productMapper.selectPage(any(Page.class), any())).thenReturn(activeProductPage(activeProduct(1L)));
        when(productMapper.selectById(1L)).thenThrow(new RuntimeException("refresh failed"));

        priceService.refreshActiveProducts();

        verify(productMapper, times(3)).selectById(1L);
        verify(productMapper, never()).updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any());
        verify(productMapper, atLeastOnce()).selectPage(any(Page.class), any());
    }

    private ArgumentMatcher<PriceHistory> createdPriceHistory(String expectedPrice) {
        return history -> history.getProductId().equals(1L)
                && new BigDecimal("100.00").compareTo(history.getOldPrice()) == 0
                && new BigDecimal(expectedPrice).compareTo(history.getNewPrice()) == 0
                && "MOCK".equals(history.getSource())
                && CAPTURED_AT.equals(history.getCapturedAt());
    }

    private ArgumentMatcher<PriceAlertMessage> createdPriceAlertMessage() {
        return message -> message.getMessageId() != null
                && !message.getMessageId().isBlank()
                && "TARGET_PRICE_REACHED:99:1:5:80.00:79.00:1782039600000".equals(message.getEventKey())
                && message.getUserId().equals(99L)
                && message.getProductId().equals(1L)
                && message.getWatchlistId().equals(5L)
                && "Laptop".equals(message.getProductName())
                && new BigDecimal("79.00").compareTo(message.getCurrentPrice()) == 0
                && new BigDecimal("80.00").compareTo(message.getTargetPrice()) == 0
                && CAPTURED_AT.equals(message.getTriggeredAt());
    }

    private ArgumentMatcher<OutboxEvent> createdOutboxEvent() {
        return event -> "TARGET_PRICE_REACHED:99:1:5:80.00:79.00:1782039600000".equals(event.getEventKey())
                && "PRICE_ALERT_TARGET_REACHED_V1".equals(event.getEventType())
                && OutboxEventStatus.PENDING == event.getStatus()
                && event.getAttempts() == 0
                && CAPTURED_AT.equals(event.getNextRetryAt())
                && event.getPayload() != null
                && event.getPayload().contains("\"eventKey\":\"TARGET_PRICE_REACHED:99:1:5:80.00:79.00:1782039600000\"")
                && event.getPayload().contains("\"messageId\":\"TARGET_PRICE_REACHED:99:1:5:80.00:79.00:1782039600000\"");
    }

    private Product activeProduct() {
        return activeProduct(1L);
    }

    private Product activeProduct(Long id) {
        Product product = new Product();
        product.setId(id);
        product.setProductName("Laptop");
        product.setCurrentPrice(new BigDecimal("100.00"));
        product.setCurrency("USD");
        product.setStatus(1);
        return product;
    }

    @SafeVarargs
    private Page<Product> activeProductPage(Product... products) {
        Page<Product> page = new Page<>(1, products.length == 0 ? 1 : products.length);
        page.setTotal(products.length);
        page.setRecords(List.of(products));
        return page;
    }

    private Watchlist activeWatchlistWithoutDedupPrice() {
        Watchlist watchlist = new Watchlist();
        watchlist.setId(5L);
        watchlist.setUserId(99L);
        watchlist.setProductId(1L);
        watchlist.setTargetPrice(new BigDecimal("80.00"));
        watchlist.setNotifyEnabled(1);
        watchlist.setStatus(1);
        return watchlist;
    }

    private void mockQuote(String price, String currency) {
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        lenient().when(priceProvider.providerCode()).thenReturn("MOCK");
        when(priceProvider.fetchPrice(any(Product.class))).thenReturn(new PriceQuote(
                new BigDecimal(price),
                currency,
                "MOCK",
                CAPTURED_AT,
                null,
                "Laptop"));
    }

    @Test
    void refreshProductPriceThrowsPriceProviderExceptionAndRecordsSpecificMetrics() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        when(priceProvider.providerCode()).thenReturn("SERPAPI");
        when(priceProvider.fetchPrice(any(Product.class))).thenThrow(
                new PriceProviderException(PriceProviderFailureType.RATE_LIMITED, true, "serpapi rate limited"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(PriceProviderException.class);

        verify(metrics).recordPriceProviderFailure("SERPAPI", "RATE_LIMITED");
        verify(metrics).recordPriceProviderFetch(eq("SERPAPI"), eq(PriceTrackerMetrics.RESULT_FAILED), any(java.time.Duration.class));
        verify(metrics).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "SERPAPI");
    }

    @Test
    void refreshActiveProductsAbortsImmediatelyOnNonRetryableProviderException() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 1);
        when(productMapper.selectPage(any(Page.class), any())).thenReturn(activeProductPage(activeProduct(1L)));
        when(productMapper.selectById(1L)).thenReturn(activeProduct(1L));
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        when(priceProvider.providerCode()).thenReturn("SERPAPI");
        when(priceProvider.fetchPrice(any(Product.class))).thenThrow(
                new PriceProviderException(PriceProviderFailureType.AUTHENTICATION_FAILED, false, "invalid key"));

        priceService.refreshActiveProducts();

        // Should only query the product once and abort retrying because it's not retryable
        verify(productMapper, times(1)).selectById(1L);
    }

    @Test
    void refreshProductPriceTreatsUnchangedAffectedRowsZeroAsLegalNoOp() {
        Product currentState = activeProduct();
        currentState.setLastCheckedAt(CAPTURED_AT);
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("100.00", "USD");
        when(productMapper.updateRefreshMetadataIfPriceMatches(
                eq(1L), eq(1), eq(new BigDecimal("100.00")), eq("USD"), eq(CAPTURED_AT)))
                .thenReturn(0);
        when(productMapper.selectRefreshStateForUpdate(1L)).thenReturn(currentState);

        priceService.refreshProductPrice(1L);

        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
        verify(outboxEventMapper, never()).insertEvent(any());
        verify(productCacheEvictionCoordinator, never()).registerProductCacheEvictionAfterCommit(any());
    }

    @Test
    void refreshProductPriceUsesIsNullGuardForMissingDatabasePrice() {
        Product productWithoutPrice = activeProduct();
        productWithoutPrice.setCurrentPrice(null);
        Watchlist watchlist = activeWatchlistWithoutDedupPrice();
        watchlist.setTargetPrice(new BigDecimal("100.00"));
        when(productMapper.selectById(1L)).thenReturn(productWithoutPrice);
        mockQuote("100.00", "USD");
        when(productMapper.updateRefreshPriceIfPriceMatches(
                eq(1L), eq(1), isNull(), eq(new BigDecimal("100.00")),
                eq("USD"), eq(CAPTURED_AT), eq(CAPTURED_AT))).thenReturn(1);
        when(watchlistMapper.selectList(any())).thenReturn(List.of(watchlist));
        when(cacheService.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
        when(outboxEventMapper.insertEvent(any())).thenReturn(1);

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateRefreshPriceIfPriceMatches(
                eq(1L), eq(1), isNull(), eq(new BigDecimal("100.00")),
                eq("USD"), eq(CAPTURED_AT), eq(CAPTURED_AT));
        verify(productMapper, never()).updateRefreshMetadataIfPriceMatches(
                any(), any(), any(), any(), any());
        verify(priceHistoryMapper).insert(argThat((PriceHistory history) ->
                new BigDecimal("100.00").compareTo(history.getOldPrice()) == 0
                        && new BigDecimal("100.00").compareTo(history.getNewPrice()) == 0));
        verify(outboxEventMapper).insertEvent(any(OutboxEvent.class));
    }

    @Test
    void refreshProductPriceDoesNotMarkProviderFailedWhenPersistenceDetectsPriceConflict() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(productMapper.updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(productMapper.selectRefreshStateForUpdate(1L))
                .thenReturn(Product.builder()
                        .id(1L)
                        .status(1)
                        .currentPrice(new BigDecimal("90.00"))
                        .build());

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(PriceRefreshConflictException.class);

        verify(metrics).recordPriceProviderFetch(
                eq("MOCK"),
                eq(PriceTrackerMetrics.RESULT_SUCCESS),
                any(java.time.Duration.class));
        verify(metrics, never()).recordPriceProviderFetch(
                eq("MOCK"),
                eq(PriceTrackerMetrics.RESULT_FAILED),
                any(java.time.Duration.class));
        verify(metrics).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "MOCK");
        verify(metrics, never()).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_SUCCESS, "MOCK");
    }

    @Test
    void refreshProductPriceDoesNotMarkProviderFailedWhenHistoryInsertThrows() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(priceHistoryMapper.insert(any(PriceHistory.class)))
                .thenThrow(new DataAccessResourceFailureException("history unavailable"));

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("history unavailable");

        verify(metrics).recordPriceProviderFetch(
                eq("MOCK"),
                eq(PriceTrackerMetrics.RESULT_SUCCESS),
                any(java.time.Duration.class));
        verify(metrics, never()).recordPriceProviderFetch(
                eq("MOCK"),
                eq(PriceTrackerMetrics.RESULT_FAILED),
                any(java.time.Duration.class));
        verify(metrics).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "MOCK");
    }

    @Test
    void refreshProductPriceRecordsAttemptSuccessAfterTransactionCommit() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("100.00", "USD");

        priceService.refreshProductPrice(1L);

        InOrder inOrder = inOrder(transactionManager, metrics);
        inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
        inOrder.verify(metrics).recordPriceRefreshAttempt(
                PriceTrackerMetrics.RESULT_SUCCESS,
                "MOCK");
        verify(metrics, never()).recordPriceRefreshAttempt(
                PriceTrackerMetrics.RESULT_FAILED,
                "MOCK");
    }

    @Test
    void refreshProductPriceRecordsAttemptFailureWhenCommitFails() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("100.00", "USD");
        doThrow(new RuntimeException("commit failed"))
                .when(transactionManager).commit(any(TransactionStatus.class));

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("commit failed");

        verify(metrics).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "MOCK");
        verify(metrics, never()).recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_SUCCESS, "MOCK");
    }

    @Test
    void refreshProductWithRetryIgnoresAttemptSuccessMetricFailureAndClearsProviderContext() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("100.00", "USD");
        doThrow(new RuntimeException("metrics unavailable"))
                .when(metrics)
                .recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_SUCCESS, "MOCK");

        Integer notificationCount = ReflectionTestUtils.invokeMethod(
                priceService,
                "refreshProductWithRetry",
                1L);

        assertThat(notificationCount).isZero();
        @SuppressWarnings("unchecked")
        ThreadLocal<String> providerContext = (ThreadLocal<String>) ReflectionTestUtils.getField(
                priceService,
                "lastResolvedProvider");
        assertThat(providerContext).isNotNull();
        assertThat(providerContext.get()).isNull();
        verify(priceProvider, times(1)).fetchPrice(any(Product.class));
        verify(metrics).recordPriceRefreshFinal(PriceTrackerMetrics.RESULT_SUCCESS, "MOCK");
    }

    @Test
    void refreshProductWithRetryIgnoresFinalSuccessMetricFailureWithoutRetrying() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("100.00", "USD");
        doThrow(new RuntimeException("final metric unavailable"))
                .when(metrics)
                .recordPriceRefreshFinal(PriceTrackerMetrics.RESULT_SUCCESS, "MOCK");

        Integer notificationCount = ReflectionTestUtils.invokeMethod(
                priceService,
                "refreshProductWithRetry",
                1L);

        assertThat(notificationCount).isZero();
        verify(priceProvider, times(1)).fetchPrice(any(Product.class));
        @SuppressWarnings("unchecked")
        ThreadLocal<String> providerContext = (ThreadLocal<String>) ReflectionTestUtils.getField(
                priceService,
                "lastResolvedProvider");
        assertThat(providerContext).isNotNull();
        assertThat(providerContext.get()).isNull();
    }

    @Test
    void refreshProductWithRetryPreservesUnavailableExceptionWhenFinalMetricFails() {
        when(productMapper.selectById(1L)).thenReturn(null);
        doThrow(new RuntimeException("final metric unavailable"))
                .when(metrics)
                .recordPriceRefreshFinal(PriceTrackerMetrics.RESULT_FAILED, "unknown");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                priceService,
                "refreshProductWithRetry",
                1L))
                .isInstanceOf(ProductRefreshUnavailableException.class);

        verify(productMapper, times(1)).selectById(1L);
        verify(priceProvider, never()).fetchPrice(any(Product.class));
    }

    @Test
    void refreshProductWithRetryPreservesNonRetryableProviderExceptionWhenMetricsFail() {
        PriceProviderException providerException = new PriceProviderException(
                PriceProviderFailureType.AUTHENTICATION_FAILED,
                false,
                "invalid key");
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        when(priceProvider.providerCode()).thenReturn("SERPAPI");
        when(priceProvider.fetchPrice(any(Product.class))).thenThrow(providerException);
        doThrow(new RuntimeException("provider fetch metric unavailable"))
                .when(metrics)
                .recordPriceProviderFetch(
                        eq("SERPAPI"),
                        eq(PriceTrackerMetrics.RESULT_FAILED),
                        any(java.time.Duration.class));
        doThrow(new RuntimeException("provider failure metric unavailable"))
                .when(metrics)
                .recordPriceProviderFailure("SERPAPI", "AUTHENTICATION_FAILED");
        doThrow(new RuntimeException("attempt metric unavailable"))
                .when(metrics)
                .recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "SERPAPI");
        doThrow(new RuntimeException("final metric unavailable"))
                .when(metrics)
                .recordPriceRefreshFinal(PriceTrackerMetrics.RESULT_FAILED, "SERPAPI");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                priceService,
                "refreshProductWithRetry",
                1L))
                .isSameAs(providerException);

        verify(priceProvider, times(1)).fetchPrice(any(Product.class));
        verify(metrics).recordPriceProviderFailure("SERPAPI", "AUTHENTICATION_FAILED");
    }

    @Test
    void refreshProductWithRetryPreservesLastConflictWhenFinalMetricFails() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(productMapper.updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(productMapper.selectRefreshStateForUpdate(1L))
                .thenReturn(Product.builder()
                        .id(1L)
                        .status(1)
                        .currentPrice(new BigDecimal("90.00"))
                        .build());
        doThrow(new RuntimeException("final metric unavailable"))
                .when(metrics)
                .recordPriceRefreshFinal(PriceTrackerMetrics.RESULT_FAILED, "MOCK");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                priceService,
                "refreshProductWithRetry",
                1L))
                .isInstanceOf(PriceRefreshConflictException.class)
                .hasMessageContaining("actualPrice=90.00");

        verify(priceProvider, times(3)).fetchPrice(any(Product.class));
    }

    @Test
    void refreshProductPricePreservesDatabaseExceptionWhenAttemptFailedMetricFails() {
        DataAccessResourceFailureException databaseException =
                new DataAccessResourceFailureException("history unavailable");
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(priceHistoryMapper.insert(any(PriceHistory.class))).thenThrow(databaseException);
        doThrow(new RuntimeException("attempt metric unavailable"))
                .when(metrics)
                .recordPriceRefreshAttempt(PriceTrackerMetrics.RESULT_FAILED, "MOCK");

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isSameAs(databaseException);
    }

    @Test
    void refreshProductPriceContinuesPersistenceWhenProviderSuccessMetricFails() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("provider metric unavailable"))
                .when(metrics)
                .recordPriceProviderFetch(
                        eq("MOCK"),
                        eq(PriceTrackerMetrics.RESULT_SUCCESS),
                        any(java.time.Duration.class));

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateRefreshPriceIfPriceMatches(
                eq(1L), eq(1), eq(new BigDecimal("100.00")), eq(new BigDecimal("79.00")),
                eq("USD"), eq(CAPTURED_AT), eq(CAPTURED_AT));
        verify(priceHistoryMapper).insert(any(PriceHistory.class));
    }

    @Test
    void refreshProductPricePreservesProviderExceptionWhenProviderFailedMetricFails() {
        PriceProviderException providerException = new PriceProviderException(
                PriceProviderFailureType.RATE_LIMITED,
                true,
                "rate limited");
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceProviderRouter.route(any(Product.class))).thenReturn(priceProvider);
        when(priceProvider.providerCode()).thenReturn("SERPAPI");
        when(priceProvider.fetchPrice(any(Product.class))).thenThrow(providerException);
        doThrow(new RuntimeException("provider metric unavailable"))
                .when(metrics)
                .recordPriceProviderFetch(
                        eq("SERPAPI"),
                        eq(PriceTrackerMetrics.RESULT_FAILED),
                        any(java.time.Duration.class));

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isSameAs(providerException);

        verify(metrics).recordPriceProviderFailure("SERPAPI", "RATE_LIMITED");
    }

    @Test
    void refreshProductPriceRejectsUnexpectedOutboxAffectedRows() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
        when(outboxEventMapper.insertEvent(any())).thenReturn(0);

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outbox event insert affected unexpected rows");
    }

    @Test
    void refreshProductPriceThrowsWhenPriceHistoryInsertDoesNotAffectOneRow() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(priceHistoryMapper.insert(any(PriceHistory.class))).thenReturn(0);

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("price history insert affected unexpected rows");

        verify(outboxEventMapper, never()).insertEvent(any());
    }

    @Test
    void refreshProductPricePropagatesNonDuplicateOutboxFailure() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
        when(outboxEventMapper.insertEvent(any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("database unavailable");
    }

    @Test
    void refreshProductPricePreservesJsonProcessingExceptionCause() throws Exception {
        JsonProcessingException jsonException = new JsonProcessingException("serialization failed") { };
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));
        when(cacheService.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
        doThrow(jsonException).when(objectMapper).writeValueAsString(any(PriceAlertMessage.class));

        assertThatThrownBy(() -> priceService.refreshProductPrice(1L))
                .isInstanceOf(BusinessException.class)
                .hasCause(jsonException);

        verify(outboxEventMapper, never()).insertEvent(any());
    }

    @Test
    void refreshActiveProductsDoesNotRetryUnavailableProduct() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 1);
        when(productMapper.selectPage(any(Page.class), any())).thenReturn(activeProductPage(activeProduct(1L)));
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        mockQuote("79.00", "USD");
        when(productMapper.updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(productMapper.selectRefreshStateForUpdate(1L))
                .thenReturn(Product.builder().id(1L).status(0).currentPrice(new BigDecimal("100.00")).build());

        priceService.refreshActiveProducts();

        verify(productMapper, times(1)).selectById(1L);
        verify(productMapper, times(1)).selectRefreshStateForUpdate(1L);
    }

    @Test
    void refreshActiveProductsRetriesConcurrentPriceChange() {
        ReflectionTestUtils.setField(priceService, "priceRefreshBatchSize", 1);
        Product retriedProduct = activeProduct();
        retriedProduct.setCurrentPrice(new BigDecimal("90.00"));
        when(productMapper.selectPage(any(Page.class), any())).thenReturn(activeProductPage(activeProduct(1L)));
        when(productMapper.selectById(1L)).thenReturn(activeProduct(), retriedProduct);
        mockQuote("79.00", "USD");
        when(watchlistMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0, 1);
        when(productMapper.selectRefreshStateForUpdate(1L))
                .thenReturn(Product.builder().id(1L).status(1).currentPrice(new BigDecimal("90.00")).build());

        priceService.refreshActiveProducts();

        verify(productMapper, times(2)).selectById(1L);
        verify(productMapper, times(2)).updateRefreshPriceIfPriceMatches(
                any(), any(), any(), any(), any(), any(), any());
    }
}
