create table tb_price_history
(
    id          bigint auto_increment comment '价格历史ID'
        primary key,
    product_id  bigint                                not null comment '商品ID',
    old_price   decimal(10, 2)                        null comment '旧价格',
    new_price   decimal(10, 2)                        not null comment '新价格',
    captured_at datetime    default CURRENT_TIMESTAMP not null comment '采集时间',
    source      varchar(50) default 'mock'            not null comment '价格来源'
)
    comment '价格历史表';
