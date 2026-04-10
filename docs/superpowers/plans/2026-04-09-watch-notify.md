# Watch Notify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the watchlist, price history, notification, internal refresh, and scheduled price refresh flow for PT-04 without reimplementing auth or product CRUD.

**Architecture:** Extend the existing Spring Boot + MyBatis Plus structure with focused entities, DTOs, VOs, mappers, services, controllers, and a scheduler. Centralize price change logic in `PriceService.refreshProductPrice(Long productId)` so manual refresh and scheduled refresh reuse the same behavior, including history recording and deduplicated notifications.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis Plus, Redis, Lombok, Spring Validation, JUnit 5, Mockito

---

### Task 1: Lock in PT-04 service behavior with tests

**Files:**
- Create: `src/test/java/com/example/price_tracker/service/impl/WatchlistServiceImplTest.java`
- Create: `src/test/java/com/example/price_tracker/service/impl/NotificationServiceImplTest.java`
- Create: `src/test/java/com/example/price_tracker/service/impl/PriceServiceImplTest.java`

- [ ] **Step 1: Write the failing watchlist test**

```java
@Test
void addWatchlistReactivatesExistingDisabledRecord() {
    when(watchlistMapper.selectOne(any())).thenReturn(existingDisabledWatchlist());

    Long id = watchlistService.addWatchlist(addDto());

    assertEquals(10L, id);
    verify(watchlistMapper).updateById(argThat(watchlist ->
            watchlist.getStatus() == 1 &&
            watchlist.getNotifyEnabled() == 1 &&
            new BigDecimal("88.00").compareTo(watchlist.getTargetPrice()) == 0));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=WatchlistServiceImplTest test`
Expected: FAIL because `WatchlistServiceImpl` and dependent PT-04 model code do not exist yet.

- [ ] **Step 3: Write the failing notification test**

```java
@Test
void markReadRejectsOtherUsersNotification() {
    UserContext.setCurrentUserId(99L);
    when(notificationMapper.selectById(5L)).thenReturn(notificationOwnedByAnotherUser());

    assertThrows(BusinessException.class, () -> notificationService.markRead(5L));
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -Dtest=NotificationServiceImplTest test`
Expected: FAIL because `NotificationServiceImpl` does not exist yet.

- [ ] **Step 5: Write the failing price refresh tests**

```java
@Test
void refreshProductPriceCreatesHistoryAndNotificationWhenTargetReached() {
    when(priceMockUtil.generateNextPrice(new BigDecimal("100.00"))).thenReturn(new BigDecimal("79.00"));
    when(productMapper.selectById(1L)).thenReturn(activeProduct());
    when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlist()));

    priceService.refreshProductPrice(1L);

    verify(productMapper).updateById(any(Product.class));
    verify(priceHistoryMapper).insert(any(PriceHistory.class));
    verify(notificationMapper).insert(any(Notification.class));
    verify(watchlistMapper).updateById(argThat(w -> new BigDecimal("79.00").compareTo(w.getLastNotifiedPrice()) == 0));
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn -q -Dtest=PriceServiceImplTest test`
Expected: FAIL because `PriceServiceImpl` and PT-04 persistence objects do not exist yet.

### Task 2: Add PT-04 domain types and persistence

**Files:**
- Modify: `src/main/java/com/example/price_tracker/entity/Product.java`
- Modify: `src/main/java/com/example/price_tracker/entity/Watchlist.java`
- Modify: `src/main/java/com/example/price_tracker/entity/PriceHistory.java`
- Modify: `src/main/java/com/example/price_tracker/entity/Notification.java`
- Create: `src/main/java/com/example/price_tracker/dto/WatchlistQueryDto.java`
- Modify: `src/main/java/com/example/price_tracker/dto/WatchlistAddDto.java`
- Modify: `src/main/java/com/example/price_tracker/dto/WatchlistUpdateDto.java`
- Create: `src/main/java/com/example/price_tracker/dto/PriceHistoryQueryDto.java`
- Create: `src/main/java/com/example/price_tracker/dto/NotificationQueryDto.java`
- Create: `src/main/java/com/example/price_tracker/vo/WatchlistVo.java`
- Create: `src/main/java/com/example/price_tracker/vo/PriceHistoryVo.java`
- Create: `src/main/java/com/example/price_tracker/vo/NotificationVo.java`
- Create: `src/main/java/com/example/price_tracker/mapper/WatchlistMapper.java`
- Create: `src/main/java/com/example/price_tracker/mapper/PriceHistoryMapper.java`
- Create: `src/main/java/com/example/price_tracker/mapper/NotificationMapper.java`

- [ ] **Step 1: Implement PT-04 entities and DTO/VO contracts**
- [ ] **Step 2: Run `mvn -q -Dtest=WatchlistServiceImplTest,NotificationServiceImplTest,PriceServiceImplTest test` and confirm failures move from missing types to missing service behavior**

### Task 3: Implement watchlist and notification services

**Files:**
- Create: `src/main/java/com/example/price_tracker/service/WatchlistService.java`
- Create: `src/main/java/com/example/price_tracker/service/NotificationService.java`
- Create: `src/main/java/com/example/price_tracker/service/impl/WatchlistServiceImpl.java`
- Create: `src/main/java/com/example/price_tracker/service/impl/NotificationServiceImpl.java`

- [ ] **Step 1: Implement minimal watchlist service behavior to satisfy add/list/update/delete tests**
- [ ] **Step 2: Implement minimal notification list/read behavior**
- [ ] **Step 3: Re-run `mvn -q -Dtest=WatchlistServiceImplTest,NotificationServiceImplTest test` and confirm PASS**

### Task 4: Implement price refresh flow and scheduling

**Files:**
- Create: `src/main/java/com/example/price_tracker/service/PriceService.java`
- Create: `src/main/java/com/example/price_tracker/service/impl/PriceServiceImpl.java`
- Create: `src/main/java/com/example/price_tracker/util/PriceMockUtil.java`
- Create: `src/main/java/com/example/price_tracker/task/PriceRefreshTask.java`

- [ ] **Step 1: Implement `PriceMockUtil` and `PriceService.refreshProductPrice(Long productId)` with history + notification flow**
- [ ] **Step 2: Implement batch refresh helper used by scheduled task**
- [ ] **Step 3: Re-run `mvn -q -Dtest=PriceServiceImplTest test` and confirm PASS**

### Task 5: Expose PT-04 controllers

**Files:**
- Modify: `src/main/java/com/example/price_tracker/controller/WatchlistController.java`
- Modify: `src/main/java/com/example/price_tracker/controller/NontificationController.java`
- Modify: `src/main/java/com/example/price_tracker/controller/InternelController.java`

- [ ] **Step 1: Wire watchlist endpoints**
- [ ] **Step 2: Wire notification endpoints**
- [ ] **Step 3: Wire internal product refresh endpoint**
- [ ] **Step 4: Run targeted controller-adjacent tests or compile to catch signature issues**

### Task 6: Final verification

**Files:**
- Modify only if verification exposes defects

- [ ] **Step 1: Run PT-04 focused tests**

Run: `mvn -q -Dtest=WatchlistServiceImplTest,NotificationServiceImplTest,PriceServiceImplTest test`
Expected: PASS

- [ ] **Step 2: Run required compile verification**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: If compile fails, fix only the concrete remaining issues and re-run the same command until it passes or a real blocker remains**
