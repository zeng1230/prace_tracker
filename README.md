# Price Tracker

Spring Boot 3.x backend for a price tracking system.

## Stack

- Java 17
- Maven Wrapper
- Spring Boot 3
- MyBatis Plus
- MySQL 8
- Redis
- JWT
- Lombok
- Knife4j

## API Targets

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

## Local Startup

1. Create MySQL database `price_tracker`.
2. Execute SQL files under `src/main/resources/sql/`.
3. Start Redis on `localhost:6379`.
4. Adjust datasource and Redis settings in `src/main/resources/application.yml` if needed.
5. Run compile check:

```powershell
.\mvnw.cmd -q -DskipTests compile
```

6. Start application:

```powershell
.\mvnw.cmd spring-boot:run
```

Knife4j doc entry: `http://localhost:8080/doc.html`
