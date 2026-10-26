## 🎯 Objective

An `User Points Service` that allows adding and tracking user reward points.
The system should support:
- Adding points to a user
- Querying current user points
- Caching points for performance
- Emitting a message event on point updates
- Maintaining a simple `Top 10` user leaderboard in Redis

---

## 🧰 Tech Requirements

You **must use** the following technologies:

- **Java 21+**
- **Spring Boot**
- **MySQL** (for persistence)
- **Redis** (for caching)
- **RocketMQ** (for event messaging)

You may use starter dependencies such as:
- Spring Web
- Spring Data JPA
- Spring Cache
- RocketMQ Spring Boot Starter

---

## ✅ Implementation

Run it, seed data and try the endpoints: [HELP.md](HELP.md). Design trade-offs: [docs/TRADE_OFF.md](docs/TRADE_OFF.md).

- All five endpoints; Swagger UI at `/user-points-service/swagger-ui/index.html`.
- Totals are cached in Redis; the leaderboard is a Redis sorted set, and equal totals rank by `userId` ascending.
- Every add publishes an `ADDED` message to `user-points-topic` through a transactional outbox, so a committed add
  always gets its event. Delivery is at least once and unordered: deduplicate by `eventId`.
- Required `Idempotency-Key` prevents duplicate adds on HTTP retries; a changed payload with the same key returns 409.
- Errors return `{system, requestId, statusCode, message, errors?}` with a matching HTTP status.
- 123 unit and Testcontainers integration tests, 93% line coverage, run on GitHub Actions.

Message, tag `ADDED`, keys `userId` and the request id:

```json
{
  "eventId": "52121adb-b686-4470-a950-687907026ecb",
  "userId": "user_123",
  "amount": 100,
  "total": 1100,
  "requestId": "e2e-1",
  "occurredAt": "2026-10-24T09:02:24.936Z"
}
```

---

## 🔧 Features to Implement

### 1️⃣ Add User Points

**Endpoint:** `POST /points`

**Required header:** `Idempotency-Key: <unique-operation-id>` — use a new key per add and reuse it on retries.

```json
{
  "userId": "user_123",
  "amount": 100,
  "reason": "signup_bonus"
}
```

**Expected Behavior:**
- Store the points record in MySQL
- Update the user's total points
- Publish a message to `user-points-topic` via RocketMQ

---

### 2️⃣ Get Total Points

**Endpoint:** `GET /points/{userId}`

**Expected Behavior:**
- Retrieve the user's total points from Redis cache first
- If not found, fallback to MySQL and update the cache

---

### 3️⃣ Get Leaderboard

**Endpoint:** `GET /points/leaderboard`

```json
[
  { "userId": "user_001", "total": 950 },
  { "userId": "user_002", "total": 820 }
]
```

**Expected Behavior:**
- Retrieve top 10 users ranked by highest total points from a Redis sorted set

---

### 4️⃣ Update Reason

**Endpoint:** `PUT /points/{id}`

**Expected Behavior:**
- Update the `reason` field of a specific point record in MySQL
- Invalidate or update related Redis cache if applicable

---

### 5️⃣ Delete User Points

**Endpoint:** `DELETE /points/{userId}`

**Expected Behavior:**
- Remove all point records for the user from MySQL
- Remove user data from Redis cache and leaderboard


⸻

🧪 Bonus (Optional)
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088  

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvn spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples  

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
