create table tb_product
(
    id              bigint auto_increment comment '商品ID'
        primary key,
    product_name    varchar(255)                          not null comment '商品名称',
    product_url     varchar(500)                          not null comment '商品链接',
    platform        varchar(50) default 'amazon'          not null comment '平台',
    current_price   decimal(10, 2)                        null comment '当前价格',
    currency        varchar(10) default 'USD'             not null comment '币种',
    image_url       varchar(500)                          null comment '商品图片',
    status          tinyint     default 1                 not null comment '状态 1有效 0失效',
    last_checked_at datetime                              null comment '上次检查时间',
    created_at      datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment '商品表';
