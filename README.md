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

### Event delivery: transactional outbox

A points change and its event must not disagree: an event for a rolled-back change, or a committed change with no
event. Sending to RocketMQ cannot join the MySQL transaction, so the event is written to `outbox_message` in the same
transaction, and `OutboxRelay` publishes it right after the commit.

| Option | Crash after commit | Broker down | Cost |
| --- | --- | --- | --- |
| Send after commit | event lost | `POST` still succeeds | lowest |
| **Transactional outbox** (chosen) | relay sends it after restart | `POST` still succeeds; rows wait in the outbox | one table and a relay |
| RocketMQ transactional message | broker checks back | `POST` fails, because the half message is sent first | service coupled to the broker |

- Only adding points publishes an event, as the spec asks. Deleting a user publishes nothing.
- The outbox is shared, not per message type. Each row carries its own `topic`, `tag` and `message_key`, so
  `OutboxWriter`, `OutboxRelay` and `RocketMqSender` know nothing about points; `PointsEventOutboxListener` is the only
  class that maps a points event to a message.
- A commit that wrote to the outbox wakes the relay (`OutboxRelayTrigger`), so a message usually leaves within
  milliseconds. Every pass runs on one dedicated thread (`ThreadConfig`), and wake-ups that arrive while a pass is
  queued merge into it. A sweep every 30 seconds and one pass at startup pick up rows that a crash or a failed send
  left behind.
- Delivery is at least once: if a send succeeds but deleting the row fails, the event is sent again. A send that the
  broker stored but reported as `FLUSH_DISK_TIMEOUT` or `SLAVE_NOT_AVAILABLE` is also sent again; a broker that flushes
  asynchronously and has no synchronous replica, as `broker.conf` sets up, never reports these. Each message carries
  an `eventId` for consumers to deduplicate.
- **No ordering guarantee.** `ADDED` events commute: a consumer that sums amounts or keeps a count gets the same result
  in any order. Paying for per-key ordering would buy consumers nothing, so the relay does not:
  - Each page of up to 200 rows goes to the broker as one batch (one round trip), grouped by topic and tag and split
    at 1 MB.
  - A failed batch stays in the outbox and is retried later; the batches after it in the same pass are still sent.
  - After 3 failed batches in a row the pass stops, because each one can wait for the 3-second send timeout. During
    an outage a pass therefore ends within about 10 seconds instead of holding up shutdown.
  - Measured locally by seeding 20,000 rows and timing how long one relay on Docker took to empty the outbox: about
    15,000 messages per second, against about 1,400 when every message was sent on its own in order. This is a drain
    rate, not a load test. The peak in the scale assumptions is about 6,000 writes per second.
- Dead letters: only a message that RocketMQ refuses for what it is (`MESSAGE_ILLEGAL`, such as a body over the size
  limit) moves to `outbox_dead_letter`, with the error. Sending it again would fail the same way. Every other failure,
  such as the broker being down, a missing topic or a timeout, stays in the outbox and is retried forever, so an
  outage never dead-letters healthy messages; so does a topic name the client rejects, such as `a.b`, because that
  comes back as a different code. One refused message rejects its whole batch, so the relay then sends that batch one
  message at a time and dead-letters only the refused one, stopping at the first failure of any other kind.
- Replaying a dead letter after fixing the cause:

  ```sql
  START TRANSACTION;
  INSERT INTO outbox_message (event_id, topic, tag, message_key, request_id, payload, created_at)
  SELECT event_id, topic, tag, message_key, request_id, payload, created_at FROM outbox_dead_letter WHERE id = ?;
  DELETE FROM outbox_dead_letter WHERE id = ?;
  COMMIT;
  ```

  A row inserted by hand does not wake the relay, so it leaves with the next 30-second sweep.

  The dead-letter table has its own id and does not make `event_id` unique, so a replayed message that is refused
  again gets a new row instead of blocking the move.
- Published rows are deleted, so the table only holds what is still pending.
- If events that do not commute are added, such as deductions or deletions, they need per-key ordering again: route
  by `userId` with `syncSendOrderly`, send in order and stop on failure, and partition the relay by `hash(userId)` to
  keep the throughput.
- Known limits:
  - Run one relay instance; several would send the same rows twice. `outbox.relay.enabled=false` turns it off on the
    other instances, and ShedLock or `SELECT ... FOR UPDATE SKIP LOCKED` would let them share the work.
  - A topic that never gets created keeps its messages in the outbox forever. The relay logs a warning on every pass,
    which is what an alert should watch.
  - Every pass starts from the oldest row and stops after 3 failed batches in a row. If 3 batches' worth of rows for
    such a topic pile up at the head of the outbox, the rows after them are not reached until those are fixed or
    removed. With one topic today this cannot happen; skipping a failing destination for the rest of the pass would
    fix it once the outbox carries several.

Message on `user-points-topic`, tag `ADDED`, keys `userId` and the request id:

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

Events for a user may arrive out of order or more than once. Deletions are not published, so a consumer cannot rebuild
a user's state from this stream alone once the user has been deleted: the amounts from before the deletion still add
up, and a user who earns points again starts over from a lower `total`. The payload is stored in a MySQL `JSON` column,
so field order and whitespace are not guaranteed.

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
