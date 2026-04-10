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
public class UserVo {

    private Long id;

    private String username;

    private String email;

    private String nickname;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
