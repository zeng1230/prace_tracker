package com.example.price_tracker.redis;

import com.example.price_tracker.metrics.PriceTrackerMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

@Component
@Slf4j
public class ProductCacheEvictionCoordinator {

    private final RedisCacheService cacheService;
    private final PriceTrackerMetrics metrics;

    public ProductCacheEvictionCoordinator(RedisCacheService cacheService, PriceTrackerMetrics metrics) {
        this.cacheService = cacheService;
        this.metrics = metrics;
    }

    public void registerProductCacheEvictionAfterCommit(Long productId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.debug("No active transaction for product cache eviction, evicting immediately, productId={}", productId);
            clearProductCacheImmediately(productId);
            return;
        }

        ProductCacheEvictionSynchronization currentSynchronization = findCurrentSynchronization();
        if (currentSynchronization != null) {
            currentSynchronization.addProductId(productId);
            return;
        }

        ProductCacheEvictionSynchronization newSynchronization =
                new ProductCacheEvictionSynchronization();
        newSynchronization.addProductId(productId);
        try {
            TransactionSynchronizationManager.registerSynchronization(newSynchronization);
        } catch (Exception exception) {
            newSynchronization.clearProductIds();
            throw new IllegalStateException(
                    "failed to register product cache eviction after commit, productId=" + productId,
                    exception);
        }
    }

    public void clearProductCacheImmediately(Long productId) {
        evictProductCache(productId);
    }

    private ProductCacheEvictionSynchronization findCurrentSynchronization() {
        return TransactionSynchronizationManager.getSynchronizations()
                .stream()
                .filter(ProductCacheEvictionSynchronization.class::isInstance)
                .map(ProductCacheEvictionSynchronization.class::cast)
                .findFirst()
                .orElse(null);
    }

    private void evictRegisteredProductsAfterCommit(Set<Long> productIds) {
        ArrayList<Long> productIdSnapshot;
        try {
            productIdSnapshot = new ArrayList<>(productIds);
        } catch (Exception exception) {
            safeLogUnexpectedAfterCommitFailure(null, exception);
            return;
        }

        for (Long productId : productIdSnapshot) {
            try {
                evictProductCache(productId);
            } catch (Exception exception) {
                safeLogUnexpectedAfterCommitFailure(productId, exception);
            }
        }
    }

    private void evictProductCache(Long productId) {
        evictKey(productId, () -> RedisKeyManager.productDetailKey(productId));
        evictKey(productId, () -> RedisKeyManager.productPriceKey(productId));
        evictKey(productId, () -> RedisKeyManager.nullValueKey("product:detail:" + productId));
        evictKey(productId, () -> RedisKeyManager.nullValueKey("product:price:" + productId));
    }

    private void evictKey(Long productId, Supplier<String> keySupplier) {
        String key;
        try {
            key = keySupplier.get();
        } catch (Exception exception) {
            safeLogCacheFailure("failed to build product cache key", productId, null, exception);
            safeRecordCacheEviction(PriceTrackerMetrics.RESULT_FAILED, productId, null);
            return;
        }

        try {
            cacheService.delete(key);
            safeRecordCacheEviction(PriceTrackerMetrics.RESULT_SUCCESS, productId, key);
        } catch (Exception exception) {
            safeLogCacheFailure("failed to evict product cache", productId, key, exception);
            safeRecordCacheEviction(PriceTrackerMetrics.RESULT_FAILED, productId, key);
        }
    }

    private void safeRecordCacheEviction(String result, Long productId, String key) {
        try {
            metrics.recordProductCacheEviction(result);
        } catch (Exception exception) {
            safeLogCacheFailure("failed to record product cache eviction metric", productId, key, exception);
        }
    }

    private void clearRegisteredProductIds(Set<Long> productIds) {
        try {
            productIds.clear();
        } catch (Exception exception) {
            safeLogCleanupFailure("failed to clear registered product cache eviction ids", exception);
        }
    }

    private void safeLogUnexpectedAfterCommitFailure(Long productId, Exception exception) {
        try {
            log.error(
                    "unexpected product cache eviction afterCommit failure, productId={}",
                    productId,
                    exception);
        } catch (Exception ignored) {
            // Logging must not make a committed database transaction appear to fail.
        }
    }

    private void safeLogCacheFailure(String message, Long productId, String key, Exception exception) {
        try {
            log.error("{}, productId={}, key={}", message, productId, key, exception);
        } catch (Exception ignored) {
            // Logging must not prevent the remaining cache keys from being evicted.
        }
    }

    private void safeLogCleanupFailure(String message, Exception exception) {
        try {
            log.error(message, exception);
        } catch (Exception ignored) {
            // Synchronization state cleanup is best effort and must not leak logging failures.
        }
    }

    private final class ProductCacheEvictionSynchronization implements TransactionSynchronization {

        private final Set<Long> productIds = new LinkedHashSet<>();

        private void addProductId(Long productId) {
            productIds.add(productId);
        }

        @Override
        public void afterCommit() {
            evictRegisteredProductsAfterCommit(productIds);
        }

        @Override
        public void afterCompletion(int status) {
            clearProductIds();
        }

        private void clearProductIds() {
            clearRegisteredProductIds(productIds);
        }
    }
}
