package com.example.price_tracker.service;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.dto.NotificationQueryDto;
import com.example.price_tracker.vo.NotificationVo;

public interface NotificationService {

    PageResult<NotificationVo> pageMyNotifications(NotificationQueryDto queryDto);

    void markRead(Long id);
}
