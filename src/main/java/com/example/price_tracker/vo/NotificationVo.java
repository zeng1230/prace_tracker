package com.example.price_tracker.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVo {

    private Long id;
    private Long productId;
    private Long watchlistId;
    private String productName;
    private String notifyType;
    private String content;
    private Integer isRead;
    private Integer sendStatus;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
