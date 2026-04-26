# Price Tracker

Price Tracker 是一个基于 Spring Boot 3.x 的商品价格跟踪后端项目。当前实现聚焦用户认证、商品管理、关注列表、价格刷新、价格历史、站内通知，以及第二阶段 RabbitMQ 异步价格提醒链路。

## 核心功能

- 用户认证：支持注册、登录，并通过 JWT 保护需要登录的接口。
- 当前用户：支持查询当前登录用户信息。
- 商品管理：支持商品新增、详情查询、分页查询、更新和逻辑删除。
- 关注列表：用户可以关注商品，设置目标价和是否开启通知。
- 价格刷新：内部接口和定时任务可以触发商品价格刷新，当前价格由 mock 工具生成。
- 价格历史：商品价格发生变化后记录历史价格。
- 站内通知：用户可以分页查看自己的通知，并将通知标记为已读。
- 异步提醒：商品价格达到关注目标价时，通过 RabbitMQ 投递价格提醒消息，再由消费者创建通知。

## 技术栈

- Java 17
- Maven Wrapper
- Spring Boot 3.3.4
- Spring Validation
- MyBatis Plus
- MySQL 8
- Redis
- RabbitMQ
- JWT
- Lombok
- Knife4j

## 模块说明

- `controller`：接收请求参数并统一返回 `Result<T>`，不直接暴露 entity。
- `service`：承载业务逻辑，包括商品、关注、通知、价格刷新和认证逻辑。
- `mapper`：基于 MyBatis Plus 访问数据库。
- `dto`：请求参数对象。
- `vo`：响应参数对象。
- `entity`：数据库表映射对象。
- `mq.message`：RabbitMQ 消息体，目前包含 `PriceAlertMessage`。
- `mq.producer`：价格提醒消息生产者。
- `mq.consumer`：价格提醒消息消费者。
- `config`：Web、MyBatis Plus、Redis、RabbitMQ、Knife4j、JWT 相关配置。
- `task`：定时价格刷新任务。

## RabbitMQ 异步通知链路

当前价格提醒链路已经从“刷新价格时直接写通知表”改为 RabbitMQ 异步处理。

1. `PriceServiceImpl.refreshProductPrice(productId)` 查询有效商品并生成新价格。
2. 如果价格未变化，只更新 `lastCheckedAt`，不记录价格历史，也不发送通知消息。
3. 如果价格发生变化，更新商品当前价和检查时间，并写入 `tb_price_history`。
4. 服务查询该商品下 `status = 1` 且 `notify_enabled = 1` 的关注记录。
5. 当 `target_price` 不为空且 `newPrice <= targetPrice` 时，构造 `PriceAlertMessage`。
6. `PriceAlertProducer` 将消息发送到 RabbitMQ。
7. `PriceAlertConsumer` 监听队列并调用 `NotificationService.consumePriceAlert(message)`。
8. `NotificationServiceImpl` 再次校验关注记录是否仍有效、是否仍开启通知、价格是否仍达到目标价。
9. 通过校验后写入 `tb_notification`，并把 `tb_watchlist.last_notified_price` 更新为当前触发价格。

### RabbitMQ 设计

- Exchange：`price.alert.exchange`
- Exchange 类型：`DirectExchange`
- Exchange 持久化：是
- Queue：`price.alert.queue`
- Queue 持久化：是
- Routing key：`price.alert`
- Binding：`price.alert.queue` 绑定到 `price.alert.exchange`，routing key 为 `price.alert`
- 消息序列化：`Jackson2JsonMessageConverter`
- 消费入口：`PriceAlertConsumer.consume`
- 业务处理入口：`NotificationService.consumePriceAlert`

### 消息体

`PriceAlertMessage` 当前字段如下：

- `userId`：关注记录所属用户 ID。
- `productId`：触发价格提醒的商品 ID。
- `watchlistId`：触发价格提醒的关注记录 ID。
- `currentPrice`：刷新后的当前价格。
- `targetPrice`：用户设置的目标价格。
- `productName`：商品名称，用于生成通知内容。
- `triggeredAt`：触发时间，消费端为空时会使用当前时间兜底。

### 简单防重策略

当前防重策略在消费端执行：

- 消费消息时重新查询 `watchlistId` 对应的关注记录。
- 如果关注记录不存在、`status != 1`、`notify_enabled != 1`，直接跳过。
- 如果 `currentPrice > targetPrice`，直接跳过。
- 如果 `watchlist.last_notified_price` 与消息中的 `currentPrice` 相等，认为同一价格已经通知过，直接跳过。
- 成功创建通知后，将 `last_notified_price` 更新为本次 `currentPrice`，用于后续去重。

当前尚未实现死信队列、延迟重试、幂等唯一索引、外部邮件或短信发送。

## API 概览

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/users/me`
- `POST /api/products`
- `GET /api/products/{id}`
- `GET /api/products`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`
- `POST /api/watchlist`
- `GET /api/watchlist/my`
- `PUT /api/watchlist/{id}`
- `DELETE /api/watchlist/{id}`
- `GET /api/products/{id}/price-history`
- `GET /api/notifications/my`
- `PUT /api/notifications/{id}/read`
- `POST /api/internal/products/{id}/refresh-price`

