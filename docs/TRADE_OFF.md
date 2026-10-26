# Design trade-offs

## Cache: simple, eventually consistent

- **Total:** Redis cache-aside. A miss loads MySQL and fills Redis; a committed add/delete evicts the user's cache.
- **Top 10:** Caffeine (1 second) → Redis sorted set (5 seconds) → MySQL (`total DESC, userId ASC`).
  A miss rebuilds the complete top 10, so Redis loss and deleting a top user do not leave an incomplete leaderboard.
- Redis stores JSON members with position scores `0..9`. This preserves exact BIGINT totals and MySQL's tie ordering.
  One Lua script replaces the whole set and its TTL atomically. Business rules stay in `UserPointsCache`.
- A committed add/delete invalidates Redis and this instance's local leaderboard. Rollbacks do not invalidate caches.
  Redis failures fall back to MySQL. TTLs are configured in the cache YAML files.

**Trade-off:** a query racing with a write can refill stale data. TTL limits how long that snapshot stays after refill,
not freshness measured from the write. Frequent writes also cause more MySQL rebuilds. Revision checks and a separate
leaderboard projection are future options if stronger consistency or greater scale is required.

## Messaging: transactional outbox

Sending directly after a DB commit can lose an event if the process crashes. Instead, an Outbox row is committed
with the points. A commit triggers the relay; startup and a 30-second sweep recover pending rows.

**Trade-off:** delivery is asynchronous, at least once and unordered. A crash after sending but before deleting the
row can resend it; consumers must deduplicate by `eventId`. Broker outages keep rows pending. Messages rejected as
`MESSAGE_ILLEGAL` move to `outbox_dead_letter`; other failures are retried. Replay a dead letter by inserting it back
into `outbox_message` and deleting the dead-letter row in one transaction.

## HTTP retries: Idempotency-Key

`POST /points` requires an `Idempotency-Key`. A missing or invalid key returns 400 before any writes.

| Request | Result |
| --- | --- |
| New key | Add once and save the original 201 body |
| Same key, same normalized body | Replay the original 201 body |
| Same key, different body | 409; points stay unchanged |
| Database lock timeout | 503; retry with the same key |

The existing `user_points_records` table stores a unique `idempotency_key`, a SHA-256 hash of the normalized
request and the original response JSON. A first request saves these with the total and Outbox in one transaction.
If concurrent first requests race, the unique index admits only one; after its failed transaction rolls back, the
loser reads and replays the winner. JSON property order and surrounding reason whitespace do not affect matching.
PUT changes the reason but leaves the request hash and original response intact.

**Trade-off:** this protects short-term retries while the point record exists. DELETE removes both the points and
their retry history; sending an old key after deletion can add again. Keys have no separate TTL and are shared across
all callers of `POST /points`. A production API needing protection beyond deletion would need independent durable
storage, a retention contract and authenticated caller-specific key scope. `X-Request-Id` traces attempts;
`eventId` deduplicates MQ delivery; neither replaces the client operation key.
