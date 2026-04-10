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
public class PriceHistoryVo {

    private Long id;
    private Long productId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private LocalDateTime capturedAt;
    private String source;
}
