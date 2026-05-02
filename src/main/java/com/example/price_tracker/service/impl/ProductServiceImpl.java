package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.dto.ProductAddDto;
import com.example.price_tracker.dto.ProductUpdateDto;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisDistributedLock;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.ProductService;
import com.example.price_tracker.vo.ProductDetailVo;
import com.example.price_tracker.vo.ProductPageVo;
import com.example.price_tracker.vo.ProductPriceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int ACTIVE_STATUS = 1;
    private static final int DELETED_STATUS = 0;
    private static final String NULL_CACHE_VALUE = "NULL";
    private static final Duration CACHE_BASE_TTL = Duration.ofMinutes(30);
    private static final Duration CACHE_TTL_JITTER = Duration.ofMinutes(5);
    private static final Duration NULL_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration LOCK_RETRY_WAIT = Duration.ofMillis(50);

    private final ProductMapper productMapper;
    private final RedisCacheService cacheService;
    private final RedisDistributedLock distributedLock;

    @Override
    public Long addProduct(ProductAddDto productAddDto) {
        LocalDateTime now = LocalDateTime.now();
        Product product = new Product();
        product.setProductName(productAddDto.getProductName());
        product.setProductUrl(productAddDto.getProductUrl());
        product.setPlatform(productAddDto.getPlatform());
        product.setCurrentPrice(productAddDto.getCurrentPrice());
        product.setCurrency(productAddDto.getCurrency());
        product.setImageUrl(productAddDto.getImageUrl());
        product.setStatus(ACTIVE_STATUS);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return product.getId();
    }

    @Override
    public ProductDetailVo getProductDetail(Long id) {
        String cacheKey = RedisKeyManager.productDetailKey(id);
        String nullCacheKey = RedisKeyManager.nullValueKey("product:detail:" + id);
        ProductDetailVo cached = cacheService.get(cacheKey, ProductDetailVo.class);
        if (cached != null) {
            log.info("cache hit, key={}", cacheKey);
            return cached;
        }
        log.info("cache miss, key={}", cacheKey);
        if (isNullCacheHit(nullCacheKey)) {
            throwProductNotFound();
        }
        return loadDetailWithLock(id, cacheKey, nullCacheKey);
    }

    @Override
    public ProductPriceVo getCurrentPrice(Long id) {
        String cacheKey = RedisKeyManager.productPriceKey(id);
        String nullCacheKey = RedisKeyManager.nullValueKey("product:price:" + id);
        ProductPriceVo cached = cacheService.get(cacheKey, ProductPriceVo.class);
        if (cached != null) {
            log.info("cache hit, key={}", cacheKey);
            return cached;
        }
        log.info("cache miss, key={}", cacheKey);
        if (isNullCacheHit(nullCacheKey)) {
            throwProductNotFound();
        }
        return loadPriceWithLock(id, cacheKey, nullCacheKey);
    }

    @Override
    public PageResult<ProductPageVo> pageProducts(Long pageNum, Long pageSize, String keyword) {
        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ACTIVE_STATUS)
                .orderByDesc(Product::getUpdatedAt);
        if (StringUtils.isNotBlank(keyword)) {
            queryWrapper.and(wrapper -> wrapper
                    .like(Product::getProductName, keyword)
                    .or()
                    .like(Product::getPlatform, keyword));
        }

        Page<Product> page = productMapper.selectPage(new Page<>(pageNum, pageSize), queryWrapper);
        List<ProductPageVo> records = page.getRecords().stream()
                .map(this::toProductPageVo)
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public void updateProduct(Long id, ProductUpdateDto productUpdateDto) {
        Product existing = getActiveProductOrThrow(id);
        existing.setProductName(productUpdateDto.getProductName());
        existing.setProductUrl(productUpdateDto.getProductUrl());
        existing.setPlatform(productUpdateDto.getPlatform());
        existing.setCurrentPrice(productUpdateDto.getCurrentPrice());
        existing.setCurrency(productUpdateDto.getCurrency());
        existing.setImageUrl(productUpdateDto.getImageUrl());
        existing.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(existing);
        clearProductCache(id);
    }

    @Override
    public void deleteProduct(Long id) {
        Product existing = getActiveProductOrThrow(id);
        existing.setStatus(DELETED_STATUS);
        existing.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(existing);
        clearProductCache(id);
    }

    private ProductDetailVo loadDetailWithLock(Long id, String cacheKey, String nullCacheKey) {
        String lockKey = RedisKeyManager.lockKey("product:detail:" + id);
        String lockOwner = "product-detail-" + UUID.randomUUID();
        if (!distributedLock.tryLock(lockKey, lockOwner, LOCK_TTL)) {
            log.info("lock failed, key={}", lockKey);
            waitBriefly();
            ProductDetailVo cached = cacheService.get(cacheKey, ProductDetailVo.class);
            if (cached != null) {
                log.info("cache hit, key={}", cacheKey);
                return cached;
            }
            if (isNullCacheHit(nullCacheKey)) {
                throwProductNotFound();
            }
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "product detail cache is rebuilding");
        }

        log.info("lock acquired, key={}", lockKey);
        try {
            ProductDetailVo cached = cacheService.get(cacheKey, ProductDetailVo.class);
            if (cached != null) {
                log.info("cache hit, key={}", cacheKey);
                return cached;
            }
            Product product = productMapper.selectById(id);
            log.info("db fallback, productId={}", id);
            if (!isActiveProduct(product)) {
                cacheService.set(nullCacheKey, NULL_CACHE_VALUE, NULL_CACHE_TTL);
                throwProductNotFound();
            }
            ProductDetailVo detailVo = toProductDetailVo(product);
            cacheService.set(cacheKey, detailVo, cacheService.randomTtl(CACHE_BASE_TTL, CACHE_TTL_JITTER));
            return detailVo;
        } finally {
            distributedLock.unlock(lockKey, lockOwner);
        }
    }

    private ProductPriceVo loadPriceWithLock(Long id, String cacheKey, String nullCacheKey) {
        String lockKey = RedisKeyManager.lockKey("product:price:" + id);
        String lockOwner = "product-price-" + UUID.randomUUID();
        if (!distributedLock.tryLock(lockKey, lockOwner, LOCK_TTL)) {
            log.info("lock failed, key={}", lockKey);
            waitBriefly();
            ProductPriceVo cached = cacheService.get(cacheKey, ProductPriceVo.class);
            if (cached != null) {
                log.info("cache hit, key={}", cacheKey);
                return cached;
            }
            if (isNullCacheHit(nullCacheKey)) {
                throwProductNotFound();
            }
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "product price cache is rebuilding");
        }

        log.info("lock acquired, key={}", lockKey);
        try {
            ProductPriceVo cached = cacheService.get(cacheKey, ProductPriceVo.class);
            if (cached != null) {
                log.info("cache hit, key={}", cacheKey);
                return cached;
            }
            Product product = productMapper.selectById(id);
            log.info("db fallback, productId={}", id);
            if (!isActiveProduct(product)) {
                cacheService.set(nullCacheKey, NULL_CACHE_VALUE, NULL_CACHE_TTL);
                throwProductNotFound();
            }
            ProductPriceVo priceVo = toProductPriceVo(product);
            cacheService.set(cacheKey, priceVo, cacheService.randomTtl(CACHE_BASE_TTL, CACHE_TTL_JITTER));
            return priceVo;
        } finally {
            distributedLock.unlock(lockKey, lockOwner);
        }
    }

    private Product getActiveProductOrThrow(Long id) {
        Product product = productMapper.selectById(id);
        if (!isActiveProduct(product)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
        }
        return product;
    }

    private boolean isActiveProduct(Product product) {
        return product != null && isActiveStatus(product.getStatus());
    }

    private boolean isActiveStatus(Integer status) {
        return status != null && status == ACTIVE_STATUS;
    }

    private boolean isNullCacheHit(String nullCacheKey) {
        boolean hit = NULL_CACHE_VALUE.equals(cacheService.get(nullCacheKey, String.class));
        if (hit) {
            log.info("null cache hit, key={}", nullCacheKey);
        }
        return hit;
    }

    private void throwProductNotFound() {
        throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
    }

    private void waitBriefly() {
        try {
            Thread.sleep(LOCK_RETRY_WAIT.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "cache retry interrupted");
        }
    }

    private void clearProductCache(Long id) {
        cacheService.delete(RedisKeyManager.productDetailKey(id));
        cacheService.delete(RedisKeyManager.productPriceKey(id));
        cacheService.delete(RedisKeyManager.nullValueKey("product:detail:" + id));
        cacheService.delete(RedisKeyManager.nullValueKey("product:price:" + id));
    }

    private ProductDetailVo toProductDetailVo(Product product) {
        return ProductDetailVo.builder()
                .id(product.getId())
                .productName(product.getProductName())
                .productUrl(product.getProductUrl())
                .platform(product.getPlatform())
                .currentPrice(product.getCurrentPrice())
                .currency(product.getCurrency())
                .imageUrl(product.getImageUrl())
                .lastCheckedAt(product.getLastCheckedAt())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private ProductPageVo toProductPageVo(Product product) {
        return ProductPageVo.builder()
                .id(product.getId())
                .productName(product.getProductName())
                .productUrl(product.getProductUrl())
                .platform(product.getPlatform())
                .currentPrice(product.getCurrentPrice())
                .currency(product.getCurrency())
                .imageUrl(product.getImageUrl())
                .lastCheckedAt(product.getLastCheckedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private ProductPriceVo toProductPriceVo(Product product) {
        return ProductPriceVo.builder()
                .productId(product.getId())
                .currentPrice(product.getCurrentPrice())
                .currency(product.getCurrency())
                .lastCheckedAt(product.getLastCheckedAt())
                .build();
    }
}
