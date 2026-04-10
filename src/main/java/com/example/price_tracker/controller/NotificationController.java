package com.example.price_tracker.controller;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.Result;
import com.example.price_tracker.dto.NotificationQueryDto;
import com.example.price_tracker.service.NotificationService;
import com.example.price_tracker.vo.NotificationVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/my")
    public Result<PageResult<NotificationVo>> my(@Valid NotificationQueryDto queryDto) {
        return Result.success(notificationService.pageMyNotifications(queryDto));
    }

    @PutMapping("/{id}/read")
    public Result<Void> read(@PathVariable @Min(value = 1, message = "id must be greater than 0") Long id) {
        notificationService.markRead(id);
        return Result.success();
    }
}
