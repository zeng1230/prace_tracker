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
import com.example.price_tracker.service.ProductService;
import com.example.price_tracker.vo.ProductDetailVo;
import com.example.price_tracker.vo.ProductPageVo;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final String PRODUCT_DETAIL_CACHE_KEY = "product:detail:";
    private static final int ACTIVE_STATUS = 1;
    private static final int DELETED_STATUS = 0;

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;

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
        String cacheKey = buildProductDetailCacheKey(id);
        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue instanceof ProductDetailVo productDetailVo) {
            return productDetailVo;
        }

        Product product = getActiveProductOrThrow(id);
        ProductDetailVo detailVo = toProductDetailVo(product);
        redisTemplate.opsForValue().set(cacheKey, detailVo);
        return detailVo;
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
        clearProductDetailCache(id);
    }

    @Override
    public void deleteProduct(Long id) {
        Product existing = getActiveProductOrThrow(id);
        existing.setStatus(DELETED_STATUS);
        existing.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(existing);
        clearProductDetailCache(id);
    }

    private Product getActiveProductOrThrow(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || !isActiveStatus(product.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
        }
        return product;
    }

    private boolean isActiveStatus(Integer status) {
        return status != null && status == ACTIVE_STATUS;
    }

    private void clearProductDetailCache(Long id) {
        redisTemplate.delete(buildProductDetailCacheKey(id));
    }

    private String buildProductDetailCacheKey(Long id) {
        return PRODUCT_DETAIL_CACHE_KEY + id;
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
}
