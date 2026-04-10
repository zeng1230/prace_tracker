package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.dto.NotificationQueryDto;
import com.example.price_tracker.entity.Notification;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.NotificationMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.service.NotificationService;
import com.example.price_tracker.vo.NotificationVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final ProductMapper productMapper;

    @Override
    public PageResult<NotificationVo> pageMyNotifications(NotificationQueryDto queryDto) {
        Long currentUserId = requireCurrentUserId();
        Page<Notification> page = notificationMapper.selectPage(
                new Page<>(queryDto.getPageNum(), queryDto.getPageSize()),
                lambdaQuery(Notification.class)
                        .eq(Notification::getUserId, currentUserId)
                        .orderByDesc(Notification::getCreatedAt)
        );
        Map<Long, Product> productMap = buildProductMap(page.getRecords());
        List<NotificationVo> records = page.getRecords().stream()
                .map(notification -> toVo(notification, productMap.get(notification.getProductId())))
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public void markRead(Long id) {
        Long currentUserId = requireCurrentUserId();
        Notification notification = notificationMapper.selectById(id);
        if (notification == null || !currentUserId.equals(notification.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "notification not found");
        }
        if (notification.getIsRead() != null && notification.getIsRead() == 1) {
            return;
        }
        notification.setIsRead(1);
        notificationMapper.updateById(notification);
    }

    private Map<Long, Product> buildProductMap(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(notifications.stream().map(Notification::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private NotificationVo toVo(Notification notification, Product product) {
        return NotificationVo.builder()
                .id(notification.getId())
                .productId(notification.getProductId())
                .watchlistId(notification.getWatchlistId())
                .productName(product == null ? null : product.getProductName())
                .notifyType(notification.getNotifyType())
                .content(notification.getContent())
                .isRead(notification.getIsRead())
                .sendStatus(notification.getSendStatus())
                .createdAt(notification.getCreatedAt())
                .sentAt(notification.getSentAt())
                .build();
    }

    private Long requireCurrentUserId() {
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "current user is not authenticated");
        }
        return currentUserId;
    }
}
