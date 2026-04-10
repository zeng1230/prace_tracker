package com.example.price_tracker.vo;

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
public class WatchlistVo {

    private Long id;
    private Long productId;
    private String productName;
    private String productUrl;
    private String platform;
    private BigDecimal currentPrice;
    private String currency;
    private String imageUrl;
    private BigDecimal targetPrice;
    private Integer notifyEnabled;
    private BigDecimal lastNotifiedPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
