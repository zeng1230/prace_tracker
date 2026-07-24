package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.example.price_tracker.metrics.PriceTrackerMetrics;
import com.example.price_tracker.mq.message.PriceAlertEventKeyBuilder;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.provider.PriceProvider;
import com.example.price_tracker.provider.PriceProviderException;
import com.example.price_tracker.provider.PriceProviderRouter;
import com.example.price_tracker.provider.PriceQuote;
import com.example.price_tracker.redis.ProductCacheEvictionCoordinator;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.PriceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class PriceServiceImpl implements PriceService {

    private static final int ACTIVE_STATUS = 1;
    private static final int NOTIFY_ENABLED = 1;
    private static final String PRICE_ALERT_EVENT_TYPE = "PRICE_ALERT_TARGET_REACHED_V1";
    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("100.00");
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_REFRESH_RETRIES = 2;

    private final ProductMapper productMapper;
    private final PriceHistoryMapper priceHistoryMapper;
    private final WatchlistMapper watchlistMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final PriceProviderRouter priceProviderRouter;
    private final RedisCacheService cacheService;
    private final ProductCacheEvictionCoordinator productCacheEvictionCoordinator;
    private final PriceTrackerMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ThreadLocal<String> lastResolvedProvider = new ThreadLocal<>();

    public PriceServiceImpl(ProductMapper productMapper,
                            PriceHistoryMapper priceHistoryMapper,
                            WatchlistMapper watchlistMapper,
                            OutboxEventMapper outboxEventMapper,
                            PriceProviderRouter priceProviderRouter,
                            RedisCacheService cacheService,
                            ProductCacheEvictionCoordinator productCacheEvictionCoordinator,
                            PriceTrackerMetrics metrics,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.productMapper = productMapper;
        this.priceHistoryMapper = priceHistoryMapper;
        this.watchlistMapper = watchlistMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.priceProviderRouter = priceProviderRouter;
        this.cacheService = cacheService;
        this.productCacheEvictionCoordinator = productCacheEvictionCoordinator;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Value("${notification.idempotent.ttl-minutes:10}")
    private long notificationIdempotentTtlMinutes = 10;

    @Value("${price-tracker.price-refresh.batch-size:100}")
    private int priceRefreshBatchSize = DEFAULT_BATCH_SIZE;

    @Override
    public void refreshProductPrice(Long productId) {
        lastResolvedProvider.remove();
        try {
            try {
                executeRefreshAttempt(productId);
            } catch (RuntimeException exception) {
                safelyRecordPriceRefreshFinal(
                        PriceTrackerMetrics.RESULT_FAILED,
                        resolvedProviderCode());
                throw exception;
            }
            safelyRecordPriceRefreshFinal(
                    PriceTrackerMetrics.RESULT_SUCCESS,
                    resolvedProviderCode());
        } finally {
            lastResolvedProvider.remove();
        }
    }

    private int refreshProductPriceInternal(Long productId) {
        Product product = getActiveProductOrThrow(productId);
        BigDecimal expectedOldPrice = product.getCurrentPrice();
        BigDecimal oldPrice = expectedOldPrice == null ? DEFAULT_PRICE : expectedOldPrice;
        PriceProvider priceProvider = null;
        PriceQuote quote;
        long startNanos = System.nanoTime();
        try {
            priceProvider = priceProviderRouter.route(product);
            lastResolvedProvider.set(priceProvider.providerCode());
            quote = priceProvider.fetchPrice(product);
        } catch (PriceProviderException exception) {
            long durationNanos = System.nanoTime() - startNanos;
            String providerCode = priceProvider != null ? priceProvider.providerCode() : "unknown";
            if (priceProvider != null) {
                lastResolvedProvider.set(providerCode);
                safelyRecordPriceProviderFetch(
                        providerCode,
                        PriceTrackerMetrics.RESULT_FAILED,
                        Duration.ofNanos(durationNanos));
                safelyRecordPriceProviderFailure(
                        providerCode,
                        exception.getFailureType().name());
            }
            log.warn("price provider fetch failed, productId={}, providerCode={}, failureType={}, retryable={}, error={}",
                    productId, providerCode, exception.getFailureType(), exception.isRetryable(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            if (priceProvider != null) {
                long durationNanos = System.nanoTime() - startNanos;
                String providerCode = priceProvider.providerCode();
                lastResolvedProvider.set(providerCode);
                safelyRecordPriceProviderFetch(
                        providerCode,
                        PriceTrackerMetrics.RESULT_FAILED,
                        Duration.ofNanos(durationNanos));
            }
            throw exception;
        }

        safelyRecordPriceProviderFetch(
                priceProvider.providerCode(),
                PriceTrackerMetrics.RESULT_SUCCESS,
                Duration.ofNanos(System.nanoTime() - startNanos));

        BigDecimal newPrice = quote.price();
        LocalDateTime capturedAt = quote.capturedAt();
        boolean priceUnchanged = expectedOldPrice != null
                && newPrice.compareTo(expectedOldPrice) == 0;
        if (priceUnchanged) {
            int affectedRows = productMapper.updateRefreshMetadataIfPriceMatches(
                    productId,
                    ACTIVE_STATUS,
                    expectedOldPrice,
                    quote.currency(),
                    capturedAt);
            if (affectedRows == 0 && isLegalMetadataNoOp(
                    productId, expectedOldPrice, quote.currency(), capturedAt)) {
                return 0;
            }
            requireSingleProductUpdate(affectedRows, productId, expectedOldPrice);
            productCacheEvictionCoordinator.registerProductCacheEvictionAfterCommit(productId);
            return 0;
        }

        int affectedRows = productMapper.updateRefreshPriceIfPriceMatches(
                productId,
                ACTIVE_STATUS,
                expectedOldPrice,
                newPrice,
                quote.currency(),
                capturedAt,
                capturedAt);
        requireSingleProductUpdate(affectedRows, productId, expectedOldPrice);
        productCacheEvictionCoordinator.registerProductCacheEvictionAfterCommit(productId);
        product.setCurrentPrice(newPrice);
        product.setCurrency(quote.currency());
        product.setLastCheckedAt(capturedAt);
        product.setUpdatedAt(capturedAt);
        int historyInserted = priceHistoryMapper.insert(PriceHistory.builder()
                .productId(product.getId())
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .capturedAt(capturedAt)
                .source(quote.source())
                .build());
        if (historyInserted != 1) {
            throw new BusinessException(
                    ResultCode.SYSTEM_ERROR,
                    "price history insert affected unexpected rows, productId=" + productId
                            + ", affectedRows=" + historyInserted);
        }
        int notificationTriggeredCount = 0;
        List<Watchlist> watchlists = watchlistMapper.selectList(new LambdaQueryWrapper<Watchlist>()
                .eq(Watchlist::getProductId, productId)
                .eq(Watchlist::getStatus, ACTIVE_STATUS)
                .eq(Watchlist::getNotifyEnabled, NOTIFY_ENABLED));
        for (Watchlist watchlist : watchlists) {
            if (shouldNotify(watchlist, newPrice)) {
                if (sendAlertIfNotDuplicate(product, watchlist, newPrice, capturedAt)) {
                    notificationTriggeredCount++;
                }
            }
        }
        return notificationTriggeredCount;
    }

    private int executeRefreshAttempt(Long productId) {
        Integer notificationCount;
        try {
            notificationCount = transactionTemplate.execute(
                    status -> refreshProductPriceInternal(productId));
        } catch (RuntimeException exception) {
            safelyRecordPriceRefreshAttempt(
                    PriceTrackerMetrics.RESULT_FAILED,
                    resolvedProviderCode());
            throw exception;
        }
        safelyRecordPriceRefreshAttempt(
                PriceTrackerMetrics.RESULT_SUCCESS,
                resolvedProviderCode());
        return notificationCount != null ? notificationCount : 0;
    }

    @Override
    public void refreshActiveProducts() {
        long startAt = System.currentTimeMillis();
        int batchSize = resolveBatchSize();
        long pageNum = 1L;
        long scannedCount = 0L;
        long successCount = 0L;
        long failedCount = 0L;
        long notificationTriggeredCount = 0L;
        log.info("price refresh task start, batchSize={}", batchSize);

        while (true) {
            Page<Product> pageRequest = new Page<>(pageNum, batchSize);
            Page<Product> page = productMapper.selectPage(pageRequest, new LambdaQueryWrapper<Product>()
                    .eq(Product::getStatus, ACTIVE_STATUS)
                    .orderByAsc(Product::getId));
            List<Product> products = page.getRecords();
            if (products == null || products.isEmpty()) {
                break;
            }

            log.info("price refresh batch start, pageNum={}, batchSize={}, batchCount={}",
                    pageNum, batchSize, products.size());
            for (Product product : products) {
                scannedCount++;
                try {
                    notificationTriggeredCount += refreshProductWithRetry(product.getId());
                    successCount++;
                } catch (Exception exception) {
                    failedCount++;
                    log.warn("price refresh product failed, productId={}, retries={}, message={}",
                            product.getId(), MAX_REFRESH_RETRIES, exception.getMessage());
                }
            }

            if (pageNum >= page.getPages()) {
                break;
            }
            pageNum++;
        }

        log.info("price refresh task finished, scanned count={}, success count={}, failed count={}, notification triggered count={}, total cost={}ms",
                scannedCount, successCount, failedCount, notificationTriggeredCount, System.currentTimeMillis() - startAt);
    }

    private int refreshProductWithRetry(Long productId) {
        RuntimeException lastException = null;
        lastResolvedProvider.remove();
        try {
            for (int attempt = 0; attempt <= MAX_REFRESH_RETRIES; attempt++) {
                try {
                    int notificationCount = executeRefreshAttempt(productId);
                    safelyRecordPriceRefreshFinal(
                            PriceTrackerMetrics.RESULT_SUCCESS,
                            resolvedProviderCode());
                    return notificationCount;
                } catch (ProductRefreshUnavailableException exception) {
                    log.warn("price refresh stopped because product is unavailable, productId={}, attempt={}, message={}",
                            productId, attempt + 1, exception.getMessage());
                    safelyRecordPriceRefreshFinal(
                            PriceTrackerMetrics.RESULT_FAILED,
                            resolvedProviderCode());
                    throw exception;
                } catch (PriceRefreshConflictException exception) {
                    lastException = exception;
                    boolean willRetry = attempt < MAX_REFRESH_RETRIES;
                    if (willRetry) {
                        log.warn("price refresh retrying after concurrent price change, productId={}, attempt={}, maxRetries={}, message={}",
                                productId, attempt + 1, MAX_REFRESH_RETRIES, exception.getMessage());
                    } else {
                        log.warn("price refresh retries exhausted after concurrent price change, productId={}, attempt={}, maxRetries={}, message={}",
                                productId, attempt + 1, MAX_REFRESH_RETRIES, exception.getMessage());
                    }
                } catch (PriceProviderException exception) {
                    lastException = exception;
                    log.warn("price refresh attempt failed due to provider error, productId={}, attempt={}, maxRetries={}, failureType={}, retryable={}, message={}",
                            productId, attempt + 1, MAX_REFRESH_RETRIES, exception.getFailureType(), exception.isRetryable(), exception.getMessage());
                    if (!exception.isRetryable()) {
                        safelyRecordPriceRefreshFinal(
                                PriceTrackerMetrics.RESULT_FAILED,
                                resolvedProviderCode());
                        throw exception;
                    }
                } catch (RuntimeException exception) {
                    lastException = exception;
                    log.warn("price refresh attempt failed, productId={}, attempt={}, maxRetries={}, message={}",
                            productId, attempt + 1, MAX_REFRESH_RETRIES, exception.getMessage());
                }
            }
            safelyRecordPriceRefreshFinal(
                    PriceTrackerMetrics.RESULT_FAILED,
                    resolvedProviderCode());
            throw lastException;
        } finally {
            lastResolvedProvider.remove();
        }
    }

    private String resolvedProviderCode() {
        String providerCode = lastResolvedProvider.get();
        return providerCode != null ? providerCode : "unknown";
    }

    private void safelyRecordPriceRefreshFinal(String result, String providerCode) {
        safelyRecordMetric(
                "price refresh final",
                result,
                providerCode,
                () -> metrics.recordPriceRefreshFinal(result, providerCode));
    }

    private void safelyRecordPriceRefreshAttempt(String result, String providerCode) {
        safelyRecordMetric(
                "price refresh attempt",
                result,
                providerCode,
                () -> metrics.recordPriceRefreshAttempt(result, providerCode));
    }

    private void safelyRecordPriceProviderFetch(String providerCode,
                                                String result,
                                                Duration duration) {
        safelyRecordMetric(
                "price provider fetch",
                result,
                providerCode,
                () -> metrics.recordPriceProviderFetch(providerCode, result, duration));
    }

    private void safelyRecordPriceProviderFailure(String providerCode, String failureType) {
        safelyRecordMetric(
                "price provider failure",
                failureType,
                providerCode,
                () -> metrics.recordPriceProviderFailure(providerCode, failureType));
    }

    private void safelyRecordMetric(String metricName,
                                    String result,
                                    String providerCode,
                                    Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            safelyLogMetricFailure(metricName, result, providerCode, exception);
        }
    }

    private void safelyLogMetricFailure(String metricName,
                                        String result,
                                        String providerCode,
                                        RuntimeException metricException) {
        try {
            log.warn(
                    "failed to record metric, metricName={}, result={}, providerCode={}",
                    metricName,
                    result,
                    providerCode,
                    metricException);
        } catch (RuntimeException ignored) {
            // Observability failures must not affect price refresh business control flow.
        }
    }

    private int resolveBatchSize() {
        return priceRefreshBatchSize > 0 ? priceRefreshBatchSize : DEFAULT_BATCH_SIZE;
    }

    private boolean shouldNotify(Watchlist watchlist, BigDecimal newPrice) {
        return watchlist.getTargetPrice() != null && newPrice.compareTo(watchlist.getTargetPrice()) <= 0;
    }

    private PriceAlertMessage buildPriceAlertMessage(Product product, Watchlist watchlist, BigDecimal newPrice, LocalDateTime now) {
        String eventKey = PriceAlertEventKeyBuilder.buildTargetPriceReachedKey(
                watchlist.getUserId(),
                product.getId(),
                watchlist.getId(),
                watchlist.getTargetPrice(),
                newPrice,
                now);
        return PriceAlertMessage.builder()
                .messageId(eventKey)
                .eventKey(eventKey)
                .userId(watchlist.getUserId())
                .productId(product.getId())
                .watchlistId(watchlist.getId())
                .currentPrice(newPrice)
                .targetPrice(watchlist.getTargetPrice())
                .productName(product.getProductName())
                .triggeredAt(now)
                .build();
    }

    private boolean sendAlertIfNotDuplicate(Product product, Watchlist watchlist, BigDecimal newPrice, LocalDateTime now) {
        String idempotentKey = RedisKeyManager.notificationIdempotentKey(
                watchlist.getUserId() + ":" + product.getId() + ":" + watchlist.getTargetPrice());
        boolean acquired = cacheService.setIfAbsent(
                idempotentKey,
                "1",
                Duration.ofMinutes(notificationIdempotentTtlMinutes));
        if (!acquired) {
            log.info("notification idempotent hit, key={}", idempotentKey);
            return false;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        log.info("Transaction rolled back, deleting redis idempotent key={}", idempotentKey);
                        cacheService.delete(idempotentKey);
                    }
                }
            });
        } else {
            log.debug("No transaction active when acquiring idempotent key={}", idempotentKey);
        }

        PriceAlertMessage message = buildPriceAlertMessage(product, watchlist, newPrice, now);
        String payload = serializeMessage(message);
        OutboxEvent event = OutboxEvent.builder()
                .eventKey(message.getEventKey())
                .eventType(PRICE_ALERT_EVENT_TYPE)
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attempts(0)
                .nextRetryAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            int inserted = outboxEventMapper.insertEvent(event);
            if (inserted != 1) {
                throw new BusinessException(
                        ResultCode.SYSTEM_ERROR,
                        "outbox event insert affected unexpected rows, eventKey=" + message.getEventKey()
                                + ", affectedRows=" + inserted);
            }
            log.info("created outbox event for price alert, eventKey={}, productId={}, userId={}, watchlistId={}",
                    message.getEventKey(), message.getProductId(), message.getUserId(), message.getWatchlistId());
            return true;
        } catch (DuplicateKeyException exception) {
            log.info("outbox event unique conflict, eventKey={}, decision=idempotent_skip", message.getEventKey());
            return false;
        }
    }

    private String serializeMessage(PriceAlertMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ResultCode.SYSTEM_ERROR,
                    "failed to serialize price alert outbox payload",
                    exception);
        }
    }

    private Product getActiveProductOrThrow(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != ACTIVE_STATUS) {
            throw new ProductRefreshUnavailableException(productId);
        }
        return product;
    }

    private boolean isLegalMetadataNoOp(Long productId,
                                        BigDecimal expectedOldPrice,
                                        String currency,
                                        LocalDateTime lastCheckedAt) {
        Product currentState = selectCurrentRefreshStateOrThrow(productId, expectedOldPrice);
        return Objects.equals(currentState.getCurrency(), currency)
                && Objects.equals(currentState.getLastCheckedAt(), lastCheckedAt);
    }

    private void requireSingleProductUpdate(int affectedRows,
                                            Long productId,
                                            BigDecimal expectedOldPrice) {
        if (affectedRows == 1) {
            return;
        }
        if (affectedRows == 0) {
            selectCurrentRefreshStateOrThrow(productId, expectedOldPrice);
        }
        throw new BusinessException(
                ResultCode.SYSTEM_ERROR,
                "product refresh update affected unexpected rows, productId=" + productId
                        + ", affectedRows=" + affectedRows);
    }

    private Product selectCurrentRefreshStateOrThrow(Long productId, BigDecimal expectedOldPrice) {
        Product currentState = productMapper.selectRefreshStateForUpdate(productId);
        if (currentState == null
                || currentState.getStatus() == null
                || currentState.getStatus() != ACTIVE_STATUS) {
            throw new ProductRefreshUnavailableException(productId);
        }
        if (!pricesEqual(currentState.getCurrentPrice(), expectedOldPrice)) {
            throw new PriceRefreshConflictException(
                    productId,
                    expectedOldPrice,
                    currentState.getCurrentPrice());
        }
        return currentState;
    }

    private boolean pricesEqual(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
