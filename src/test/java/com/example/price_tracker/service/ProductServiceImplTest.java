package com.example.price_tracker.service;

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
import com.example.price_tracker.service.impl.ProductServiceImpl;
import com.example.price_tracker.vo.ProductDetailVo;
import com.example.price_tracker.vo.ProductPageVo;
import com.example.price_tracker.vo.ProductPriceVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private RedisDistributedLock distributedLock;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper, cacheService, distributedLock);
    }

    @Test
    void shouldReturnCachedProductDetailBeforeDatabaseLookup() {
        ProductDetailVo cached = ProductDetailVo.builder()
                .id(1L)
                .productName("Kindle")
                .platform("amazon")
                .build();
        when(cacheService.get(RedisKeyManager.productDetailKey(1L), ProductDetailVo.class)).thenReturn(cached);

        ProductDetailVo result = productService.getProductDetail(1L);

        assertEquals("Kindle", result.getProductName());
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void shouldLoadProductDetailFromDatabaseAndCacheWhenRedisMisses() {
        Product product = buildProduct(2L);
        when(distributedLock.tryLock(eq(RedisKeyManager.lockKey("product:detail:2")), startsWith("product-detail-"), any(Duration.class))).thenReturn(true);
        when(productMapper.selectById(2L)).thenReturn(product);

        ProductDetailVo result = productService.getProductDetail(2L);

        assertEquals(2L, result.getId());
        assertEquals("USD", result.getCurrency());
        verify(cacheService).set(eq(RedisKeyManager.productDetailKey(2L)), any(ProductDetailVo.class), any(Duration.class));
        verify(distributedLock).unlock(eq(RedisKeyManager.lockKey("product:detail:2")), startsWith("product-detail-"));
    }

    @Test
    void shouldCacheNullWhenProductDetailDoesNotExist() {
        when(distributedLock.tryLock(eq(RedisKeyManager.lockKey("product:detail:99")), startsWith("product-detail-"), any(Duration.class))).thenReturn(true);
        when(productMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productService.getProductDetail(99L));

        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
        verify(cacheService).set(eq(RedisKeyManager.nullValueKey("product:detail:99")), eq("NULL"), any(Duration.class));
    }

    @Test
    void shouldNotQueryDatabaseWhenNullProductDetailCacheHits() {
        when(cacheService.get(RedisKeyManager.productDetailKey(99L), ProductDetailVo.class)).thenReturn(null);
        when(cacheService.get(RedisKeyManager.nullValueKey("product:detail:99"), String.class)).thenReturn("NULL");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productService.getProductDetail(99L));

        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void shouldReturnCachedCurrentPriceBeforeDatabaseLookup() {
        ProductPriceVo cached = ProductPriceVo.builder()
                .productId(1L)
                .currentPrice(new BigDecimal("88.00"))
                .currency("USD")
                .build();
        when(cacheService.get(RedisKeyManager.productPriceKey(1L), ProductPriceVo.class)).thenReturn(cached);

        ProductPriceVo result = productService.getCurrentPrice(1L);

        assertEquals(new BigDecimal("88.00"), result.getCurrentPrice());
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void shouldCacheCurrentPriceAfterDatabaseFallback() {
        Product product = buildProduct(7L);
        when(distributedLock.tryLock(eq(RedisKeyManager.lockKey("product:price:7")), startsWith("product-price-"), any(Duration.class))).thenReturn(true);
        when(productMapper.selectById(7L)).thenReturn(product);

        ProductPriceVo result = productService.getCurrentPrice(7L);

        assertEquals(product.getCurrentPrice(), result.getCurrentPrice());
        verify(cacheService).set(eq(RedisKeyManager.productPriceKey(7L)), any(ProductPriceVo.class), any(Duration.class));
    }

    @Test
    void shouldCreateProductWithDefaultStatus() {
        ProductAddDto dto = new ProductAddDto();
        dto.setProductName("AirPods");
        dto.setProductUrl("https://example.com/airpods");
        dto.setPlatform("amazon");
        dto.setCurrentPrice(new BigDecimal("199.99"));
        dto.setCurrency("USD");
        dto.setImageUrl("https://example.com/airpods.png");

        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(10L);
            return 1;
        });

        Long id = productService.addProduct(dto);

        assertEquals(10L, id);
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        Product saved = captor.getValue();
        assertEquals(1, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void shouldUpdateProductAndClearDetailCache() {
        Product existing = buildProduct(3L);
        ProductUpdateDto dto = new ProductUpdateDto();
        dto.setProductName("Kindle Paperwhite");
        dto.setProductUrl("https://example.com/kindle-new");
        dto.setPlatform("amazon");
        dto.setCurrentPrice(new BigDecimal("139.99"));
        dto.setCurrency("USD");
        dto.setImageUrl("https://example.com/kindle-new.png");

        when(productMapper.selectById(3L)).thenReturn(existing);

        productService.updateProduct(3L, dto);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(captor.capture());
        Product updated = captor.getValue();
        assertEquals("Kindle Paperwhite", updated.getProductName());
        assertEquals("https://example.com/kindle-new", updated.getProductUrl());
        verify(cacheService).delete(RedisKeyManager.productDetailKey(3L));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(3L));
    }

    @Test
    void shouldMarkProductDeletedByStatusAndClearCache() {
        Product existing = buildProduct(4L);
        when(productMapper.selectById(4L)).thenReturn(existing);

        productService.deleteProduct(4L);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        verify(cacheService).delete(RedisKeyManager.productDetailKey(4L));
        verify(cacheService).delete(RedisKeyManager.productPriceKey(4L));
    }

    @Test
    void shouldReturnPagedProducts() {
        Product first = buildProduct(5L);
        Product second = buildProduct(6L);
        first.setProductName("Kindle 1");
        second.setProductName("Kindle 2");
        Page<Product> page = new Page<>(1, 10);
        page.setRecords(List.of(first, second));
        page.setTotal(2);

        when(productMapper.selectPage(any(Page.class), any()))
                .thenReturn(page);

        PageResult<ProductPageVo> result = productService.pageProducts(1L, 10L, "Kindle");

        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals("Kindle 1", result.getRecords().get(0).getProductName());
        assertInstanceOf(ProductPageVo.class, result.getRecords().get(0));
    }

    private Product buildProduct(Long id) {
        Product product = new Product();
        product.setId(id);
        product.setProductName("Kindle");
        product.setProductUrl("https://example.com/kindle");
        product.setPlatform("amazon");
        product.setCurrentPrice(new BigDecimal("129.99"));
        product.setCurrency("USD");
        product.setImageUrl("https://example.com/kindle.png");
        product.setStatus(1);
        product.setLastCheckedAt(LocalDateTime.of(2026, 4, 1, 10, 0));
        product.setCreatedAt(LocalDateTime.of(2026, 4, 1, 10, 0));
        product.setUpdatedAt(LocalDateTime.of(2026, 4, 1, 10, 0));
        return product;
    }
}
