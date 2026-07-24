package com.example.price_tracker.redis;

import com.example.price_tracker.metrics.PriceTrackerMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.verification.VerificationMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class ProductCacheEvictionTransactionIT {

    private static final long OUTER_PRODUCT_ID = 101L;
    private static final long INNER_PRODUCT_ID = 202L;

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
    private ProductCacheEvictionCoordinator coordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private RedisCacheService cacheService;

    @MockBean
    private PriceTrackerMetrics metrics;

    private TransactionTemplate outerTransaction;
    private TransactionTemplate requiresNewTransaction;

    @BeforeEach
    void setUp() {
        clearInvocations(cacheService, metrics);
        outerTransaction = new TransactionTemplate(transactionManager);
        requiresNewTransaction = new TransactionTemplate(transactionManager);
        requiresNewTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void innerCommitEvictsInnerProductBeforeOuterRollback() {
        outerTransaction.executeWithoutResult(outerStatus -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);

            requiresNewTransaction.executeWithoutResult(innerStatus ->
                    coordinator.registerProductCacheEvictionAfterCommit(INNER_PRODUCT_ID));

            verifyProductKeys(INNER_PRODUCT_ID, times(1));
            verifyProductKeys(OUTER_PRODUCT_ID, never());
            outerStatus.setRollbackOnly();
        });

        verifyProductKeys(INNER_PRODUCT_ID, times(1));
        verifyProductKeys(OUTER_PRODUCT_ID, never());
    }

    @Test
    void innerAndOuterCommitEvictAtTheirOwnCommitBoundaries() {
        outerTransaction.executeWithoutResult(outerStatus -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);

            requiresNewTransaction.executeWithoutResult(innerStatus ->
                    coordinator.registerProductCacheEvictionAfterCommit(INNER_PRODUCT_ID));

            verifyProductKeys(INNER_PRODUCT_ID, times(1));
            verifyProductKeys(OUTER_PRODUCT_ID, never());
        });

        verifyProductKeys(INNER_PRODUCT_ID, times(1));
        verifyProductKeys(OUTER_PRODUCT_ID, times(1));
    }

    @Test
    void innerRollbackDoesNotEvictInnerProductAndOuterCommitEvictsOuterProduct() {
        outerTransaction.executeWithoutResult(outerStatus -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);

            requiresNewTransaction.executeWithoutResult(innerStatus -> {
                coordinator.registerProductCacheEvictionAfterCommit(INNER_PRODUCT_ID);
                innerStatus.setRollbackOnly();
            });

            verifyProductKeys(INNER_PRODUCT_ID, never());
            verifyProductKeys(OUTER_PRODUCT_ID, never());
        });

        verifyProductKeys(INNER_PRODUCT_ID, never());
        verifyProductKeys(OUTER_PRODUCT_ID, times(1));
    }

    @Test
    void repeatedRegistrationInOneTransactionEvictsEachKeyOnce() {
        outerTransaction.executeWithoutResult(status -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);

            verifyProductKeys(OUTER_PRODUCT_ID, never());
        });

        verifyProductKeys(OUTER_PRODUCT_ID, times(1));
    }

    @Test
    void sameProductInOuterAndInnerTransactionsEvictsOncePerCommit() {
        outerTransaction.executeWithoutResult(outerStatus -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);

            requiresNewTransaction.executeWithoutResult(innerStatus ->
                    coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID));

            verifyProductKeys(OUTER_PRODUCT_ID, times(1));
        });

        verifyProductKeys(OUTER_PRODUCT_ID, times(2));
    }

    @Test
    void oneProductCacheFailureDoesNotBlockFollowingProduct() {
        when(cacheService.delete(RedisKeyManager.productDetailKey(OUTER_PRODUCT_ID)))
                .thenThrow(new RuntimeException("redis unavailable"));

        outerTransaction.executeWithoutResult(status -> {
            coordinator.registerProductCacheEvictionAfterCommit(OUTER_PRODUCT_ID);
            coordinator.registerProductCacheEvictionAfterCommit(INNER_PRODUCT_ID);

            verifyProductKeys(OUTER_PRODUCT_ID, never());
            verifyProductKeys(INNER_PRODUCT_ID, never());
        });

        verifyProductKeys(OUTER_PRODUCT_ID, times(1));
        verifyProductKeys(INNER_PRODUCT_ID, times(1));
    }

    private void verifyProductKeys(Long productId, VerificationMode verificationMode) {
        verify(cacheService, verificationMode).delete(RedisKeyManager.productDetailKey(productId));
        verify(cacheService, verificationMode).delete(RedisKeyManager.productPriceKey(productId));
        verify(cacheService, verificationMode).delete(
                RedisKeyManager.nullValueKey("product:detail:" + productId));
        verify(cacheService, verificationMode).delete(
                RedisKeyManager.nullValueKey("product:price:" + productId));
    }
}
