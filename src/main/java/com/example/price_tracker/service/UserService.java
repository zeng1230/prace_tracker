package com.example.price_tracker.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.price_tracker.entity.User;
import com.example.price_tracker.vo.UserVo;

public interface UserService extends IService<User> {

    UserVo getCurrentUser();

    User getByUsername(String username);

    User getRequiredById(Long userId);

    UserVo toUserVo(User user);
}
