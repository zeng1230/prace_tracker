# Codex Context

当前阶段：Price Tracker 已完成第三阶段 Redis 高并发增强，处于 Integration 收口。目标是验证、联调、补文档，不新增业务、不重写项目。

已完成能力：注册/登录/JWT/当前用户；商品新增、详情、当前价格、分页、更新、逻辑删除；商品详情/价格 Redis 缓存、空值缓存、击穿锁、TTL 随机偏移、缓存失效；关注新增、列表、改价、取消，含缓存、幂等、限流；通知列表、已读、价格提醒落库；RabbitMQ 异步提醒；Redis 通知入口幂等和 MQ 消费幂等；`PriceRefreshTask` 定时分页刷新商品，单商品失败不影响整体任务。

关键文件：`docs/STAGE_HANDOFF.md`、`docs/performance-test.md`、`README.md`、`application.yml`、`PriceRefreshTask.java`、`redis/*`、`mq/*`、`RabbitMQConfig.java`、`ProductServiceImpl.java`、`PriceServiceImpl.java`、`WatchlistServiceImpl.java`、`NotificationServiceImpl.java`。

核心链路：商品刷新更新价格并清理缓存；价格变化写 `tb_price_history`；达到目标价后先写 Redis 幂等 key，再发 `PriceAlertMessage` 到 `price.alert.exchange`；消费者监听 `price.alert.queue`，用 `messageId` 或业务字段做 Redis 幂等，再调用 `NotificationService.consumePriceAlert` 写 `tb_notification` 并更新 `last_notified_price`。

未完成/风险：真实压测未执行；RabbitMQ 连通未验证；MQ 消费失败只记日志不重投；RabbitMQ 配置主要写死 localhost/guest；README 有英文 Stage 2 附录重复；`SwapperConfig.java` 为空；工作区为脏状态，提交前需确认策略。

下一步：运行 `.\mvnw.cmd -q -DskipTests compile` 和 `.\mvnw.cmd -q test`；失败时最小修复；检查文档与代码一致；启动 MySQL、Redis、RabbitMQ 做真实联调；性能收口时按压测文档填写真实数据。

启动 Prompt：

```text
你正在接手 Price Tracker。先读 docs/CODEX_CONTEXT.md、docs/STAGE_HANDOFF.md、README.md、docs/performance-test.md，并查看 git status。当前目标是 Integration 收口，不要新增业务或重写项目。先运行 .\mvnw.cmd -q -DskipTests compile，再运行 .\mvnw.cmd -q test。若失败，按根因做最小修复。重点检查文档与代码一致性、RabbitMQ/Redis/MySQL 真实联调状态、压测文档是否需补真实结果。最后输出修改文件、执行命令、编译/测试结果、风险点和是否可收口。
```
