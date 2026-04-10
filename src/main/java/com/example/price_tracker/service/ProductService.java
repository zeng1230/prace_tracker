package com.example.price_tracker.service;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.dto.ProductAddDto;
import com.example.price_tracker.dto.ProductUpdateDto;
import com.example.price_tracker.vo.ProductDetailVo;
import com.example.price_tracker.vo.ProductPageVo;

public interface ProductService {

    Long addProduct(ProductAddDto productAddDto);

    ProductDetailVo getProductDetail(Long id);

    PageResult<ProductPageVo> pageProducts(Long pageNum, Long pageSize, String keyword);

    void updateProduct(Long id, ProductUpdateDto productUpdateDto);

    void deleteProduct(Long id);
}
