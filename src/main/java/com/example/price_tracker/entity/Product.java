package com.example.price_tracker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("product_name")
    private String productName;

    @TableField("product_url")
    private String productUrl;

    private String platform;

    @TableField("current_price")
    private BigDecimal currentPrice;

    private String currency;

    @TableField("image_url")
    private String imageUrl;

    private Integer status;

    @TableField("last_checked_at")
    private LocalDateTime lastCheckedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
