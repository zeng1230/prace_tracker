package com.example.price_tracker.redis;

import com.example.price_tracker.metrics.PriceTrackerMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCacheEvictionCoordinatorTest {

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private PriceTrackerMetrics metrics;

    private ProductCacheEvictionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ProductCacheEvictionCoordinator(cacheService, metrics);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void evictsFourKeysOnceAfterCommitAndDeduplicatesRegistration() {
        beginTransactionSynchronization();

        coordinator.registerProductCacheEvictionAfterCommit(7L);
        coordinator.registerProductCacheEvictionAfterCommit(7L);

        verify(cacheService, never()).delete(anyString());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verifyProductKeysDeletedOnce(7L);
    }

    @Test
    void rollbackDoesNotEvict() {
        beginTransactionSynchronization();
        coordinator.registerProductCacheEvictionAfterCommit(8L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cacheService, never()).delete(anyString());
    }

    @Test
    void noTransactionEvictsImmediately() {
        coordinator.registerProductCacheEvictionAfterCommit(9L);

        verifyProductKeysDeletedOnce(9L);
    }

    @Test
    void redisAndMetricFailuresDoNotStopRemainingKeysOrEscapeAfterCommit() {
        beginTransactionSynchronization();
        when(cacheService.delete(RedisKeyManager.productDetailKey(10L)))
                .thenThrow(new RuntimeException("redis unavailable"));
        doThrow(new RuntimeException("metrics unavailable"))
                .when(metrics).recordProductCacheEviction(anyString());
        coordinator.registerProductCacheEvictionAfterCommit(10L);
        coordinator.registerProductCacheEvictionAfterCommit(11L);

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(cacheService).delete(RedisKeyManager.productDetailKey(10L));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(10L));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:detail:10"));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:price:10"));
        verifyProductKeysDeletedOnce(11L);
        verify(metrics, times(8)).recordProductCacheEviction(anyString());
    }

    @Test
    void sameTransactionUsesOneSynchronizationForDifferentProducts() {
        beginTransactionSynchronization();

        coordinator.registerProductCacheEvictionAfterCommit(13L);
        coordinator.registerProductCacheEvictionAfterCommit(14L);

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync ->
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verifyProductKeysDeletedOnce(13L);
        verifyProductKeysDeletedOnce(14L);
    }

    @Test
    void completedTransactionDoesNotLeakRegisteredProductsIntoNextTransaction() {
        beginTransactionSynchronization();
        coordinator.registerProductCacheEvictionAfterCommit(11L);
        completeCommittedTransaction();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        beginTransactionSynchronization();
        coordinator.registerProductCacheEvictionAfterCommit(12L);
        completeCommittedTransaction();

        verifyProductKeysDeletedOnce(11L);
        verifyProductKeysDeletedOnce(12L);
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void completeCommittedTransaction() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void verifyProductKeysDeletedOnce(Long productId) {
        verify(cacheService).delete(RedisKeyManager.productDetailKey(productId));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(productId));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:detail:" + productId));
        verify(cacheService).delete(RedisKeyManager.nullValueKey("product:price:" + productId));
    }
}
