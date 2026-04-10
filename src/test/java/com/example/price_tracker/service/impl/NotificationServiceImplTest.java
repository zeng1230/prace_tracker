package com.example.price_tracker.service.impl;

import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.entity.Notification;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.NotificationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(99L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void markReadRejectsOtherUsersNotification() {
        when(notificationMapper.selectById(5L)).thenReturn(notificationOwnedByAnotherUser());

        BusinessException exception = assertThrows(BusinessException.class, () -> notificationService.markRead(5L));

        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
        assertEquals("notification not found", exception.getMessage());
    }

    private Notification notificationOwnedByAnotherUser() {
        Notification notification = new Notification();
        notification.setId(5L);
        notification.setUserId(77L);
        notification.setIsRead(0);
        return notification;
    }
}
