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

## 📈 Scale assumptions

- ~70M monthly web visits
- Mobile app traffic included: assume 2–4× web → ~10M DAU
- Design target: 100M registered users, 10M DAU
- Peak: weekend evenings (European football, Nigeria UTC+1), 10× average
  → ~6K write QPS / ~25K read QPS

---

## 🧠 Design decisions

### Leaderboard tie-break

The spec ranks users by total points but does not say how to order equal totals. This service orders them by
`userId` ascending, so the result is deterministic and identical in Redis and in the MySQL fallback.

- The sorted set stores `score = -total` and is read with `ZRANGE 0 9`: the highest total comes first, and equal
  scores fall back to member order, which is `userId` ascending. `ZREVRANGE` on positive scores would order ties by
  `userId` descending.
- Because the stored order matches the displayed order, `ZRANK` would give a user's rank directly if a
  "my rank" endpoint were added.
- Redis compares members byte by byte, and `user_id` uses the `utf8mb4_0900_bin` collation, which does the same.
  Together with the ASCII-only `userId` pattern, both stores agree on tie order.

"First to reach the total ranks first" would be a better product rule. It needs a composite score that packs the
total and the time into one number. A Redis score is a double, which is exact only up to 2^53, so the two parts must
share 53 bits:

| Time part | Bits | Total part left |
| --- | --- | --- |
| seconds over 10 years (all-time) | 29 | 24 bits ≈ 16.7M, which 17 maximum adds already exceed |
| seconds over one season (≤ 388 days) | 25 | 28 bits ≈ 268M |
| milliseconds over one season | 35 | 18 bits ≈ 262K |

The composite score therefore only works for a leaderboard bounded in time, such as a season or an event. The spec's
`total` is the all-time total returned by `GET /points/{userId}`, so this service keeps the `userId` tie-break. For a
seasonal leaderboard it would switch to `score = total × 2^25 + (seasonEnd − reachedAt)`, read with `ZREVRANGE`, and
cap the season total at 2^28 − 1 with `TOTAL_LIMIT_EXCEEDED` (422) so scores never lose precision.

---

## 🔧 Features to Implement

### 1️⃣ Add User Points

**Endpoint:** `POST /points`

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
