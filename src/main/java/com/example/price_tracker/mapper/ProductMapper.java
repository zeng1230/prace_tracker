package com.example.price_tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    @Select("""
            <script>
            SELECT id, product_name, product_url, platform, current_price, currency, image_url,
                   status, last_checked_at, created_at, updated_at
            FROM tb_product
            <if test="keyword != null and keyword != ''">
              WHERE product_name LIKE CONCAT('%', #{keyword}, '%')
                 OR platform LIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    Page<Product> selectAdminPage(Page<Product> page, @Param("keyword") String keyword);

    @Select("""
            SELECT id, product_name, product_url, platform, current_price, currency, image_url,
                   status, last_checked_at, created_at, updated_at
            FROM tb_product
            WHERE id = #{productId}
            """)
    Product selectAdminById(@Param("productId") Long productId);

    @Update("""
            UPDATE tb_product
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{productId}
            """)
    int updateStatusByAdmin(@Param("productId") Long productId, @Param("status") Integer status);

    @Update("""
            <script>
            UPDATE tb_product
            SET currency = #{currency},
                last_checked_at = #{lastCheckedAt},
                updated_at = updated_at
            WHERE id = #{productId}
              AND status = #{activeStatus}
              AND
              <choose>
                <when test="expectedOldPrice == null">current_price IS NULL</when>
                <otherwise>current_price = #{expectedOldPrice}</otherwise>
              </choose>
            </script>
            """)
    int updateRefreshMetadataIfPriceMatches(@Param("productId") Long productId,
                                            @Param("activeStatus") Integer activeStatus,
                                            @Param("expectedOldPrice") BigDecimal expectedOldPrice,
                                            @Param("currency") String currency,
                                            @Param("lastCheckedAt") LocalDateTime lastCheckedAt);

    @Update("""
            <script>
            UPDATE tb_product
            SET current_price = #{newPrice},
                currency = #{currency},
                last_checked_at = #{lastCheckedAt},
                updated_at = #{updatedAt}
            WHERE id = #{productId}
              AND status = #{activeStatus}
              AND
              <choose>
                <when test="expectedOldPrice == null">current_price IS NULL</when>
                <otherwise>current_price = #{expectedOldPrice}</otherwise>
              </choose>
            </script>
            """)
    int updateRefreshPriceIfPriceMatches(@Param("productId") Long productId,
                                         @Param("activeStatus") Integer activeStatus,
                                         @Param("expectedOldPrice") BigDecimal expectedOldPrice,
                                         @Param("newPrice") BigDecimal newPrice,
                                         @Param("currency") String currency,
                                         @Param("lastCheckedAt") LocalDateTime lastCheckedAt,
                                         @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT id, current_price, currency, status, last_checked_at, updated_at
            FROM tb_product
            WHERE id = #{productId}
            FOR UPDATE
            """)
    Product selectRefreshStateForUpdate(@Param("productId") Long productId);
}
