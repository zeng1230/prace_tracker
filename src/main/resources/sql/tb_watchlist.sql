create table tb_watchlist
(
    id                  bigint auto_increment comment '关注记录ID'
        primary key,
    user_id             bigint                             not null comment '用户ID',
    product_id          bigint                             not null comment '商品ID',
    target_price        decimal(10, 2)                     null comment '目标价格',
    notify_enabled      tinyint  default 1                 not null comment '是否开启提醒 1开启 0关闭',
    last_notified_price decimal(10, 2)                     null comment '最近提醒价格',
    status              tinyint  default 1                 not null comment '状态 1关注中 0已取消',
    created_at          datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at          datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_user_product
        unique (user_id, product_id)
)
    comment '用户关注表';