## 本地启动

1. 创建 MySQL 数据库 `price_tracker`。
2. 执行 `src/main/resources/sql/` 下的 SQL 文件。
3. 启动 Redis，默认连接 `localhost:6379`。
4. 启动 RabbitMQ，默认连接 `localhost:5672`，账号密码为 `guest/guest`。
5. 如本地环境不同，调整 `src/main/resources/application.yml` 中的 MySQL、Redis、RabbitMQ 配置。
6. 执行编译检查：

```powershell
.\mvnw.cmd -q -DskipTests compile
```

7. 启动应用：

```powershell
.\mvnw.cmd spring-boot:run
```

Knife4j 文档入口：`http://localhost:8080/doc.html`

## 项目亮点

- 统一包名 `com.example.price_tracker`，项目结构按 Spring Boot 单体应用组织。
- 统一使用 `Result<T>` 和 `PageResult<T>` 返回接口数据。
- DTO 和 VO 分离，控制器不直接返回 entity。
- 商品删除、关注删除采用状态字段实现逻辑删除。
- 价格提醒通过 RabbitMQ 异步解耦，价格刷新不直接承担通知落库逻辑。
- 消费端保留业务校验和简单防重，降低重复消息导致重复通知的概率。

## 后续规划

- 为 RabbitMQ 链路补充更完整的可靠性设计，例如消费重试、死信队列和异常消息观测。
- 为通知防重补充数据库级唯一约束或消息幂等表。
- 根据业务需要扩展通知发送渠道，目前只实现站内通知落库。
- 补充更完整的集成测试环境说明，例如 MySQL、Redis、RabbitMQ 的 Docker Compose 启动方式。

## Stage 2 Addendum: RabbitMQ Async Notification

### Stage 2 Goal

Stage 2 completes the async price alert link for the existing Price Tracker project. The refresh flow is still responsible for updating product prices and writing price history, while the notification write path is moved behind RabbitMQ.

### Why RabbitMQ

- Decouple price refresh from notification persistence.
- Reduce the chance that notification handling slows down or blocks the refresh path.
- Keep room for later retry, dead-letter, and channel expansion.

### Async Link

```text
PriceRefreshTask / Internal refresh API
  -> PriceServiceImpl.refreshProductPrice(productId)
  -> update product current_price
  -> insert tb_price_history
  -> query active watchlist records with notify_enabled = 1
  -> if currentPrice <= targetPrice, build PriceAlertMessage
  -> PriceAlertProducer sends message to exchange
  -> queue receives message through routing key
  -> PriceAlertConsumer consumes message asynchronously
  -> NotificationService.consumePriceAlert(message)
  -> insert tb_notification
  -> update tb_watchlist.last_notified_price
```

### Key Classes

- `src/main/java/com/example/price_tracker/config/RabbitMQConfig.java`
  Defines the exchange, queue, binding, and JSON converter.
- `src/main/java/com/example/price_tracker/mq/message/PriceAlertMessage.java`
  RabbitMQ event payload. Fields: `messageId`, `userId`, `productId`, `watchlistId`, `currentPrice`, `targetPrice`, `productName`, `triggeredAt`.
- `src/main/java/com/example/price_tracker/mq/producer/PriceAlertProducer.java`
  Sends the message and writes send-before, send-success, and send-failure logs.
- `src/main/java/com/example/price_tracker/mq/consumer/PriceAlertConsumer.java`
  Receives the message, logs receive/start/success/failure, and delegates to the notification service.
- `src/main/java/com/example/price_tracker/service/impl/NotificationServiceImpl.java`
  Validates the event, applies duplicate suppression based on `last_notified_price`, writes the notification, and updates the watchlist.

### Test Scenarios

Current MQ tests cover these scenarios:

1. Current price is above target price: no RabbitMQ message is sent.
2. Current price is less than or equal to target price: the RabbitMQ producer is called.
3. Consumer receives a valid message: notification handling succeeds.
4. Consumer handling throws an exception: an error log is written and the listener does not rethrow to break the main flow.

Run the focused MQ tests:

```powershell
.\mvnw.cmd -q "-Dtest=PriceAlertConsumerTest,PriceServiceImplTest,NotificationServiceImplTest" test
```

Run the full test suite:

```powershell
.\mvnw.cmd -q test
```

### Current Stage Boundaries

- The producer only filters business conditions and dispatches messages.
- Final duplicate suppression is still handled by the consumer side.
- Listener-side exceptions are logged clearly and swallowed at the MQ boundary so price refresh is not failed by notification-side issues.
- This stage does not yet implement dead-letter queues, retry orchestration, idempotency tables, or external email/SMS sending.

### Next Steps

- Stronger idempotency guarantees
- Dead-letter queue
- Consumer retry strategy
- Notification channel expansion
- Better concurrency strategy for large batch refresh jobs
