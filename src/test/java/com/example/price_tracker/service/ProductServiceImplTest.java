package com.example.price_tracker.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.dto.ProductAddDto;
import com.example.price_tracker.dto.ProductUpdateDto;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.service.impl.ProductServiceImpl;
import com.example.price_tracker.vo.ProductDetailVo;
import com.example.price_tracker.vo.ProductPageVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper, redisTemplate);
    }

    @Test
    void shouldReturnCachedProductDetailBeforeDatabaseLookup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ProductDetailVo cached = ProductDetailVo.builder()
                .id(1L)
                .productName("Kindle")
                .platform("amazon")
                .build();
        when(valueOperations.get("product:detail:1")).thenReturn(cached);

        ProductDetailVo result = productService.getProductDetail(1L);

        assertEquals("Kindle", result.getProductName());
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void shouldLoadProductDetailFromDatabaseAndCacheWhenRedisMisses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Product product = buildProduct(2L);
        when(valueOperations.get("product:detail:2")).thenReturn(null);
        when(productMapper.selectById(2L)).thenReturn(product);

        ProductDetailVo result = productService.getProductDetail(2L);

        assertEquals(2L, result.getId());
        assertEquals("USD", result.getCurrency());
        verify(valueOperations).set(eq("product:detail:2"), any(ProductDetailVo.class));
    }

    @Test
    void shouldThrowNotFoundWhenProductDetailDoesNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:detail:99")).thenReturn(null);
        when(productMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productService.getProductDetail(99L));

        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
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
        verify(redisTemplate).delete("product:detail:3");
    }

    @Test
    void shouldMarkProductDeletedByStatusAndClearCache() {
        Product existing = buildProduct(4L);
        when(productMapper.selectById(4L)).thenReturn(existing);

        productService.deleteProduct(4L);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        verify(redisTemplate).delete("product:detail:4");
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
